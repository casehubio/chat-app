---
entry_type: note
subtype: diary
title: "The Obligation Envelope"
date: 2026-09-21
author: mdp
tags: [qhorus, commitments, corrections, erasure, state-machines, chat-app]
status: draft
projects: [casehubio/chat-app]
series: issue-42-ux-overhaul
---

# The Obligation Envelope

The correction model landed. Three issues on the queue, three answers to the same question: what does the commitment lifecycle know about message content?

Nothing. That was the answer every time.

## The Content-Blind Commitment

The `Commitment` record stores `correlationId`, `requester`, `obligor`, `state`, `expiresAt`. It does not store message content. It does not know what the COMMAND says. It tracks "someone owes a response" — an obligation envelope, not a contract of terms.

This distinction drove every decision. When a COMMAND is corrected — typo fix, clarification, refined wording — the envelope is unchanged. The obligation ("respond to this") persists. The obligor sees the updated content through normal channel delivery. The commitment state machine never hears about it.

The interesting case was retraction. Correcting a COMMAND is a content refinement. Retracting a COMMAND is an obligation withdrawal. Those are fundamentally different operations, and the code was treating them identically — the correction spec's blanket bypass ("CommitmentService is NOT invoked when `correctsMessageId` is set") applied to both.

So retractions needed their own commitment path. A new terminal state: CANCELLED. The requester withdrew the request — distinct from DECLINED (obligor refused) and EXPIRED (infrastructure timeout). Three different actors ending the same obligation for three different reasons, each semantically precise.

## The Guard That Was Wrong

The first design used `requiresCorrelationId()` to determine which retractions should cancel commitments. The method returns true for COMMAND, QUERY, PROPOSE, and JUDGMENT. But the commitment creation guard only creates commitments for the first three — JUDGMENT uses COMMAND composition and doesn't create its own. The method answers "does this type carry a correlationId?" not "does this type create a commitment?"

Claude caught this during decision review. The fix: check the data, not the type. `correctionTarget.commitmentId() != null` — fires only when the original message actually created a commitment at dispatch time. No type enumeration to maintain, no semantic mismatch.

## The Maintenance Trap

The JPA stores had a `terminalStates()` method returning a hard-coded `List.of(FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED)`. Five values. The enum's `isTerminal()` method returns the same set. Two parallel definitions of "terminal" that must stay in sync.

Adding CANCELLED to `isTerminal()` without updating every hard-coded list would cause `expireOverdue()` to overwrite CANCELLED commitments to EXPIRED — silent data corruption with no compile-time warning.

The fix: `Arrays.stream(CommitmentState.values()).filter(CommitmentState::isTerminal).toList()` as a static field. One definition of "terminal," derived from the enum method, computed once at class load. Future states are automatically included.

This is a general JPA pattern worth knowing. JPQL can't call Java methods, so developers hard-code enum value lists in query parameters. The hard-coded list diverges from the enum's classification method on the next addition. Always derive.

## The Latent Bug

The commitment switch block in `MessageService.dispatch()` fires for any dispatch with a `correlationId`. The correction spec says corrections don't carry correlationIds — but the code doesn't enforce this. A malformed correction with a correlationId and type DONE would trigger `commitmentService.fulfill()`.

One guard: `if (dispatch.correctsMessageId() == null && dispatch.correlationId() != null)`. The spec's contract, enforced in code.

## What Landed

Three qhorus issues, sixteen design decisions, three specs, three plans:

**#443** — Correction/retraction modeling. `correctsMessageId` and `retraction` fields on Message and MessageDispatch. Orthogonal to MessageType — no new enum values. Enforcement gate in dispatch validates same-channel, sender match, correction limits, SYSTEM guard. Six decisions (D1–D6).

**#444** — Message-scoped content erasure. Two-phase model: tombstone entry preserves chain integrity, then physical content null. Original digest stored in tombstone for pre-erasure integrity verification. Six decisions (D7–D12).

**#445** — Correction+commitment semantics. Corrections: no effect. Retractions: CANCELLED state, `cancel()` method, `CommitmentCancelledEvent`. Data-driven dispatch guard, correction-proof commitment switch, delegation-chain-aware cancellation. Four decisions (D13–D16).

The obligation envelope held. The commitment layer stayed content-blind. The message layer handles content, the commitment layer handles obligations, and corrections sit cleanly in the gap between them.
