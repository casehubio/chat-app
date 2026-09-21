# Qhorus Correction/Retraction Modeling — Design Spec

## 1. Overview

This spec defines the backend model for message corrections and retractions in qhorus-api. Corrections replace the displayed content of a prior message; retractions withdraw a message. Both are append-only records preserving ledger immutability — the original message is never mutated.

**Issue:** casehubio/qhorus#443
**Consumer:** casehubio/chat-app (UX overhaul, issue #42) — client-side correction collapsing and editor mode are already implemented against this API contract.

**Scope:** qhorus-api (API module) and qhorus runtime-core (enforcement). Foundation-level only — no UI changes.

---

## 2. Data Model

### 2.1 New Fields on Message

Two new nullable fields on the `Message` record (api/message/Message.java) and `MessageDispatch` (api/message/MessageDispatch.java):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `correctsMessageId` | `Long` | `null` | References the original message's PK. When set, this message is a correction or retraction of the referenced message. |
| `retraction` | `boolean` | `false` | When `true` and `correctsMessageId` is set, this is a retraction (withdrawal). When `false` and `correctsMessageId` is set, this is a correction (content replacement). |

These fields are orthogonal to `MessageType` — no new enum values. A correction can carry any MessageType (typically matches the original's type). The `correctsMessageId` field is distinct from `inReplyTo` — corrections do not inflate `replyCount` and do not appear in reply threads.

### 2.2 MessageDispatch Builder Validation

When `correctsMessageId` is set:
- `content` is required (the corrected content, or retraction reason)
- `correlationId` is NOT auto-generated (corrections don't create obligations)
- `inReplyTo` is NOT set (corrections are not replies)
- The MessageType validation rules apply to the message's type as normal

When `retraction` is `true`:
- `correctsMessageId` must also be set (retraction without a target is invalid)

### 2.3 JPA Entity Changes

The `MessageEntity` (runtime-core) gains two columns:

```sql
-- V<next> migration
ALTER TABLE message ADD COLUMN corrects_message_id BIGINT;
ALTER TABLE message ADD COLUMN retraction BOOLEAN DEFAULT FALSE;
ALTER TABLE message ADD CONSTRAINT fk_corrects_message
    FOREIGN KEY (corrects_message_id) REFERENCES message(id);
CREATE INDEX idx_message_corrects ON message(corrects_message_id)
    WHERE corrects_message_id IS NOT NULL;
```

The FK ensures referential integrity. The partial index optimizes lookups for correction chains without penalizing the common case (most messages have `correctsMessageId = null`).

### 2.4 MessageLedgerEntry

`MessageLedgerEntry` gains `correctsMessageId` (Long, nullable) for audit trail completeness. The ledger entry records the correction linkage alongside all other message metadata.

---

## 3. Dispatch Pipeline

### 3.1 Enforcement Gate — MessageService.dispatch()

When `dispatch.correctsMessageId() != null`, the following validation runs inside `MessageService.dispatch()` **after** the existing ACL/rate-limit/trust checks and **before** message persistence:

1. **Original message lookup:** `messageStore.findById(dispatch.correctsMessageId())` — must exist, else reject with `IllegalArgumentException("correctsMessageId references a non-existent message")`
2. **Same channel:** `original.channelId().equals(dispatch.channelId())` — corrections must target a message in the same channel
3. **Not correcting a correction:** `original.correctsMessageId() == null` — corrections must target the original message, not a prior correction record (prevents unbounded chains). Users CAN submit multiple corrections to the same original.
4. **Authorization:**
   - **Correction** (`retraction=false`): `dispatch.sender().equals(original.sender())` — only the original sender can correct
   - **Retraction** (`retraction=true`): `dispatch.sender().equals(original.sender())` OR sender has `MemberRole.MODERATOR` in the channel (looked up via `channelMembershipStore`)
5. **SYSTEM message guard:** `original.actorType() != ActorType.SYSTEM` — SYSTEM messages cannot be corrected or retracted
6. **Correction limit:** Count existing corrections for this original (`messageStore.countByCorrectsMessageId(original.id())`). If count >= configurable max (default 10, via `casehub.qhorus.correction.max-per-message`), reject.

If any check fails, dispatch throws `IllegalArgumentException` with a descriptive message. The correction is rejected before persistence — no partial state.

### 3.2 Commitment Bypass

When `correctsMessageId` is set, `CommitmentService` is NOT invoked. Corrections do not create, fulfill, or modify commitments. The obligation lifecycle runs against the original dispatch only.

This is an intentional deferral — the semantics of corrections to commitment-bearing messages is tracked as casehubio/qhorus#445.

### 3.3 Ledger Recording

`LedgerWriteService.record()` processes corrections like any other message — a new `MessageLedgerEntry` is created with the correction's content, sender, type, and `correctsMessageId`. The `causedByEntryId` is resolved from the original message's ledger entry, making the correction chain walkable via `CausalGraphService.buildGraph()`.

No attestation is written for corrections (they are not terminal types resolving an obligation).

### 3.4 Fan-Out

`ChannelGateway.fanOut()` delivers corrections to all registered backends via the existing mechanism. The `OutboundMessage` gains `correctsMessageId` and `retraction` fields so backends can render corrections appropriately.

Connected clients (WebSocket, A2A SSE) receive corrections as new messages with the `correctsMessageId` linkage. Client-side collapsing renders the corrected view.

---

## 4. API Surface

### 4.1 Message Record Changes

`Message` (api/message/Message.java) gains two fields at the end of the record:
- `correctsMessageId` (Long, nullable)
- `retraction` (boolean)

`Message.Builder` gains corresponding setters.

### 4.2 MessageDispatch Changes

`MessageDispatch` (api/message/MessageDispatch.java) gains:
- `correctsMessageId` (Long, nullable)
- `retraction` (boolean, default false)

Builder validation:
- `retraction=true` requires `correctsMessageId` to be set

### 4.3 MessageResult Changes

`MessageResult` (api/message/MessageResult.java) gains `correctsMessageId` (Long, nullable) so callers can confirm the correction was accepted.

### 4.4 OutboundMessage Changes

`OutboundMessage` (api/gateway/OutboundMessage.java) gains `correctsMessageId` (Long, nullable) and `retraction` (boolean) for backend delivery.

### 4.5 MessageView / MessageStore

`MessageStore` gains:
- `countByCorrectsMessageId(Long messageId)` — for enforcement limit check
- Existing `findById()` is sufficient for original message lookup

`MessageView` (if used by MCP tool rendering) gains `correctsMessageId` and `retraction`.

### 4.6 MCP Tools

Two new MCP tools in `QhorusMcpTools`:

```java
@Tool("correct_message")
String correctMessage(String channel, long messageId, String correctedContent)
// Dispatches a correction via MessageService.dispatch()
// Uses the original message's type and topic

@Tool("retract_message")
String retractMessage(String channel, long messageId, String reason)
// Dispatches a retraction via MessageService.dispatch()
// reason is optional (nullable)
```

Existing `send_message` also accepts the new `corrects_message_id` and `retraction` parameters for direct API access.

### 4.7 REST API

`POST /api/channels/{channelId}/messages` already dispatches via `MessageService`. The request body gains optional `correctsMessageId` (Long) and `retraction` (boolean) fields. No new endpoints needed.

---

## 5. Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `casehub.qhorus.correction.max-per-message` | int | 10 | Maximum corrections per original message. Configurable per deployment. |

---

## 6. Migration

One Flyway migration:

```sql
-- V<next>__add_correction_fields.sql
ALTER TABLE message ADD COLUMN corrects_message_id BIGINT;
ALTER TABLE message ADD COLUMN retraction BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE message ADD CONSTRAINT fk_message_corrects
    FOREIGN KEY (corrects_message_id) REFERENCES message(id);
CREATE INDEX idx_message_corrects ON message(corrects_message_id)
    WHERE corrects_message_id IS NOT NULL;

ALTER TABLE message_ledger_entry ADD COLUMN corrects_message_id BIGINT;
```

---

## 7. What This Does NOT Cover

- **Commitment re-acknowledgment** — tracked as casehubio/qhorus#445
- **Message-scoped erasure** — tracked as casehubio/qhorus#444
- **TypeScript sync** — tracked as casehubio/blocks-ui#162 (already done on branch)
- **Client-side correction rendering** — already implemented in blocks-ui (Batch 4)
- **Server-side ChannelProjection for corrections** — deferred; client-side collapsing is sufficient for initial release

---

## 8. References

| Source | What it informed |
|--------|-----------------|
| `api/message/Message.java` | Existing message record structure, field types |
| `api/message/MessageType.java` | 11-value taxonomy, validation methods |
| `api/message/MessageDispatch.java` | Builder validation rules, field set |
| `runtime-core/message/MessageService.java` | Enforcement gate order, dispatch pipeline |
| `runtime/ledger/LedgerWriteService.java` | Ledger entry creation, causedByEntryId resolution |
| `runtime-core/message/CommitmentService.java` | Obligation lifecycle, type-based switching |
| `runtime-core/gateway/ChannelGateway.java` | Fan-out mechanism, backend delivery |
| `api/channel/ChannelMembership.java` | MemberRole.MODERATOR for retraction authorization |
| chat-app spec §4 (Corrections and Retractions) | UX requirements, authorization rules, limits |
| chat-app spec §12.1 | Upstream issue requirements |
| casehubio/qhorus#445 | Correction + commitment integrity (deferred) |
| casehubio/qhorus#444 | Message-scoped erasure (deferred) |
