# Correction+Commitment Semantics — Design Spec

## 1. Overview

This spec defines how corrections and retractions interact with the qhorus commitment lifecycle. Corrections have no commitment effect. Retractions of commitment-bearing messages cancel the active commitment via a new CANCELLED terminal state.

**Issue:** casehubio/qhorus#445
**Depends on:** casehubio/qhorus#443 (correction/retraction modeling — implemented)
**Consumer:** casehubio/chat-app (UX overhaul, issue #42)

**Scope:** qhorus-api (CommitmentState enum, events) and qhorus runtime-core (CommitmentService, MessageService dispatch wiring). No UI changes.

---

## 2. First Principles

The commitment is an **obligation envelope** — it tracks "someone owes a response to this COMMAND" via correlationId, requester, obligor, and state. It does NOT track message content. The `Commitment` record has no content field; it doesn't know what the COMMAND says.

This creates a clean semantic boundary:

| Operation | Meaning | Commitment effect |
|-----------|---------|-------------------|
| **Correction** | Same intent, better expression | None — content refinement, obligation unchanged |
| **Retraction** | I withdraw this request | Cancel — the obligation is withdrawn |

Material intent changes (requester wants something fundamentally different) should use retract + re-command, not correction. The correction path is for refinements; the retraction path is for withdrawal.

---

## 3. Corrections — No Commitment Effect

Corrections to commitment-bearing messages (COMMAND, QUERY, PROPOSE) have no effect on the commitment lifecycle. This confirms D6 from the correction spec.

The correction changes displayed content but does not touch the obligation envelope. The obligor sees the correction through normal channel delivery (fanOut). The commitment state is oblivious to the content change.

**No code changes required.** The existing commitment bypass (`correctsMessageId != null` → `commitmentId = null`) already prevents corrections from creating commitments. D16 adds an additional guard on the commitment switch block to close a latent bug (§5.3).

---

## 4. Retractions — Commitment Cancellation

### 4.1 New CommitmentState: CANCELLED

Add `CANCELLED` to the `CommitmentState` enum:

```java
/** Requester retracted the originating message; obligation withdrawn. */
CANCELLED;

public boolean isTerminal() {
    return this == FULFILLED || this == DECLINED || this == FAILED
            || this == DELEGATED || this == EXPIRED || this == CANCELLED;
}
```

CANCELLED is terminal (`isTerminal() = true`, `isActive() = false`). No further transitions are possible.

### 4.2 CommitmentService.cancel()

New method following the `decline()`/`fail()` pattern:

```java
@Transactional
public Optional<Commitment> cancel(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) return Optional.empty();
    Span span = startSpan("qhorus.commitment.cancel");
    try {
        return store.findByCorrelationId(correlationId)
                .filter(c -> c.state().isActive())
                .map(c -> {
                    setSpanAttrs(span, c, correlationId, "CANCELLED");
                    Commitment saved = store.save(c.toBuilder()
                            .state(CommitmentState.CANCELLED)
                            .resolvedAt(Instant.now())
                            .build());
                    cancelledConsumer.accept(new CommitmentCancelledEvent(
                            saved.id(), saved.correlationId(), saved.channelId(),
                            saved.obligor(), saved.requester()));
                    return saved;
                });
    } catch (Exception e) {
        recordError(span, e);
        throw e;
    } finally {
        endSpan(span);
    }
}
```

Key properties:
- **Active guard:** `c.state().isActive()` — only OPEN/ACKNOWLEDGED commitments are cancelled. Terminal commitments no-op.
- **Delegation chain:** `findByCorrelationId()` returns the active child in a delegation chain (per DELEGATED javadoc). Multi-level delegation (A → B → C) cancels C's commitment (the active leaf). B and A are already DELEGATED (terminal).
- **Idempotent:** Double retraction no-ops — CANCELLED is terminal, `isActive()` returns false.
- **resolvedAt set:** Consistent with all other terminal transitions.

### 4.3 CommitmentCancelledEvent

New event record following the existing pattern:

```java
public record CommitmentCancelledEvent(
        UUID commitmentId,
        String correlationId,
        UUID channelId,
        String obligor,
        String requester) {}
```

Emitted to a `cancelledConsumer` (injected via constructor, same pattern as `declinedConsumer` and `expiredConsumer`).

### 4.4 CdiCommitmentService

Update the CDI subclass to inject `cancelledConsumer`:

```java
@Inject
@Any
Event<CommitmentCancelledEvent> cancelledEvent;
```

Wire into the constructor's `cancelledConsumer` parameter as `cancelledEvent::fire`.

### 4.5 JPA Store — terminalStates() Update

Both `JpaCommitmentStore` and `JpaCrossTenantCommitmentStore` have a hard-coded `terminalStates()` method used in JPQL queries (`WHERE state NOT IN :terminalStates`). This list must include CANCELLED — otherwise `expireOverdue()` would overwrite CANCELLED commitments to EXPIRED (silent data corruption), and `findOpenByObligor()`/`findOpenByRequester()` would return CANCELLED commitments as "open."

Add CANCELLED to both `terminalStates()` methods:

```java
private List<CommitmentState> terminalStates() {
    return List.of(CommitmentState.FULFILLED, CommitmentState.DECLINED,
            CommitmentState.FAILED, CommitmentState.DELEGATED,
            CommitmentState.EXPIRED, CommitmentState.CANCELLED);
}
```

### 4.6 Notification Bridge — CommitmentEventNotifier

`CommitmentEventNotifier` observes `CommitmentDeclinedEvent` and `CommitmentExpiredEvent` to fire `QhorusObligationEvent` notifications. Add an observer for `CommitmentCancelledEvent`:

```java
public void onCancelled(@Observes CommitmentCancelledEvent event) {
    fire(event.channelId(), event.obligor(), event.correlationId(),
            QhorusObligationEvent.Kind.CANCELLED);
}
```

Add `CANCELLED` to `QhorusObligationEvent.Kind` enum.

### 4.7 ObligationReportService

`ObligationReportService.buildAgentSummaries()` counts commitments by explicit state comparison. Add CANCELLED to the counting logic so compliance reports correctly categorize cancelled commitments:

```java
int cancelled = (int) inWindow.stream()
        .filter(c -> c.state() == CommitmentState.CANCELLED).count();
```

### 4.8 CommitmentReader.findByCorrelationId Javadoc

Add javadoc to `CommitmentReader.findByCorrelationId()` documenting the active-child-first contract that `cancel()` relies on:

```java
/**
 * Returns the active commitment for this correlationId, or the most
 * recently created one if none are active. In a delegation chain,
 * this returns the child OPEN commitment — not the DELEGATED parent.
 */
Optional<Commitment> findByCorrelationId(String correlationId);
```

---

## 5. Dispatch Wiring

### 5.1 Hoisted correctionTarget

Promote the enforcement gate's original message lookup to method scope:

```java
// Before the enforcement gate (currently line 399)
Message correctionTarget = null;

// Inside the enforcement gate — rename 'original' to correctionTarget
if (dispatch.correctsMessageId() != null) {
    correctionTarget = messageStore.find(dispatch.correctsMessageId())
            .orElseThrow(() -> new IllegalArgumentException(
                    "correctsMessageId references a non-existent message: "
                    + dispatch.correctsMessageId()));
    // ... existing validation against correctionTarget ...
}
```

Single read, reused for both validation and cancellation. No double-read.

### 5.2 Retraction Cancellation Block

After the existing commitment switch block (after current line 503):

```java
if (correctionTarget != null && dispatch.retraction()
        && correctionTarget.commitmentId() != null) {
    commitmentService.cancel(correctionTarget.correlationId());
}
```

The `commitmentId != null` guard is data-driven: it fires only when the original message actually created a commitment at dispatch time. This is more precise than type-driven guards — `commitmentId` is set by the creation guard (line 435-439) which handles the exact set of commitment-creating types. Future-proof: if new types start creating commitments, the guard automatically includes them.

### 5.3 Correction Guard on Commitment Switch Block

The existing commitment switch (line 468) fires for any dispatch with a correlationId, including corrections. The correction spec §2.2 says corrections don't carry correlationIds, but the code doesn't enforce this. Add a guard:

```java
// Before (latent bug):
if (dispatch.correlationId() != null) {
    switch (dispatch.type()) { ... }
}

// After (fixed):
if (dispatch.correctsMessageId() == null && dispatch.correlationId() != null) {
    switch (dispatch.type()) { ... }
}
```

This closes a latent bug where a malformed correction with a correlationId and resolution type (DONE, DECLINE) could trigger commitment fulfillment/decline, contradicting §3.2's contract.

---

## 6. What This Does NOT Cover

- **Resolution message retractions** — retracting a DONE/RESPONSE/DECLINE does not revert the commitment. That would require tracking previous state and reversing terminal transitions — a fundamentally different mechanism (revert, not cancel). The `commitmentId != null` guard in §5.2 prevents this: resolution messages have `commitmentId == null`.
- **Correction chain erasure** — when an original message has corrections, should erasure cascade to correction content? Tracked separately (decision review R1-06).
- **LAST_WRITE channel correction bypass** — LAST_WRITE channels return before the correction enforcement gate. Pre-existing gap from #443.
- **`retraction` field on MessageLedgerEntry** — the ledger can't distinguish corrections from retractions without joining to the message table. Pre-existing gap from #443 (decision review R1-04).
- **Temporal limits on corrections** — no time window for how long after dispatch a message can be corrected. Discussed in decision review R1-12, not addressed.

---

## 7. Migration

No database migration needed. `CommitmentState` is an application-level enum stored via JPA `@Enumerated`. Adding CANCELLED requires no schema change — the JPA store serializes enum values as strings.

---

## 8. Testing

### 8.1 Unit Tests (CommitmentServiceTest)

| Test | Scenario |
|------|----------|
| `cancel_open_commitment` | OPEN → CANCELLED, resolvedAt set, event emitted |
| `cancel_acknowledged_commitment` | ACKNOWLEDGED → CANCELLED, resolvedAt set, event emitted |
| `cancel_terminal_commitment_noop` | FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED → no change, empty Optional |
| `cancel_null_correlationId` | null/blank correlationId → empty Optional |
| `cancel_nonexistent_correlationId` | correlationId not found → empty Optional |
| `cancel_delegated_chain` | A→B delegated, cancel returns B's CANCELLED commitment |
| `cancel_multi_level_delegation` | A→B→C delegated, cancel returns C's CANCELLED commitment, A and B remain DELEGATED |
| `cancel_idempotent` | CANCELLED → no change, empty Optional |

### 8.2 Integration Tests (MessageServiceIT or dedicated IT)

| Test | Scenario |
|------|----------|
| `retract_command_cancels_commitment` | Dispatch COMMAND → commitment OPEN → dispatch retraction → commitment CANCELLED |
| `retract_command_after_ack_cancels` | COMMAND → ACK → retraction → commitment CANCELLED |
| `retract_command_after_fulfillment_noop` | COMMAND → DONE → retraction → commitment stays FULFILLED |
| `retract_query_cancels_commitment` | QUERY → commitment OPEN → retraction → CANCELLED |
| `retract_narrative_no_commitment_effect` | NARRATIVE (no commitmentId) → retraction → no cancel call |
| `correction_does_not_affect_commitment` | COMMAND → correction → commitment unchanged |
| `correction_with_correlationId_blocked` | Correction carrying correlationId → switch block skipped (§5.3 guard) |
| `retract_propose_cancels_commitment` | PROPOSE → commitment OPEN → retraction → CANCELLED |
| `retract_delegated_command_cancels_child` | COMMAND → delegate → retraction → child CANCELLED |
| `moderator_retract_command_cancels_commitment` | COMMAND → commitment OPEN → moderator retraction → commitment CANCELLED |

---

## 9. References

| Source | What it informed |
|--------|-----------------|
| `api/message/CommitmentState.java` | State enum, isTerminal(), isActive() — 7 values, adding 8th |
| `api/message/Commitment.java` | Record fields — no content, linked by correlationId |
| `api/store/CommitmentStore.java` | findByCorrelationId contract — returns active child after delegation |
| `api/store/CommitmentReader.java` | Query methods available for cancel implementation |
| `api/gateway/CommitmentStateChangedEvent.java` | Existing event pattern |
| `api/message/CommitmentDeclinedEvent.java` | Event record pattern for cancel event |
| `api/message/CommitmentExpiredEvent.java` | Event record pattern for cancel event |
| `api/message/MessageType.java` | requiresCorrelationId() — includes JUDGMENT which doesn't create commitments |
| `runtime-core/message/CommitmentService.java` | decline()/fail() pattern for cancel(), constructor injection pattern |
| `runtime-core/message/MessageService.java:399-433` | Correction enforcement gate, original message lookup |
| `runtime-core/message/MessageService.java:435-439` | Commitment creation guard — commitmentId set only for originating types |
| `runtime-core/message/MessageService.java:468-503` | Commitment switch block — unguarded against corrections (latent bug) |
| `runtime/cdi/CdiCommitmentService.java` | CDI consumer injection pattern |
| `runtime/store/JpaCommitmentStore.java` | Hard-coded terminalStates() — must include CANCELLED |
| `runtime/store/JpaCrossTenantCommitmentStore.java` | Hard-coded terminalStates() — must include CANCELLED |
| `notification-bridge/CommitmentEventNotifier.java` | Decline/expire event observers — needs CANCELLED handler |
| `runtime/report/ObligationReportService.java` | State counting in compliance reports — needs CANCELLED category |
| Correction spec §2.2 | "correlationId is NOT auto-generated" for corrections |
| Correction spec §3.2 | "CommitmentService is NOT invoked when correctsMessageId is set" |
| Chat-app spec §12.4 | Correction visibility and commitment integrity requirement |
| Decision review R1-02 | Guard fix: commitmentId data-driven instead of requiresCorrelationId() |
| Decision review R1-03 | Delegation chain documentation requirement |
| Decision review R1-05/R1-13 | Latent bug: commitment switch unguarded against corrections |
