---
entry_type: note
subtype: diary
title: "The Correction That Corrected the Design"
date: 2026-09-16
author: mdp
tags: [ux, architecture, ledger-immutability, corrections, chat-app, lit, web-components]
status: draft
projects: [casehubio/chat-app]
series: issue-42-ux-overhaul
---

# The Correction That Corrected the Design

I wanted to clean up the chat UI — emoji reactions taking permanent space on every message, no hover toolbar, a raw sun/moon emoji for theme switching when pages already ships a proper theme picker. The kind of session where you audit everything, fix what's easy, and file issues for what's hard.

The audit surfaced the expected stuff. Every message carried a dashed-circle "+" button for reactions (28x24px of dead weight), an always-visible expand toggle for metadata nobody checks, and uppercase speech act badges screaming COMMAND and RESPONSE across the feed. Meanwhile, pages had a full `<pages-theme-picker compact>` component sitting unused.

Then someone asked about message editing.

## The Ledger Problem

I'd been thinking about editing the way Slack does it — in-place mutation, an `(edited)` marker, version history in a separate table. A `PUT /messages/{id}` endpoint, an `editedAt` field, a `message_versions` table. Straightforward.

Claude caught the problem during decision review: qhorus channels are append-only ledgers. Every message is a `MessageLedgerEntry` in a Merkle-tree chain. Mutating a message's content would break the chain hash — and worse, it would create a second write path that bypasses `MessageService.dispatch()`, meaning ACL checks, rate limiting, and fan-out all get reimplemented or skipped.

The fix was to stop thinking about it as "editing" and start thinking about it as "correction." Financial messaging systems (SWIFT MT/MX) and medical records don't edit entries. They post corrections — new records that reference the original and replace what's displayed. The original is never touched.

So CORRECTION and RETRACTION became append-only records in the channel stream, each carrying a `correctsMessageId` field linking back to the original. The feed collapses them visually — the original shows "(corrected)" with the latest content, and expanding reveals the correction chain. Retractions show `[Retracted by X at Y]`. The original message sits untouched in the ledger, chain hashes intact, legal audit trail satisfied.

One detail that nearly slipped through: I'd planned to use `inReplyTo` for the correction linkage. Claude pointed out this would inflate `replyCount` on every corrected message and pollute reply threads with correction records. A dedicated `correctsMessageId` field avoids both problems — corrections never appear as replies.

## The Real Estate Sweep

With the architectural question settled, the rest was a real estate cleanup. Speech act badges became a 3px colored left border (blue for info, purple for obligation, green for success) with the type name on hover tooltip. The expand toggle became contextual — only visible on messages that actually have metadata to show (artefact refs, commitment state, correlation context). The reaction bar lost its always-visible "+" button entirely; reactions initiate from a Slack-style hover toolbar.

The dock strip got the most visible change. Emoji icons (💬👥📋🔗📎) replaced with inline SVG Lit templates, width dropped from 48px to 40px, and the emoji theme toggle replaced with `<pages-theme-picker compact>`. The identity widget moved from the nav panel header to the dock strip bottom — compact mode showing just the avatar. A settings panel behind a gear icon provides a density toggle (`.pages-density-compact` CSS class on the workbench host, which overrides all `--pages-space-*` tokens automatically).

One thing I learned the hard way about panel resizing: CSS `resize` looks like the native, zero-dependency answer. It's not. The resize handle is a corner grippy — not an edge drag handle. It provides no JavaScript events for persistence. It requires `overflow: hidden`, which clips every popover, tooltip, and context menu that needs to escape panel bounds. And it behaves inconsistently inside shadow DOM. PointerEvent-based drag handles are about 30 lines of code and work perfectly — `setPointerCapture` eliminates the need for global listeners, and pointer events work consistently in shadow roots.

## What's Ahead

The foundation modelling — whether corrections are new `MessageType` values or a separate `CorrectionRecord` concern — is an upstream qhorus-api decision. The chat-app spec is deliberately agnostic: it defines the UX and the `correctsMessageId` linkage, leaving the backend to choose the cleaner abstraction. The hover toolbar, compact headers, and correction UI all live in blocks-ui (now cloned into the slot), which is where the next session picks up.

The correction pivot was the session's real output. What started as "add an edit button" became a design that actually fits the platform's architecture — append-only, legally auditable, and requiring zero mutation infrastructure. Sometimes the constraint is the feature.
