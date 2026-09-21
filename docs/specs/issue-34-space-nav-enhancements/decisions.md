# Decisions — Chat App UX Overhaul

## D1: Emoji reaction UX model

**Choice:** Slack-style hover toolbar — show a floating toolbar (react, reply, more...) on message hover. Reaction pills only appear below a message when reactions exist. Zero-reaction messages show nothing.
**Alternatives:**
- Hide "+" until hover — keeps the reaction bar layout, just hides the add button until hover. Less change but still shows an empty bar area.
- Compact inline — smaller pills, tighter spacing. Doesn't solve the real estate problem.
**Rationale:** The current always-visible dashed "+" button wastes vertical space on every message. Slack proved that hover-to-react is learnable and saves significant real estate. Reaction pills still appear when reactions exist, so they remain discoverable.
**Trade-offs:** Touch devices can't hover — requires a fallback (see D8).
**Sources:** channel-reaction-bar.ts, channel-message.ts, channel-feed.ts
**Exploration:** quick
**Status:** captured

## D2: Hover toolbar actions

**Choice:** React + Reply + More (⋯ overflow menu) — smiley-face opens emoji picker, reply arrow sets reply-to, overflow menu contains edit, delete, pin, etc.
**Alternatives:**
- Four explicit buttons (react, reply, edit, thread) — more discoverable but takes more hover space
- React + Reply only — minimal, other actions via right-click/expand
**Rationale:** Three buttons is the sweet spot. The overflow menu scales to future actions without toolbar bloat. Matches established patterns (Slack, Discord, Teams).
**Trade-offs:** Edit and delete are two clicks away (hover + overflow). Acceptable given their lower frequency.
**Sources:** channel-message.ts (existing expand toggle and action bar)
**Exploration:** quick
**Status:** captured

## D3: Message corrections — append-only model, foundation-agnostic UX

**Choice:** Append-only corrections model. A correction replaces the displayed content of a prior message; a retraction withdraws it. Both are append-only records referencing the original via a dedicated `correctsMessageId` field (NOT `inReplyTo`, which would inflate replyCount and pollute reply threads). The original message is never mutated. The UI renders corrections inline — the original shows "(corrected)" with the latest content displayed, and an expandable history. Retractions render "[Retracted by <user> at <time>]" with the reason on expand.

**Foundation modeling is upstream.** Whether corrections are new MessageType values (CORRECTION/RETRACTION) or a separate `CorrectionRecord` concern orthogonal to the speech act taxonomy is a qhorus-api design decision. The chat-app spec defines the UX behavior; the backend model is filed as an upstream issue. Both approaches preserve ledger immutability and route through existing enforcement (ACL, rate limiting, fan-out).

**Authorization:** Original sender can correct their own messages. Moderator role can retract any message. No correction of SYSTEM-actorType messages. Correction-of-correction targets the original (not the prior correction) to prevent unbounded chains.

**Limits:** Max 10 corrections per message. Corrections subject to existing per-channel rate limiter. No time-window restriction (regulated domains may need post-hoc corrections to historical records).
**Alternatives:**
- In-place editing with version table — mutates content, breaks ledger immutability, creates second write path.
- Strikethrough + append — all versions always visible inline. High fidelity but noisy.
- CORRECTION/RETRACTION as MessageType additions — conflates meta-operations with normative speech acts (R1-02). The commitment service switch would need no-op cases. Viable but architecturally less clean.
**Rationale:** Preserves qhorus's immutable ledger integrity. Uses existing dispatch infrastructure. Legal compliance satisfied: original is never altered, full correction chain is retained. `correctsMessageId` avoids `inReplyTo` semantic overload (R1-03).
**Trade-offs:** Corrections create additional records in the stream — the UI must collapse them. More complex rendering than in-place edits. Foundation dependency for backend modeling.
**Sources:** QhorusMessage type, MessageService.dispatch(), MessageLedgerEntry, channel-feed.ts, R1-01, R1-02, R1-03, R1-05, R1-11
**Exploration:** quick
**Status:** revised (R2 — decouple UX from backend model, dedicated correctsMessageId, authorization, limits)

## D4: Message withdrawal via retraction

**Choice:** Withdrawal uses the correction model (D3). A retraction record references the original via `correctsMessageId`, carries an optional reason, and renders as "[Retracted by <user> at <time>]" replacing the original's content visually. The original is preserved immutably in the ledger.

**GDPR separation:** User-facing retraction (normative — "I withdraw what I said") is distinct from compliance-driven erasure (legal — "erase all data about actor X"). The existing `LedgerErasureService.erase(rawActorId, reason)` is actor-scoped, not message-scoped — it erases ALL ledger entries for an actor. There is no message-scoped erasure API. If message-level content erasure is needed (retract + purge one message's content from the ledger), that's a foundation gap to address upstream.
**Alternatives:**
- Soft delete with deletedAt/deletedBy fields — requires message mutation, bypasses ledger integrity.
- Configurable per channel — adds policy complexity without architectural benefit.
**Rationale:** Separates user-facing withdrawal from compliance erasure. Retractions are transparent by design — in a normative platform, withdrawals should be visible.
**Trade-offs:** Users cannot silently remove messages. LedgerErasureService gap for message-scoped erasure acknowledged.
**Depends on:** D3 (correction/retraction model)
**Sources:** LedgerErasureService, ErasureReason.GDPR_ART_17_REQUEST, R1-04, R1-07
**Exploration:** quick
**Status:** revised (R2 — acknowledge actor-scoped erasure gap)

## D5: Hover toolbar component location

**Choice:** Build in blocks-ui-channel-activity. Toolbar wraps `<blocks-channel-message>` in the feed. New events: CORRECT_MESSAGE and RETRACT_MESSAGE added to ChannelEventTopics (matching the no-mutation model, not EDIT/DELETE). Chat-app wires them to REST via `_onChatEvent`. Alternatively, corrections may route through the existing SEND_MESSAGE event path with a speechAct discriminator — this depends on the foundation modeling decision (D3).

**Reaction bar:** Make the "+" add-reaction button prop-controlled (`showAddButton`, default `true`). Chat-app sets `false` and uses the hover toolbar for reaction initiation. Other blocks-ui consumers retain the current behavior.
**Alternatives:**
- Build in chat-app — wrong architectural boundary; breaks when other apps use blocks-ui.
**Rationale:** blocks-ui owns message rendering and interaction. The `pages-event` pattern already separates rendering from handling.
**Trade-offs:** Cross-repo work required. Reaction bar API change is backwards-compatible (new prop with existing default).
**Sources:** events.ts (ChannelEventTopics), channel-feed.ts, channel-message.ts, qhorus-workbench.ts (_onChatEvent), R1-07, R1-13
**Exploration:** quick
**Status:** revised (R2 — event naming, reaction bar prop)

## D6: No separate versioning — corrections are records in the channel stream

**Choice:** No `message_versions` table, no `editedAt` field, no mutation path. Correction/retraction records are dispatched through `MessageService.dispatch()` and linked to the original via `correctsMessageId`. The append-only stream IS the version history.

**Projection strategy:** `ChannelProjection<S>` is the architecturally correct mechanism for building a corrected view — it's a pure left-fold SPI designed for exactly this pattern. A correction projection would fold a `Map<MessageId, CorrectedView>` updating each original's display content as corrections arrive. Initial implementation may do the collapsing client-side in `channel-feed.ts` for simplicity, but the spec acknowledges `ChannelProjection<S>` as the long-term path via `ProjectionService`.
**Alternatives:**
- Version array on message entity — adds mutation path, bypasses ledger integrity.
- Event-sourced log — premature; ChannelProjection<S> can produce versioned views from the existing stream when ready.
**Rationale:** The append-only stream is the version history. No separate storage needed. `correctsMessageId` provides version lineage without polluting reply chains.
**Trade-offs:** Client-side collapsing is a temporary duplication of what should be a server-side projection. Acceptable for initial implementation; the projection SPI path is documented.
**Depends on:** D3 (correction/retraction model)
**Sources:** MessageService.dispatch(), ChannelProjection<S>, ProjectionService, channel-feed.ts, R1-08, R1-09
**Exploration:** quick
**Status:** revised (R2 — acknowledge ChannelProjection as correct mechanism)

## D7: No separate delete model — RETRACTION is the delete

**Choice:** RETRACTION (from D3/D4) replaces the need for a separate delete model. There is no `deletedAt`/`deletedBy` field on the message entity. A retracted message's original content remains immutable in the ledger. The UI renders the retraction inline. GDPR erasure (actual content removal) is handled by the existing `LedgerErasureService`, not by the chat UI.
**Alternatives:**
- Dedicated deletedAt/deletedBy fields — requires message mutation and a second write path.
- Delete as version entry — conflates edit and delete semantics.
**Rationale:** Retraction IS the delete in a normative communication platform. Separating "user withdrawal" from "compliance erasure" is architecturally correct and avoids building mutation infrastructure.
**Trade-offs:** Users cannot silently remove messages from the feed — retractions are visible. Acceptable: normative transparency is a feature.
**Depends on:** D4 (RETRACTION speech act)
**Sources:** LedgerErasureService, MessageService.dispatch()
**Exploration:** quick
**Status:** revised (R1-07 — GDPR tension with soft delete model)

## D8: Phone layout message actions

**Choice:** Long-press context menu. Long-press on a message shows a context menu with React, Reply, Edit, Delete. Reaction pills still show below messages that have reactions.
**Alternatives:**
- Swipe-to-reveal actions — more native-feeling but harder to implement in web
- Tap to select + bottom toolbar — adds a mode to manage
**Rationale:** Long-press is the established mobile pattern for contextual actions in messaging apps (iMessage, WhatsApp, Telegram). Web implementation is straightforward with touch event handling.
**Trade-offs:** Long-press is not as discoverable as swipe. Acceptable — it's a learned pattern.
**Depends on:** D1 (hover toolbar is desktop-only, needs mobile fallback)
**Sources:** swipe-controller.ts (existing touch handling)
**Exploration:** quick
**Status:** captured

## D9: Dock strip redesign

**Choice:** Replace emoji icons with SVG icons, reduce to 40px, use `<pages-theme-picker compact>` for theme switching, move identity widget from nav panel to dock strip bottom.
**Alternatives:**
- Collapse dock into nav header — saves 48px but nav panel gets more complex
- Keep dock, polish only — minimal change but misses the opportunity to integrate theme picker
**Rationale:** SVG icons are crisper and more professional than emoji. The pages-ui-tokens theme picker already exists and supports compact mode with popover — no need to maintain a custom toggle. Identity widget at dock bottom matches Slack's user avatar pattern.
**Trade-offs:** Moving identity widget changes the nav panel layout. The nav panel gets simpler (loses the identity widget), dock strip gets slightly more complex.
**Sources:** pages-ui-tokens/theme-picker.ts, qhorus-workbench.ts (dock strip, identity rendering)
**Exploration:** quick
**Status:** captured

## D10: Contextual expand toggle — only on messages with metadata

**Choice:** Keep the expand toggle but make it contextual: only show ▶ on messages that have expandable metadata (artefact refs, commitment state, correlation context, correction chain). Messages with nothing to expand show no toggle. Reply moves to the hover toolbar. The expand section no longer contains an action bar — it's metadata-only (commitment details, artefact details, topic, correction history).
**Alternatives:**
- Remove expand entirely, use overflow menu — buries commitment details (three interactions to reach). Unacceptable for normative platform where commitment state is operationally required.
- Keep expand on all messages — current behavior. Adds visual noise to simple messages.
**Rationale:** Commitment state, artefact details, and correction history are per-message contextual data that users need at the point of the message. The task panel shows aggregate data; the correlation panel shows chains. Neither replaces the per-message view. Contextual visibility means clean messages show no chrome, while rich messages surface their metadata affordance.
**Trade-offs:** The feed has a visual mix of messages with and without toggles. Acceptable — it communicates information density at a glance.
**Depends on:** D2 (hover toolbar actions handle reply/react/more)
**Sources:** channel-message.ts (expanded section rendering), R1-12 (commitment details accessibility)
**Exploration:** quick
**Status:** revised (R1-12 — commitment details buried too deep in overflow)

## D11: Compact message header — colored border + actor icon + hover tooltip

**Choice:** Show [actor-icon] [sender] [timestamp] in the header. Speech act indicated by a 3px colored left border. Hovering over the border shows a tooltip with the full speech act name. No abbreviations — the colored border communicates the category, and within-category distinction is rarely needed at a glance (the message content itself disambiguates queries from responses). Actor icon (👤/🤖/⚙) retained — it's the only visual indicator of actor type since `sender` is a plain string.

**Border colors:** Blue=info (QUERY, RESPONSE, STATUS), Purple=obligation (COMMAND), Green=success (DONE), Red=danger (FAILURE), Yellow=warning (DECLINE), Teal=transfer (HANDOFF), Gray=telemetry (EVENT).

**Pre-existing TypeScript sync gap:** PROPOSE and JUDGMENT are in the Java MessageType enum but missing from TypeScript MESSAGE_TYPES and messageTypeCategory(). This should be resolved before adding any new types.
**Alternatives:**
- Color border + 2-char abbreviation — preserves within-category distinction but adds cognitive overhead for a novel, arbitrary mapping (R1-12). The abbreviations aren't a standard convention.
- Full text badges — current design. Takes too much horizontal space.
- Remove actor icon — false premise that sender name encodes actor type. The `actorType` field is a separate discriminator.
**Rationale:** The colored border is visible at a glance for scanning. The hover tooltip provides exact type when needed. Dropping abbreviations reduces cognitive load without losing information — it's available on hover. Actor icon retained because human-agent distinction is architecturally significant.
**Trade-offs:** Within-category types (QUERY vs RESPONSE vs STATUS) not distinguishable without hover. Message content disambiguates in practice.
**Sources:** channel-message.ts, messageTypeCategory(), types.ts (ActorType), R1-12, R1-09
**Exploration:** quick
**Status:** revised (R2 — drop abbreviations per R1-12, note TYPE sync gap per R1-09)

## D12: Tablet layout — no dock strip

**Choice:** Remove dock strip on tablet. Sidebar tab switcher handles all panel switching. Theme picker moves into sidebar header.
**Alternatives:**
- Keep both dock + tabs — redundant controls
- Bottom nav bar — iOS/Android style, but conflicts with web browser chrome
**Rationale:** On tablet, the sidebar tabs already provide panel switching. The dock strip is redundant and wastes 40px of horizontal space that matters more on smaller screens.
**Trade-offs:** Tablet and desktop have different panel switching paradigms. Acceptable — responsive layouts already adapt per breakpoint.
**Depends on:** D9 (dock strip redesign — tablet hides it entirely)
**Sources:** qhorus-workbench.ts (tablet rendering)
**Exploration:** quick
**Status:** captured

## D13: Channel input — add send button only

**Choice:** Add visible send button (arrow icon) on the right of textarea. No attachment button — defer until artefact reference insertion or file upload is actually implemented. No rich text formatting. Markdown-in-plain-text is sufficient.
**Alternatives:**
- Send + attachment button — attachment button with no functionality is poor UX; users who discover it are frustrated.
- Full formatting toolbar — bold/italic/code buttons. Heavier, not needed yet.
- Keep minimal — Enter to send, no buttons. Power-user focused but poor discoverability.
**Rationale:** A visible send button improves discoverability for new users and aligns with every modern messaging app. The attachment button is deferred: its absence costs nothing today; its inert presence would cost user trust.
**Trade-offs:** No visible hook for future file/artefact features. The button can be added when those features land.
**Sources:** channel-input.ts, R1-15 (attachment button is premature)
**Exploration:** quick
**Status:** revised (R1-15 — inert placeholder button)

## D14: Theme designer access

**Choice:** Settings panel accessible from dock strip gear icon. Full `PagesThemeDesignerElement` opens in a right-side panel (same slot as members/correlation). Compact picker in dock handles quick light/dark switching; designer is for deep customization.
**Alternatives:**
- Separate route/page — overkill for a chat workbench
- Modal overlay — blocks the chat feed, bad for "design while chatting" workflow
**Rationale:** The right-panel slot already exists and supports multiple panel types. A gear icon in the dock strip is the universal affordance for settings. Users can customize themes while seeing the chat feed update in real time.
**Trade-offs:** Uses a panel slot that could display other content. Acceptable — theme design is infrequent and the panel is dismissable.
**Depends on:** D9 (dock strip redesign — gear icon lives there)
**Sources:** pages-ui-tokens/theme-designer.ts, qhorus-workbench.ts (panel rendering)
**Exploration:** quick
**Status:** captured

## D15: Consume latest pages/blocks-ui

**Choice:** Audit and align to current SNAPSHOT versions. Verify chat-app uses density tokens (compact/normal/spacious), all semantic colour scales, and any new components added since initial integration. One-time alignment task scoped into the implementation plan.
**Alternatives:**
- No audit — risk using stale components when newer ones exist upstream
**Rationale:** Pages and blocks-ui have evolved since initial integration. Density tokens enable compact/spacious modes. Semantic colour scales ensure the theme designer works correctly across the UI.
**Trade-offs:** Alignment work may surface breaking changes if upstream APIs changed. Manageable during implementation.
**Sources:** package.json, pages-ui-tokens, pages-ui-components, blocks-ui-channel-activity
**Exploration:** quick
**Status:** captured

## D16: Resizable panels via PointerEvent drag handles

**Choice:** PointerEvent-based drag handles between panels. A thin `<div>` (4px, `cursor: col-resize`) between nav/main and main/members captures `pointerdown`, tracks `pointermove`, applies width on `pointerup`. Min/max constraints (nav: 180–360px, members: 180–300px). Persist widths in localStorage. Collapse below minimum to fully hide.
**Alternatives:**
- CSS `resize` — wrong interaction model: corner grippy not edge-dragging, no events for persistence, clips overflow (emoji picker, context menus), shadow DOM inconsistencies (R1-06).
- Fixed widths, toggle only — simpler but less flexible.
**Rationale:** PointerEvent drag handles provide full-edge dragging (~30 lines), fire events for localStorage persistence, don't require overflow constraints, and work consistently in shadow DOM. Every professional chat app (Slack, Discord, VS Code, Teams) uses custom drag handles, not CSS resize.
**Trade-offs:** Custom code instead of native CSS. The code is minimal and the UX is significantly better.
**Sources:** qhorus-workbench.ts (panel layout), R1-06
**Exploration:** quick
**Status:** revised (R2 — CSS resize unsuitable per R1-06)
