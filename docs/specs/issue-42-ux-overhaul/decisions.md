# Decisions — Qhorus Correction/Retraction Modeling

## D1: Correction linkage — dedicated correctsMessageId field

**Choice:** Add a new `correctsMessageId` (Long, nullable) field to Message and MessageDispatch. Keeps `inReplyTo` for reply threading.
**Alternatives:**
- Reuse `inReplyTo` — simpler schema but inflates replyCount and pollutes reply threads
**Rationale:** Corrections are semantically distinct from replies. Using `inReplyTo` would overload its meaning and cause reply counts to include correction records. The chat-app UI already consumes `correctsMessageId` as a separate field.
**Trade-offs:** One more column on the message table. Migration needed.
**Sources:** chat-app spec §4.5 (D3, R1-03), Message.java (api/message/), MessageDispatch.java
**Exploration:** quick
**Status:** captured

## D2: Type model — orthogonal field, no new MessageType values

**Choice:** Keep the existing 11-value MessageType enum unchanged. `correctsMessageId` is an orthogonal nullable field on MessageDispatch/Message. A message with `correctsMessageId` set is a correction regardless of its MessageType.
**Alternatives:**
- New CORRECTION/RETRACTION enum values — explicit in taxonomy but requires no-op cases in CommitmentService, every MessageType switch, watchdog, protocol eval, A2ATaskStateMapper, and blocks-ui speechActBorderColor
- Separate CorrectionRecord entity — cleanest separation but doubles the write path and needs its own fan-out mechanism
**Rationale:** Corrections are a meta-operation on existing messages, not a new speech act category. Adding types to the enum contaminates a well-designed taxonomy with operational bookkeeping. The orthogonal field approach adds zero changes to CommitmentService, LedgerWriteService, ChannelGateway, or any existing switch statement.
**Trade-offs:** Corrections don't have their own MessageType identity — consumers must check `correctsMessageId != null` to detect them. Acceptable: the field is explicit and easy to query.
**Sources:** MessageType.java (11 values, semantic roles), CommitmentService.java (switch on type), chat-app spec §4.5
**Exploration:** quick
**Status:** captured

## D3: Retraction discrimination — boolean retraction field

**Choice:** Add `retraction` (boolean, default false) to MessageDispatch/Message alongside `correctsMessageId`. When `retraction=true` and `correctsMessageId` is set, the message is a retraction. When `retraction=false` and `correctsMessageId` is set, it's a correction.
**Alternatives:**
- Content-based discrimination (empty content = retraction) — fragile, requires content inspection
- Payload JSON flag — less discoverable, requires JSON parsing
**Rationale:** Explicit boolean is unambiguous and requires no content inspection. The field is self-documenting and queryable.
**Trade-offs:** One more column. Retraction with non-null content is valid (carries the retraction reason).
**Depends on:** D1 (correctsMessageId field)
**Sources:** chat-app spec §4.2 (retraction with optional reason)
**Exploration:** quick
**Status:** captured

## D4: Authorization — MessageService enforcement gate

**Choice:** Add correction authorization checks inside `MessageService.dispatch()`, alongside existing ACL, rate limit, and trust checks. When `correctsMessageId` is set: (1) look up the original message, (2) verify same channel, (3) for corrections: verify sender matches original sender, (4) for retractions: verify sender matches original sender OR sender has MODERATOR role in the channel.
**Alternatives:**
- Separate CorrectionService — creates a second dispatch entry point, splitting the enforcement surface
- MCP tool layer only — bypassed by direct MessageService callers
**Rationale:** MessageService.dispatch() is the single enforcement gate. All dispatch paths (MCP tools, REST, A2A, connectors) pass through it. Adding correction validation here guarantees uniform enforcement.
**Trade-offs:** MessageService gains another validation step. Acceptable: the check is conditional on `correctsMessageId != null` and adds ~10 lines.
**Depends on:** D1 (correctsMessageId), D3 (retraction flag for moderator check)
**Sources:** MessageService.java (enforcement gate), ChannelMembership.java (MemberRole.MODERATOR)
**Exploration:** quick
**Status:** captured

## D5: Ledger treatment — own entry with causedByEntryId link

**Choice:** Corrections create their own MessageLedgerEntry via the existing `LedgerWriteService.record()` path. `causedByEntryId` is resolved to the original message's ledger entry, making the correction chain walkable via `CausalGraphService`. No attestation written (corrections are not terminal types).
**Alternatives:**
- Skip ledger for corrections — breaks the immutable audit trail guarantee
**Rationale:** The ledger is the complete, immutable channel history. Corrections must be part of it. The existing ledger infrastructure handles this with zero changes — `LedgerWriteService.record()` already resolves `causedByEntryId` and creates entries for all 11 message types.
**Trade-offs:** Correction records appear in ledger queries. Consumers that render corrected views must collapse them (chat-app already does this client-side).
**Sources:** LedgerWriteService.java (record()), CausalGraphService.java, MessageLedgerEntry.java
**Exploration:** quick
**Status:** captured

## D6: Commitment interaction — no effect

**Choice:** Corrections have no effect on the commitment lifecycle. The commitment stands against the original dispatch. The corrected content is for human display; the obligation was created by the original message.
**Alternatives:**
- Reset commitment to OPEN on correction — more semantically correct but complex, needs CommitmentService changes and re-acknowledgment notification
**Rationale:** This is an intentional deferral. The semantics of corrections to commitment-bearing messages (does a correction to a COMMAND require re-acknowledgment?) is a separate design question tracked as casehubio/qhorus#445. The initial implementation avoids coupling corrections to the obligation lifecycle.
**Trade-offs:** A corrected COMMAND displays different content than what the commitment was created against. Acceptable for initial implementation; the gap is documented.
**Depends on:** D2 (orthogonal field — CommitmentService doesn't see corrections)
**Sources:** CommitmentService.java, casehubio/qhorus#445
**Exploration:** quick
**Status:** superseded by D13

---

# Decisions — Message-Scoped Content Erasure (qhorus#444)

## D7: Erasure scope — content field only

**Choice:** Message-scoped erasure nulls only the `content` field on `MessageLedgerEntry`. Other fields (`target`, `topic`, `metadata`, `contextRefs`) are structural/operational and already covered by the existing actor-scoped identity erasure (token→identity mapping severance).
**Alternatives:**
- Content + metadata — metadata could contain PII, but the field contract already prohibits PII (`LedgerEntry.metadata` javadoc: "Must NOT contain PII")
- All content-bearing fields — over-erasure destroys structural integrity needed for causal chain traversal
- Configurable per-request — complexity without a known consumer need
**Rationale:** The `content` field is the only field that routinely carries personal data (message body text). Other fields are either identifiers (covered by actor-scoped erasure) or structural (needed for graph integrity). Minimizing erasure scope preserves maximum ledger utility.
**Trade-offs:** If a consumer violates the metadata PII contract, that data won't be erased by this API. Acceptable — the contract violation is the root cause, not the erasure scope.
**Sources:** MessageLedgerEntry.java (domainContentBytes), LedgerEntry.java (metadata javadoc), LedgerErasureService.java
**Exploration:** quick
**Status:** captured

## D8: Layer placement — qhorus layer, message-specific

**Choice:** The message-scoped erasure API lives in the qhorus runtime layer, not the generic ledger layer. No SPI or abstraction at the ledger layer.
**Alternatives:**
- Ledger layer, generic field-erasure SPI — speculative, no other subclass needs this today
- Both (ledger SPI + qhorus impl) — maximum extensibility but YAGNI
**Rationale:** The ledger layer has no concept of `content` — `domainContentBytes()` is opaque. Message-scoped erasure is inherently message-specific. Other subclasses (Tarkus, etc.) can add their own erasure when they need it, following this as a pattern.
**Trade-offs:** If another subclass needs field-level erasure, they build their own. No reusable abstraction. Acceptable — pattern is more valuable than premature abstraction.
**Sources:** JpaLedgerEntry.java (abstract domainContentBytes), LedgerErasureService.java (actor-scoped only)
**Exploration:** quick
**Status:** captured

## D9: Hash chain interaction — two-phase model with tombstone

**Choice:** Two-phase erasure: (1) append an erasure tombstone entry to the Merkle chain (preserves chain integrity, provides audit trail), then (2) physical content nulling on the original entry triggered by GDPR delete request. The tombstone records the original digest for pre-erasure integrity verification.
**Alternatives:**
- Accept breakage + record digest (single-phase) — combines tombstone and mutation in one operation, no normative record of intent before physical deletion
- Recompute digest after erasure — internally consistent but history is rewritten, indistinguishable from tampering without external proof
- Exclude content from hash scope — fundamental architecture change to the ledger model, makes content non-tamper-evident for all entries
**Rationale:** The two-phase model mirrors the existing actor-scoped pattern (ErasureReceiptLedgerEntry is recorded, then identity mapping is severed). The tombstone preserves chain integrity and provides a verifiable record of authorized erasure. Physical deletion happens as a controlled compliance operation. Verifiers encountering a broken digest can look up the tombstone to confirm legitimacy.
**Trade-offs:** Two operations instead of one. The content remains in the DB between tombstone recording and physical deletion. Acceptable — the tombstone marks intent; physical deletion is the compliance gate.
**Sources:** LedgerErasureService.java (two-phase pattern), ErasureReceiptLedgerEntry.java (receipt model), JpaLedgerEntryRepository.java (hash chain pipeline), LedgerEntry.java (canonicalBytes, digest)
**Exploration:** quick
**Status:** captured

## D10: Digest preservation — original digest stored in tombstone

**Choice:** The erasure tombstone entry records the original `digest` value from the erased entry. Verifiers can confirm: (1) a tombstone exists for the entry, (2) the recorded digest matches what the entry had before erasure.
**Alternatives:**
- Leave digest as-is on erased entry — simpler but no way to prove the entry was valid before erasure
- Null the digest — makes mutation obvious but loses the original value entirely
**Rationale:** The original digest is the proof that the entry was tamper-evident before erasure. Without it, there's no way to distinguish "entry was valid, then erased" from "entry was always invalid." The tombstone is the natural place to store this.
**Trade-offs:** Tombstone schema is slightly larger. Acceptable — one extra field per erasure event.
**Depends on:** D9 (two-phase model with tombstone)
**Sources:** LedgerEntry.java (digest field), ErasureReceiptLedgerEntry.java (receipt schema)
**Exploration:** quick
**Status:** captured

## D11: Agent signature handling — null on erased entry

**Choice:** Physical content deletion also nulls `agentSignature`, `agentPublicKey`, and `agentKeyRef` on the erased entry. A stale signature that fails verification is worse than no signature.
**Alternatives:**
- Preserve original signature in tombstone — enables offline pre-erasure verification but adds complexity to the tombstone schema
- Leave signature as-is — requires tombstone-aware verification logic everywhere
**Rationale:** After content erasure, the signature covers bytes that no longer exist. Leaving it in place means every signature verifier must first check for tombstones to avoid false negatives. Nulling the fields is honest — the entry has been modified, and the signature is no longer valid.
**Trade-offs:** Original signature is lost. If pre-erasure signature verification is needed later, it can't be done. Acceptable — the tombstone's digest preservation already provides integrity proof.
**Depends on:** D9 (two-phase model), D10 (digest in tombstone)
**Sources:** LedgerEntry.java (agentSignature, agentPublicKey, agentKeyRef)
**Exploration:** quick
**Status:** captured

## D12: Authorization — admin/compliance only

**Choice:** Message-scoped erasure is an admin/compliance operation, not a user-facing feature. No per-actor authorization check — same pattern as the existing actor-scoped `LedgerErasureService.erase()`.
**Alternatives:**
- Sender + admin — blurs the line between retraction (normative, user-facing) and erasure (compliance, admin)
- Configurable per deployment — adds authorization complexity without a known need
**Rationale:** Users retract messages (normative, visible in feed via correction/retraction model from #443). Admins erase content (compliance, removes from DB). These are distinct operations with distinct semantics. Keeping erasure admin-only maintains this separation.
**Trade-offs:** Users can't self-erase — they must request erasure through a compliance channel. This is standard GDPR practice.
**Depends on:** D8 (qhorus layer placement)
**Sources:** LedgerErasureService.java (no authorization check), chat-app spec §4.7 (retraction vs erasure distinction)
**Exploration:** quick
**Status:** captured

---

# Decisions — Correction+Commitment Semantics (qhorus#445)

## D13: Corrections have no commitment effect — confirms D6

**Choice:** Corrections to commitment-bearing messages (COMMAND, QUERY, PROPOSE, JUDGMENT) have no effect on the commitment lifecycle. The commitment stands against the original dispatch. The correction changes displayed content but does not touch the obligation envelope.
**Alternatives:**
- Notification only — emit CommitmentCorrectedEvent when active commitment's originating message is corrected. Adds surface area for a signal already delivered via channel fanOut.
- State-dependent — no effect if OPEN, reset to OPEN if ACKNOWLEDGED. Couples the commitment state machine to message content, which the commitment record doesn't track.
**Rationale:** The commitment is an obligation envelope — it tracks "someone owes a response" via correlationId, requester, obligor, and state. It does NOT track message content. Corrections refine content; the obligation ("respond to this") is structurally unchanged. If a correction is so material it changes the actual intent, the requester should retract + re-command — retraction is the mechanism for "I want something fundamentally different." This preserves clean layer separation between the message layer (content) and commitment layer (obligations).
**Trade-offs:** A corrected COMMAND displays different content than what the commitment was created against. The obligor sees the correction through normal channel delivery (fanOut) but the commitment state is oblivious. Acceptable — the correction is visible in the feed, and material intent changes should use retraction.
**Depends on:** D6 (commitment interaction deferral), D1 (correctsMessageId field)
**Sources:** CommitmentService.java (state machine), MessageService.java:435-439 (commitmentId guard), correction spec §3.2 (commitment bypass), Commitment.java (record — no content field), chat-app spec §12.4
**Exploration:** deep-analysis
**Status:** captured

## D14: Retractions cancel active commitments — new CANCELLED state

**Choice:** When a retraction targets a message that created a commitment, and the commitment is still active (OPEN or ACKNOWLEDGED), the commitment transitions to a new terminal state: CANCELLED. A new `CommitmentService.cancel(String correlationId)` method handles this, following the pattern of `decline()` and `fail()`. The guard checks `correctionTarget.commitmentId() != null` — data-driven, not type-driven (see D16).

**Delegation chain handling:** `cancel()` uses `findByCorrelationId()`, which returns the active child in a delegation chain (per `CommitmentState.DELEGATED` javadoc). In a multi-level delegation (A → B → C), cancellation hits C's commitment (the active leaf). B and A are already terminal (DELEGATED) — untouched. `CommitmentCancelledEvent` fires for C's commitment with A as requester and C as obligor. The current obligor (C) is the relevant notification target regardless of how many delegation hops occurred. Terminal commitments no-op — `cancel()` filters on `isActive()`, matching the pattern of `acknowledge()`, `fulfill()`, etc.

**Resolution message retractions (out of scope):** Retracting a DONE, RESPONSE, DECLINE, etc. does NOT cancel or revert commitments. That would require a revert mechanism (tracking previous state, reversing terminal transitions) — a fundamentally different operation. The `commitmentId != null` guard in D16 prevents this: resolution messages have `commitmentId == null` because only originating messages (COMMAND, QUERY, PROPOSE) set commitmentId at dispatch time.

**Alternatives:**
- Repurpose DECLINED — treat requester retraction as a decline. No new state, but DECLINED means "obligor refused" — semantic overload. CommitmentDeclinedEvent would fire with wrong semantics.
- Soft cancel (no state change) — leave commitment as-is, obligor sees retraction via fanOut and can DECLINE manually. Leaves dangling active commitments until obligor acts or expiry fires.
**Rationale:** Retracting a COMMAND means "I withdraw this request." The obligation itself is withdrawn — not by the obligor refusing, but by the requester cancelling. This is semantically distinct from DECLINED (obligor-initiated) and EXPIRED (infrastructure-generated). A new terminal state preserves the state machine's semantic precision. The existing correction spec §3.2 bypass ("CommitmentService is NOT invoked when correctsMessageId is set") has a gap: retractions also set correctsMessageId, so retractions also bypass CommitmentService. This decision closes that gap.
**Trade-offs:** New enum value in CommitmentState requires updating isTerminal() and any exhaustive switches. Blast radius is bounded — CommitmentState has 7 values, adding an 8th follows the established pattern.
**Depends on:** D13 (corrections have no effect — scoping), D3 (retraction boolean field)
**Sources:** CommitmentState.java (7 values, isTerminal, isActive), CommitmentService.java (decline/fail pattern), CommitmentState.DELEGATED javadoc (findByCorrelationId returns child), MessageService.java:399-433 (enforcement gate), MessageService.java:468-503 (commitment switch block), decision review R1-03 (delegation documentation)
**Exploration:** deep-analysis
**Status:** revised

## D15: CommitmentCancelledEvent emitted on cancellation

**Choice:** Cancellation emits a `CommitmentCancelledEvent` to a `cancelledConsumer`, following the existing pattern of `CommitmentDeclinedEvent` → `declinedConsumer` and `CommitmentExpiredEvent` → `expiredConsumer`. The event carries channelId, commitment, obligor, and requester for downstream consumers (gateway notification, agent watchdog).
**Alternatives:**
- No event — state change only, retraction message delivered via fanOut is the notification. Breaks the pattern established by decline and expire.
**Rationale:** Every terminal commitment transition emits an event for downstream consumers. Cancellation without an event would be the only silent terminal transition. Following the pattern ensures consumers that track commitment lifecycle (dashboards, agent watchdogs, notification services) can react to cancellations.
**Trade-offs:** One more event type and consumer injection in CommitmentService constructor. Minimal — follows the established 1:1 pattern of terminal state → event.
**Depends on:** D14 (CANCELLED state)
**Sources:** CommitmentDeclinedEvent.java (event pattern), CommitmentExpiredEvent.java (event pattern), CommitmentService.java:156-159 (decline event emission), CdiCommitmentService.java (consumer injection)
**Exploration:** quick
**Status:** captured

## D16: Dispatch wiring — hoisted correctionTarget with data-driven guard

**Choice:** Two changes to `MessageService.dispatch()`:

**(1) Correction guard on commitment switch block.** The existing commitment switch at line 468 fires for any dispatch with a correlationId, including corrections. The correction spec §2.2 says corrections don't carry correlationIds, but the code doesn't enforce this. Add `dispatch.correctsMessageId() == null` to the switch guard:
```java
if (dispatch.correctsMessageId() == null && dispatch.correlationId() != null) {
    switch (dispatch.type()) { ... }
}
```
This closes a latent bug where a malformed correction with a correlationId and resolution type (DONE, DECLINE) could trigger commitment fulfillment/decline.

**(2) Retraction cancellation block.** Hoist the enforcement gate's original message lookup into a `correctionTarget` variable visible to the full method scope. After the commitment switch block (post-persistence), a new guard block triggers cancellation:
```java
if (correctionTarget != null && dispatch.retraction()
        && correctionTarget.commitmentId() != null) {
    commitmentService.cancel(correctionTarget.correlationId());
}
```
The `commitmentId != null` guard checks whether the original message actually created a commitment at dispatch time — data-driven, not type-driven. This is more precise than `requiresCorrelationId()` which includes JUDGMENT (not commitment-creating). Future-proof: if a new type starts creating commitments, the guard automatically includes it.

**Delegation chain handling:** `cancel()` uses `findByCorrelationId()`, which returns the active child in a delegation chain (per CommitmentState.DELEGATED javadoc). A retraction of a COMMAND that was delegated A → B → C cancels C's commitment (the active leaf). B and A are already terminal (DELEGATED). CommitmentCancelledEvent fires for C's commitment with A as requester and C as obligor. Terminal commitments (already FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED) no-op — `cancel()` filters on `isActive()`.

**Alternatives:**
- `requiresCorrelationId()` guard — type-driven, includes JUDGMENT which doesn't create commitments. Fires cancel() on nonexistent commitments (harmless but semantically wrong). Requires maintaining a parallel type enumeration. (Rejected after decision review R1-02.)
- Inline in enforcement gate — mixes validation with side effects. Transaction rollback risk.
- No correction guard on commitment switch — relies on callers not setting correlationId on corrections. Fragile contract.
**Rationale:** (1) Data-driven guard (`commitmentId != null`) is precisely correct — fires only when the original message created a commitment, regardless of type. (2) Correction guard on the commitment switch block closes a latent bug and makes the spec §3.2 contract ("CommitmentService is NOT invoked for corrections") enforceable in code. (3) Hoisted `correctionTarget` eliminates re-reads and makes data flow explicit. (4) Separation of concerns preserved.
**Trade-offs:** `correctionTarget` bridges two sections of dispatch() across a ~100-line gap. Acceptable — named, assignable once, null when irrelevant. The correction guard on the switch block is a one-line change to existing code.
**Depends on:** D14 (CANCELLED state), D4 (authorization gate structure)
**Sources:** MessageService.java:399-433 (enforcement gate), MessageService.java:435-439 (commitmentId creation guard), MessageService.java:468-503 (commitment switch — unguarded against corrections), Message.java (commitmentId field), CommitmentState.DELEGATED javadoc (findByCorrelationId returns child), CommitmentService.java:100-119 (acknowledge pattern), decision review R1-02 (guard fix), R1-05/R1-13 (latent bug)
**Exploration:** deep-analysis
**Status:** revised
