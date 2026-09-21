# Qhorus Message-Scoped Content Erasure — Design Spec

## 1. Overview

This spec defines a message-scoped content erasure API for qhorus, complementing the existing actor-scoped erasure in `LedgerErasureService`. The actor-scoped API severs the token→identity mapping, making entries permanently anonymous. The message-scoped API goes further: it physically removes the message `content` field from a specific `MessageLedgerEntry`, addressing GDPR Art.17 scenarios where a user wants specific content removed — not their entire identity erased.

**Issue:** casehubio/qhorus#444
**Consumer:** casehubio/chat-app (UX overhaul, issue #42) — retraction is the user-facing action; erasure is the admin/compliance action that physically removes content.

**Scope:** qhorus runtime-core (erasure service, repository) and the ledger layer (extended `LedgerErasureService`). No UI changes — erasure is an admin/compliance operation.

**Key distinction:** Retraction (qhorus#443) is normative — "I withdraw what I said." Erasure is legal — "delete this data under GDPR Art.17." Retraction is visible in the feed; erasure removes content from the database.

---

## 2. Two-Phase Erasure Model

Message-scoped erasure follows the same two-phase pattern as the existing actor-scoped erasure:

### Phase 1: Tombstone (ledger operation)

Append an erasure tombstone entry to the Merkle chain. This:
- Preserves chain integrity (append-only, no mutation)
- Records the original `digest` of the target entry (pre-erasure integrity proof)
- Records which entry and which fields are marked for erasure
- Provides a tamper-evident audit trail of the erasure request

The tombstone is a `JpaLedgerEntry` subclass — `MessageContentErasureEntry` — persisted via the existing `LedgerEntryRepository.save()` pipeline with full hash chain participation.

### Phase 2: Physical deletion (database operation)

A GDPR delete request triggers the physical content nulling on the original `MessageLedgerEntry`. This:
- Sets `content` to `null`
- Nulls `agentSignature`, `agentPublicKey`, and `agentKeyRef` (now invalid)
- Is a direct JPA update — not a ledger operation
- Runs in the same transaction as the tombstone write

After physical deletion, the original entry's `digest` is known-broken. Verifiers encountering a broken digest look up the tombstone to confirm the breakage was authorized.

### Why two phases in one transaction

The tombstone must be written before (or atomically with) the content deletion, so that a crash between the two operations never leaves content deleted without an audit trail. Both operations run in the same `@Transactional` method — the tombstone is persisted first, then the content is nulled.

---

## 3. Data Model

### 3.1 MessageContentErasureEntry

A new JPA entity extending `JpaLedgerEntry`, persisted to the Merkle chain as the erasure tombstone.

```java
@Entity
@Table(name = "message_content_erasure_entry")
@DiscriminatorValue("QHORUS_MESSAGE_CONTENT_ERASURE")
public class MessageContentErasureEntry extends JpaLedgerEntry {

    /** The message_ledger_entry PK whose content was erased. */
    @Column(name = "erased_entry_id", nullable = false)
    public UUID erasedEntryId;

    /** The message table PK (mirrors MessageLedgerEntry.messageId). */
    @Column(name = "erased_message_id", nullable = false)
    public Long erasedMessageId;

    /** Channel the erased message belongs to. */
    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    /** The Merkle leaf hash of the entry before erasure — proof of pre-erasure integrity. */
    @Column(name = "original_digest", nullable = false)
    public String originalDigest;

    /** The legal basis or trigger for this erasure event. */
    @Enumerated(EnumType.STRING)
    @Column(name = "erasure_reason", nullable = false)
    public ErasureReason erasureReason;
}
```

`subjectId` is set to the channel UUID (same as the erased entry), so the tombstone appears in the channel's ledger sequence. `entryType` is `EVENT`. `actorId` is `system:message-erasure`. `actorType` is `SYSTEM`.

### 3.2 domainContentBytes()

```java
@Override
protected byte[] domainContentBytes() {
    String canonical = String.join("|",
        erasedEntryId != null ? erasedEntryId.toString() : "",
        erasedMessageId != null ? erasedMessageId.toString() : "",
        channelId != null ? channelId.toString() : "",
        originalDigest != null ? originalDigest : "",
        erasureReason != null ? erasureReason.name() : ""
    );
    return canonical.getBytes(StandardCharsets.UTF_8);
}
```

### 3.3 Migration

One Flyway migration in the qhorus runtime module:

```sql
-- V<next>__add_message_content_erasure_entry.sql

CREATE TABLE message_content_erasure_entry (
    id              UUID NOT NULL PRIMARY KEY,
    erased_entry_id UUID NOT NULL,
    erased_message_id BIGINT NOT NULL,
    channel_id      UUID NOT NULL,
    original_digest VARCHAR(255) NOT NULL,
    erasure_reason  VARCHAR(50) NOT NULL,
    CONSTRAINT fk_mce_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_mce_erased_entry ON message_content_erasure_entry(erased_entry_id);
CREATE INDEX idx_mce_channel ON message_content_erasure_entry(channel_id);
```

The table follows JOINED inheritance — the `id` column is an FK to `ledger_entry(id)`.

---

## 4. Erasure Service

### 4.1 MessageContentErasureService (qhorus runtime)

A new `@ApplicationScoped` service in `io.casehub.qhorus.runtime.privacy`. This is in qhorus — not in the ledger layer — because message-scoped erasure requires knowledge of `MessageLedgerEntry` and `MessageEntity`, which are qhorus-specific. It follows the same pattern as `LedgerErasureService` (tombstone + physical deletion) but lives at the correct layer.

```java
@ApplicationScoped
public class MessageContentErasureService {

    @Inject LedgerEntryRepository ledger;
    @Inject @PersistenceUnit("qhorus") EntityManager em;
    @Inject MessageStore messageStore;

    public record MessageErasureResult(
            UUID erasedEntryId,
            Long erasedMessageId,
            UUID channelId,
            UUID tombstoneEntryId) {
    }

    @Transactional
    public MessageErasureResult eraseMessageContent(
            final UUID ledgerEntryId,
            final ErasureReason reason) {
        // 1. Look up the MessageLedgerEntry
        // 2. Validate: must exist, must be a QHORUS_MESSAGE, content must be non-null
        // 3. Build and persist the tombstone (Phase 1)
        // 4. Null content on MessageLedgerEntry + MessageEntity (Phase 2)
        // 5. Null agentSignature, agentPublicKey, agentKeyRef on the ledger entry
        // 6. Return result
    }
}
```

### 4.2 Validation

Before erasure, the service validates:

1. **Entry exists:** `em.find(MessageLedgerEntry.class, ledgerEntryId)` — must return non-null
2. **Content is non-null:** if `content` is already null, the entry was already erased — return without re-erasing (idempotent)
3. **No duplicate tombstone:** check if a `MessageContentErasureEntry` already exists for this `erasedEntryId` — if so, skip tombstone creation (idempotent)

### 4.3 Physical Content Deletion

After the tombstone is persisted, the physical content deletion runs as a direct JPA update on the `MessageLedgerEntry`:

```java
entry.content = null;
entry.agentSignature = null;
entry.agentPublicKey = null;
entry.agentKeyRef = null;
em.merge(entry);
```

This is a mutation of an existing entry — the only place in the entire ledger where this is permitted. The tombstone's `originalDigest` proves the entry was valid before this mutation.

### 4.4 Message Table Erasure

The `message` table (runtime `MessageEntity`) also holds message content. The erasure service nulls `MessageEntity.content` in the same transaction:

```java
MessageEntity message = messageStore.findById(entry.messageId);
if (message != null) {
    message.content = null;
    em.merge(message);
}
```

This is mandatory — content exists in both the ledger entry and the message store. Erasing only the ledger copy leaves PII in the message table. The message record itself is preserved (structural metadata: sender, channel, type, timestamp).

---

## 5. API Surface

### 5.1 MessageContentErasureService

New method:

```java
@Transactional
public MessageErasureResult eraseMessageContent(UUID ledgerEntryId, ErasureReason reason)
```

Parameters:
- `ledgerEntryId` — the UUID primary key of the `MessageLedgerEntry` to erase
- `reason` — the legal basis (from `ErasureReason` enum)

Returns `MessageErasureResult` with the erased entry ID, message ID, channel ID, and tombstone entry ID.

### 5.2 MCP Tool

One new MCP tool in `QhorusMcpTools`:

```java
@Tool("erase_message_content")
String eraseMessageContent(String ledgerEntryId, String reason)
```

Admin-only tool. Accepts the ledger entry UUID and erasure reason. Returns a confirmation message with the tombstone entry ID.

### 5.3 Verification Query

`MessageContentErasureEntry` supports a named query for verification:

```java
@NamedQuery(
    name = "MessageContentErasureEntry.findByErasedEntryId",
    query = "SELECT e FROM MessageContentErasureEntry e " +
            "WHERE e.erasedEntryId = :erasedEntryId AND e.tenancyId = :tenancyId")
```

Verifiers encountering a broken digest on a `MessageLedgerEntry` can query for a tombstone to confirm the breakage was authorized.

---

## 6. Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `casehub.qhorus.erasure.message-content.enabled` | boolean | `true` | Enable/disable message-scoped content erasure. When disabled, `eraseMessageContent()` throws `UnsupportedOperationException`. |

No additional configuration — the erasure receipt is always written (it IS the tombstone; without it, there is no audit trail).

---

## 7. Verifier Integration

### 7.1 Hash Chain Verification

Existing Merkle chain verifiers must be made tombstone-aware:

1. When a leaf hash does not match the recomputed `digest`, check for a `MessageContentErasureEntry` with `erasedEntryId = entry.id`
2. If a tombstone exists, verify `tombstone.originalDigest` matches the entry's stored `digest` — this confirms the entry was valid before erasure
3. Report the entry as "erased (authorized)" rather than "tampered"

This is a read-path change only — the verification logic learns to distinguish authorized erasure from tampering.

### 7.2 Agent Signature Verification

Signature verifiers already handle `null` signatures (entries without signing are valid). After erasure, `agentSignature` is null, so existing verification skips these entries naturally.

---

## 8. What This Does NOT Cover

- **Actor-scoped erasure** — unchanged, still severs token→identity mapping
- **Bulk message erasure** — erasing all messages from an actor in a channel. Can be built on top of this API by querying `MessageLedgerEntry` by `actorId` and calling `eraseMessageContent()` for each. Not needed for initial implementation.
- **WebSocket notification of erasure** — connected clients are not notified when content is erased. The next channel load will show the message without content. Real-time erasure notification is a future enhancement if needed.
- **Correction chain erasure** — when the original message has corrections, should the correction content also be erased? Corrections carry their own `content` field. Erasing only the original leaves correction content intact. A separate design question — track if needed.
- **Read-path filtering** — channel rendering already handles `content = null` (existing messages without content render as empty). No UI changes needed.

---

## 9. References

| Source | What it informed |
|--------|-----------------|
| `ledger/runtime/privacy/LedgerErasureService.java` | Existing actor-scoped erasure pattern, two-phase model, receipt writing |
| `ledger/runtime/model/ErasureReceiptLedgerEntry.java` | Tombstone schema pattern, `domainContentBytes()` convention |
| `ledger/api/model/LedgerEntry.java` | `canonicalBytes()`, `digest`, agent signature fields, hash chain design |
| `ledger/runtime/model/jpa/JpaLedgerEntry.java` | JOINED inheritance, `@EntityListeners`, `@PrePersist` |
| `ledger/runtime/repository/jpa/JpaLedgerEntryRepository.java` | Hash chain pipeline: sequence → enrich → hash → sign → persist |
| `qhorus/runtime/ledger/MessageLedgerEntry.java` | `domainContentBytes()` includes `content`, `channelId`, `messageId` etc. |
| `qhorus/runtime/ledger/MessageLedgerEntryRepository.java` | Query patterns, tenant scoping |
| `ledger/api/model/ErasureReason.java` | Existing erasure reasons enum |
| Correction/retraction spec (qhorus#443) | Retraction vs erasure distinction |
| casehubio/qhorus#444 | Issue: message-scoped content erasure API |
| Decisions D7-D12 | All design decisions for this spec |
