# Chat App UX Overhaul — Design Spec

## 1. Overview

This spec defines a comprehensive UX overhaul for the chat-app workbench. The goals:

1. **Streamline message chrome** — replace always-visible reaction buttons and expand toggles with a hover toolbar, reducing per-message visual overhead to near zero for simple messages.
2. **Add corrections and retractions** — an append-only model preserving ledger immutability, with full audit trail for legal compliance.
3. **Integrate the pages theme system** — replace the custom dark/light emoji toggle with the `<pages-theme-picker>` compact popover, add a settings panel with density toggle and future theme designer placeholder.
4. **Optimize real estate** — resizable panels, compact message headers with colored speech-act borders, contextual expand toggles, and responsive dock strip removal on tablet.
5. **Align with upstream** — audit pages/blocks-ui SNAPSHOT versions, consume density tokens and semantic colour scales.

**Scope:** This is a UX overhaul spanning chat-app (workbench shell) and blocks-ui-channel-activity (message components). Foundation-level changes (qhorus-api modeling for corrections) are documented as upstream dependencies, not implemented here.

**Issue tracking:** This work is broader than the original issue #34 (space nav CRUD, now closed). A new UX overhaul epic should be created to track this scope, with child issues per section. (R1-10)

**Decisions:** 16 captured, 2 rounds of review (R1 adversarial, R2 revision). See `decisions.md` for the full record.

---

## 2. Message Interaction Model

### 2.1 Desktop: Hover Toolbar (D1, D2)

**UX:** When the user hovers over any message in the feed, a floating toolbar appears at the top-right corner of the message, offset slightly above the message boundary. The toolbar contains three icon buttons:

| Button | Icon | Action |
|--------|------|--------|
| React | 😊 (smiley) | Opens the emoji picker popover, anchored to the toolbar |
| Reply | ↩ (reply arrow) | Sets the message as reply-to in the channel input |
| More | ⋯ (ellipsis) | Opens an overflow menu |

**Overflow menu items:**
- Correct — opens inline correction UI (D3)
- Retract — opens retraction confirmation dialog (D4)
- View details — shows the metadata expand section (D10)
- Pin — toggles pin state (future)
- Copy text — copies message content to clipboard

The toolbar disappears when the mouse leaves both the message and the toolbar. A small hover gap tolerance prevents flickering when moving between message and toolbar.

**Overflow menu items are context-dependent:** "Correct" is hidden when `message.actorType === 'SYSTEM'` or `message.sender !== currentActorId`. "Retract" is hidden when the user is neither the message sender nor a moderator. (R1-17)

**Keyboard access:** Focus a message via roving tabindex (already in feed), press Enter to show the toolbar. Arrow keys navigate toolbar buttons. Escape dismisses. Tab order includes toolbar buttons when visible. Uses `RovingTabindexMixin` and `FocusTrap` from `@casehubio/pages-primitives/a11y`. (R1-08)

**Architecture:** The toolbar is a new Lit component in blocks-ui-channel-activity (`<blocks-channel-hover-toolbar>`). It is rendered by `<blocks-channel-feed>` as an absolutely-positioned overlay, not as a child of `<blocks-channel-message>`. This avoids disrupting message layout and allows the toolbar to float above message boundaries.

**Positioning:** Scroll handling: the toolbar repositions on scroll via a `scroll` listener on the feed container. Boundary handling: if the message is near the top, the toolbar renders below the message instead of above. Z-index: toolbar at z-index 50, emoji picker popover at z-index 100. (R1-16)

**Files affected:**
- `blocks-ui-channel-activity/src/channel-hover-toolbar.ts` — new component
- `blocks-ui-channel-activity/src/channel-feed.ts` — render toolbar overlay, track hovered message
- `blocks-ui-channel-activity/src/events.ts` — new event topics (see §5)

### 2.2 Reaction Bar Changes (D1, D5)

**UX:** The always-visible dashed "+" add-reaction button is removed. Reaction pills (emoji + count) still appear below messages that have reactions. Users initiate reactions via the hover toolbar's react button.

**Architecture:** The "+" add-reaction button is removed entirely from `<blocks-channel-reaction-bar>`. The reaction bar renders only existing reaction pills. All consumers use the hover toolbar for reaction initiation — there is no backwards-compatibility shim. This platform has no external consumers; the migration is coordinated across all apps. (R1-13)

**Files affected:**
- `blocks-ui-channel-activity/src/channel-reaction-bar.ts` — remove "+" button rendering and associated click handler

### 2.3 Contextual Expand Toggle (D10)

**UX:** The per-message ▶/▼ expand toggle is no longer shown on every message. It appears only on messages that have expandable metadata:
- Artefact references (non-empty `artefactRefs`)
- Commitment ID (non-null `message.commitmentId` — present on the message data object, unlike the `commitmentState` component property which is never set via the feed rendering path) (R1-07)
- Correlation context (non-null `correlationId`)
- Correction chain (message has been corrected — see §4)

Messages with none of these show no toggle — a clean, minimal layout.

The expanded section is metadata-only (no action bar):
- Topic and message type details
- Artefact chips with detail view
- Commitment state and lifecycle
- Correction history (if corrected)

Reply and react actions have moved to the hover toolbar; they are no longer in the expand section.

**Architecture:** `<blocks-channel-message>` introspects message data to determine whether expandable content exists. A computed property `_hasExpandableContent` checks the four conditions above. The expand toggle renders conditionally based on this property. (R1-17)

**Files affected:**
- `blocks-ui-channel-activity/src/channel-message.ts` — conditional expand toggle, remove action bar from expanded section

### 2.4 Phone: Long-Press Context Menu (D8)

**UX:** On phone layout (narrow viewport), hover is not available. Instead, a long-press (≥500ms) on any message triggers a context menu with:
- React — opens emoji picker
- Reply — sets reply-to
- Correct — opens correction UI (own messages only)
- Retract — opens retraction dialog (own messages, or moderator)

The context menu appears as a bottom sheet on phone, anchored above the touched message on tablet.

**Architecture:** Long-press detection is handled entirely in the app layer (alongside swipe in `swipe-controller.ts`), not in blocks-ui. The app dispatches a `pages-event` that blocks-ui components listen for. blocks-ui must not coordinate with or depend on app-layer controllers directly. (R1-11)

A `touchstart` → timer → `contextmenu` pattern, cancelled by `touchmove` (distance > 10px) or `touchend` before the threshold.

**Files affected:**
- `chat-app/src/workbench/swipe-controller.ts` — long-press detection, dispatches `pages-event` for context menu
- `blocks-ui-channel-activity/src/channel-feed.ts` — context menu rendering in response to the event

---

## 3. Compact Message Header (D11)

### 3.1 Header Layout

**Before:** `[👤] [Sender] [COMMAND] [commitment-badge] [3h] [▶]`
**After:** `[👤] [Sender] [3h]` with a 3px colored left border on the entire message

The speech act type badge (uppercase text like "COMMAND", "RESPONSE") is removed from the header. The actor icon (👤/🤖/⚙) is retained — it is the only visual indicator of actor type, since the sender name is a plain string with no structural encoding of whether the sender is human, agent, or system. (R1-13 confirmed actor icon removal was based on a false premise.)

### 3.2 Colored Left Border

Each message has a 3px left border colored by speech act category:

| Color | Category | Speech Acts |
|-------|----------|-------------|
| Blue (`--pages-color-info-600`) | Information | QUERY, RESPONSE, STATUS, JUDGMENT |
| Purple (`--pages-color-accent-600`) | Obligation | COMMAND, PROPOSE |
| Green (`--pages-color-success-600`) | Success | DONE |
| Red (`--pages-color-danger-600`) | Danger | FAILURE |
| Yellow (`--pages-color-warning-600`) | Warning | DECLINE |
| Teal (`--pages-color-info-400`) | Transfer | HANDOFF |
| Gray (`--pages-color-neutral-400`) | Telemetry | EVENT |

Colors use `--pages-*` semantic tokens to adapt to light/dark themes and custom theme families. (R1-09)

**Accessibility (WCAG 1.4.1):** Color is not the sole visual indicator. Each message container includes `aria-label` with the speech act type name for screen readers. The colored border is supplemented by the actor icon (shape-based, not color-dependent) and the hover tooltip, providing multiple non-color cues. On phone (no hover), speech act type is available via long-press → View details. (R1-14)

### 3.3 Hover Tooltip

Hovering over the colored border shows a tooltip with the full speech act name (e.g., "COMMAND", "RESPONSE"). This provides exact type information on demand without permanent visual cost.

Within-category types (QUERY vs RESPONSE vs STATUS) are not distinguishable without hover. In practice, message content disambiguates — a question is clearly a query, an answer clearly a response. The colored border communicates the operationally significant category (obligation, danger, success). (R1-12)

### 3.4 TypeScript Sync Gap

**Pre-existing issue:** The Java `MessageType` enum has 11 values (QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, PROPOSE, JUDGMENT, EVENT). The TypeScript `MESSAGE_TYPES` in blocks-ui has only 9 — missing PROPOSE and JUDGMENT. The `messageTypeCategory()` function doesn't handle them either. This sync gap should be resolved before adding any new types for corrections. (R1-09)

**Files affected:**
- `blocks-ui-channel-activity/src/channel-message.ts` — header rendering, border color, tooltip
- `blocks-ui-channel-activity/src/types.ts` — add PROPOSE, JUDGMENT to MESSAGE_TYPES
- `blocks-ui-channel-activity/src/styles.ts` or inline — `messageTypeCategory()` updates

---

## 4. Corrections and Retractions (D3, D4, D6, D7)

### 4.1 UX: Corrections

When a user selects "Correct" from the hover toolbar overflow menu:
1. The channel input transforms into a correction editor pre-filled with the original message content.
2. A banner above the input shows "Correcting message from [sender] at [time]" with a cancel button.
3. The user edits the content and submits.
4. The original message in the feed updates to show the corrected content with a "(corrected)" marker next to the timestamp.
5. If the message already had the ▶ expand toggle, the correction history appears in the expanded section. If not, the toggle now appears (the correction chain counts as expandable metadata per D10).

**Correction history (expanded):** A chronological list showing:
```
Original (2026-09-15 14:23): "The meeting is at 3pm"
Corrected (2026-09-15 14:25): "The meeting is at 4pm"
```

### 4.2 UX: Retractions

When a user selects "Retract" from the overflow menu:
1. A confirmation dialog appears: "Retract this message? This action is visible to all participants."
2. Optional: a reason text field.
3. On confirm, the message in the feed changes to: `[Retracted by <user> at <time>]` in muted text.
4. The expand toggle (if present) shows the retraction reason and original content.

Retractions are transparent by design — in a normative communication platform, withdrawals should be visible. Users cannot silently remove messages.

### 4.3 Authorization

| Action | Who can do it |
|--------|--------------|
| Correct own message | Original sender |
| Correct another's message | Not allowed |
| Retract own message | Original sender |
| Retract another's message | Moderator role only |
| Correct SYSTEM messages | Not allowed — UI hides "Correct" when `message.actorType === 'SYSTEM'` (R1-17) |
| Correct a correction | `correctsMessageId` must reference the original message ID, never a correction record's ID. Users CAN submit multiple corrections to the same original. (R1-21) |

**Wiring requirement:** The workbench must pass `currentActorId` (derived from `getIdentity()`) to the feed component. This property already exists on `<blocks-channel-feed>` but is not currently wired in `qhorus-workbench.ts`. Without it, the toolbar cannot determine message ownership and the client-side authorization model fails. (R1-06)

### 4.4 Limits

- **Max corrections per message:** 10 (configurable per deployment; regulated deployments may increase). After reaching the limit, the UI shows "Correction limit reached" and disables the correct action. (R1-22)
- **Rate limiting:** Corrections are subject to the existing per-channel rate limiter in `MessageService.dispatch()`.
- **No time-window restriction:** Regulated domains may need post-hoc corrections to historical records. The platform does not impose a correction deadline.
- **Offline handling:** If the user is disconnected (connection banner visible), the correct/retract actions are disabled in the hover toolbar and context menu. Corrections are not queued offline — the append-only model requires server-side dispatch for ledger integrity. The user sees a "Reconnecting..." state and can retry when connected. (R1-15)

### 4.5 Data Model

**No message mutation.** The original message is never altered. Corrections and retractions are append-only records in the channel stream, linked to the original via a dedicated `correctsMessageId` field. This field is distinct from `inReplyTo` — using `inReplyTo` would inflate `replyCount` and pollute reply threads. (R1-03)

**Foundation modeling is upstream.** Whether corrections are modeled as:
- New `MessageType` values (CORRECTION, RETRACTION) — simple but conflates meta-operations with normative speech acts (R1-02)
- A separate `CorrectionRecord` concern orthogonal to the speech act taxonomy — cleaner separation but requires new service infrastructure

...is a qhorus-api design decision. The chat-app spec defines the UX behavior and the `correctsMessageId` linkage field. The backend modeling is filed as an upstream issue against qhorus-api.

Both approaches preserve ledger immutability and route through existing enforcement infrastructure (ACL, rate limiting, ledger write, fan-out).

### 4.6 Projection Strategy

`ChannelProjection<S>` is the architecturally correct mechanism for building a corrected view from the append-only stream. A correction projection would maintain a `Map<MessageId, CorrectedView>` that folds each correction record into the display state for the original message.

**Initial implementation:** Client-side collapsing in `channel-feed.ts`. Build a `Map<string, QhorusMessage[]>` indexed by `correctsMessageId` once per render cycle (in `willUpdate`), enabling O(1) lookups during rendering. The feed renders the latest correction's content in place of the original. This is a temporary duplication of what should be a server-side projection. (R1-08, R1-15)

**Long-term path:** The `ProjectionService` infrastructure and `ChannelProjection<S>` SPI can produce the corrected view server-side, delivered via the push dataset. The client-side logic would then be simplified to rendering the already-collapsed view.

### 4.7 GDPR Separation

User-facing retraction (normative — "I withdraw what I said") is distinct from compliance-driven erasure (legal — "erase all data about actor X").

The existing `LedgerErasureService.erase(rawActorId, reason)` is **actor-scoped** — it erases ALL ledger entries for an actor. There is no message-scoped erasure API. If message-level content erasure is needed (retract a specific message AND purge its content from the ledger), that is a foundation gap to address upstream. (R1-04)

**Files affected:**
- `blocks-ui-channel-activity/src/channel-message.ts` — correction/retraction rendering, "(corrected)" marker, retraction tombstone
- `blocks-ui-channel-activity/src/channel-feed.ts` — correction collapsing logic, group corrections by `correctsMessageId`
- `blocks-ui-channel-activity/src/channel-input.ts` — correction editor mode with pre-fill and banner
- `blocks-ui-channel-activity/src/types.ts` — `correctsMessageId` field on message type
- `chat-app/src/workbench/qhorus-workbench.ts` — wire correction/retraction events to REST

---

## 5. Component Architecture (D5)

### 5.1 Hover Toolbar Location

The hover toolbar is built in **blocks-ui-channel-activity**, not in chat-app. blocks-ui owns message rendering and interaction. The `pages-event` pattern separates rendering from event handling — blocks-ui fires events, chat-app handles them.

### 5.2 New Event Topics

New events added to `ChannelEventTopics` in blocks-ui:

| Event | Payload | Fired when |
|-------|---------|------------|
| `CORRECT_MESSAGE` | `{ messageId, correctedContent }` | User submits a correction |
| `RETRACT_MESSAGE` | `{ messageId, reason? }` | User confirms a retraction |

Event names match the no-mutation model — `CORRECT_MESSAGE` and `RETRACT_MESSAGE`, not `EDIT_MESSAGE` and `DELETE_MESSAGE`. Dedicated events are the committed design: corrections are conceptually distinct from sending a new message, and the event topology should reflect that separation. (R1-07, R1-12)

### 5.3 Chat-App Wiring

Chat-app's `_onChatEvent` handler in `qhorus-workbench.ts` wires the new events to REST endpoints:

```
CORRECT_MESSAGE → POST /api/chat/{channelId}/messages (with correctsMessageId)
RETRACT_MESSAGE → POST /api/chat/{channelId}/messages (with correctsMessageId, retraction flag)
```

Both route through the existing message creation endpoint — corrections and retractions are new messages, not mutations of existing ones.

**Files affected:**
- `blocks-ui-channel-activity/src/events.ts` — add CORRECT_MESSAGE, RETRACT_MESSAGE topics
- `chat-app/src/workbench/qhorus-workbench.ts` — handle new events in `_onChatEvent`, wire `currentActorId` to feed (R1-06)

---

## 6. Dock Strip Redesign (D9)

### 6.1 UX

**Before:** 48px dock strip with emoji icons (💬👥📋🔗📎), emoji sun/moon theme toggle, no identity widget.
**After:** 40px dock strip with SVG icons, `<pages-theme-picker compact>` popover, identity widget at bottom. Width reduced from 48px to 40px — SVG icons render crisply at 20px (vs emoji at ~24px), allowing tighter spacing. (R1-20)

**Layout (top to bottom):**
1. Channel nav toggle (SVG chat bubble icon)
2. Members panel toggle (SVG people icon)
3. Tasks panel toggle (SVG checklist icon)
4. Correlation panel toggle (SVG link icon)
5. Artifacts panel toggle (SVG paperclip icon)
6. Settings (SVG gear icon) — opens settings panel with density toggle and future extensibility (D14)
7. *spacer*
8. `<pages-theme-picker compact>` — popover with theme family selector + light/dark toggle
9. Identity widget — user avatar or initials, click for profile/logout

### 6.2 Architecture

- SVG icons are inline Lit templates, not external files — no HTTP requests, themeable via CSS `fill`/`stroke`.
- `<pages-theme-picker compact>` is consumed directly from `@casehubio/pages-ui-tokens`. The existing `_darkMode` boolean toggle in the workbench is replaced by listening to the theme picker's `theme-change` event.
- Identity widget is moved from `_renderNav()` in `qhorus-workbench.ts` (where it is rendered ABOVE `<blocks-channel-nav>`, not inside the nav component) to the dock strip bottom. This is a workbench layout change — move one render call from `_renderNav()` to `_renderDockStrip()`. On tablet/phone where the dock strip is hidden, the identity widget renders in the sidebar header area instead. (R1-10)

**Files affected:**
- `chat-app/src/workbench/qhorus-workbench.ts` — dock strip rendering, SVG icons, theme picker integration, identity widget relocation
- `chat-app/src/identity-widget.ts` — adjust styling for dock strip context (vertical, compact)

---

## 7. Theme Integration (D9, D14)

### 7.1 Quick Theme Switching

The `<pages-theme-picker compact>` in the dock strip provides:
- Theme family selector (e.g., casehub-light, casehub-dark, custom families)
- Light/dark mode toggle
- Popover UI that doesn't obscure the chat feed

This replaces the current emoji sun/moon toggle button and the `_darkMode` boolean state.

### 7.2 Settings Panel

A gear icon in the dock strip opens a settings panel in the right-side panel slot (same slot as members, correlation, artifacts panels). The settings panel provides:
- **Density toggle** — compact on/off (see §7.3)
- **Placeholder section** for a future theme designer

**Note:** The `<pages-theme-designer>` component does NOT exist. A full OKLCH colour pipeline designer (shape, density, typography controls, preset gallery, save/load/export) would need to be built from scratch — likely hundreds of lines of UI. This is out of scope for this UX overhaul. File as an upstream issue against `casehubio/pages` for future implementation. (R1-03)

### 7.3 Density

Density is controlled via the CSS class `.pages-density-compact`, which overrides existing `--pages-space-*` and `--pages-font-*` tokens when applied to a container element. Only compact exists — there are no `normal` or `spacious` variants. There are no `--pages-density-*` custom properties. (R1-04)

**Consumption model:** Apply `.pages-density-compact` to the workbench host element (`<qhorus-workbench>`). All child components automatically get tighter spacing via the overridden space/font tokens. No per-component style changes needed. The density toggle in the settings panel (§7.2) adds/removes this class.

**Files affected:**
- `chat-app/src/workbench/qhorus-workbench.ts` — settings panel rendering, gear icon handler, density class toggle on host element

---

## 8. Channel Input (D13)

### 8.1 UX

**Before:** Textarea only. Enter to send. No visual send affordance.
**After:** Textarea with a visible send button (arrow icon) on the right side. The send button is:
- Disabled (grayed) when the textarea is empty
- Enabled (accent color) when text is present
- Click or Enter both send the message

No attachment button, no formatting toolbar. Markdown-in-plain-text is sufficient for the current stage. The attachment button is deferred until artefact reference insertion or file upload is implemented — an inert button would frustrate users who discover it. (R1-15)

### 8.2 Correction Editor Mode

When correcting a message (D3), the input transforms:
- Pre-filled with the original message content
- A banner above: "Correcting message from [sender] at [time]" with a ✕ cancel button
- The send button label changes to a checkmark icon (confirm correction)
- Escape or ✕ cancels the correction and restores normal input mode

**Files affected:**
- `blocks-ui-channel-activity/src/channel-input.ts` — send button, correction editor mode, banner

---

## 9. Resizable Panels (D16)

### 9.1 UX

Panels (nav and members) can be resized by dragging the edge between the panel and the main chat area. A thin vertical line (4px) between panels changes cursor to `col-resize` on hover. Dragging adjusts the panel width in real time.

**Constraints:**
- Nav panel: 180px min, 360px max
- Members panel: 180px min, 300px max
- Dragging below minimum collapses the panel entirely (hidden)
- Double-click on the drag handle resets to default width

Panel widths are persisted via the existing `createLocalLayoutStore('qhorus-workbench:')` store, alongside current panel open/close state. (R1-23)

### 9.2 Architecture

A `PointerEvent`-based drag handle — not CSS `resize`. CSS `resize` uses a corner grippy (wrong interaction model), provides no callbacks for persistence, requires `overflow: hidden` (clips popovers and context menus), and behaves inconsistently in shadow DOM. (R1-06)

The drag handle is a thin `<div>` rendered between panels:
1. `pointerdown` — capture pointer, record initial width and pointer X
2. `pointermove` — calculate delta, apply new width (clamped to min/max)
3. `pointerup` — release capture, persist width to `createLocalLayoutStore`

~30 lines of code. Works consistently in shadow DOM and doesn't constrain `overflow`.

**Migration note:** The issue-28 workbench layout migration spec proposes migrating all layout to pages-runtime primitives (`dockWorkbench()`, `split()`, `dockBar()`). These PointerEvent drag handles are an interim solution. When pages-runtime primitives support resize, these handles should be migrated to the runtime's split panel API. (R1-02)

**Files affected:**
- `chat-app/src/workbench/qhorus-workbench.ts` — drag handle rendering, pointer event handlers, width persistence via `createLocalLayoutStore`

---

## 10. Tablet Layout (D12)

### 10.1 UX

On tablet viewport (768px–1279px), the dock strip is removed entirely. The sidebar already has a tab switcher that handles all panel navigation. Showing both the dock strip and sidebar tabs is redundant.

**What moves:**
- Theme picker → sidebar header area
- Identity widget → sidebar header area (next to theme picker)
- Panel switching → sidebar tabs (already implemented)

### 10.2 Breakpoint Behavior

| Viewport | Dock strip | Panel switching | Theme picker |
|----------|-----------|----------------|-------------|
| Desktop (≥1280px) | Visible, 40px | Dock strip icons | Dock strip |
| Tablet (768–1279px) | Hidden | Sidebar tabs | Sidebar header |
| Phone (<768px) | Hidden | Swipe drawers | Sidebar header (when open) |

Breakpoints match `responsive.ts`: `MQ_DESKTOP = '(min-width: 1280px)'`, `MQ_TABLET = '(min-width: 768px) and (max-width: 1279px)'`. (R1-05)

**Files affected:**
- `chat-app/src/workbench/qhorus-workbench.ts` — conditional dock rendering per breakpoint
- `chat-app/src/workbench/responsive.ts` — breakpoint constants (if not already defined)

---

## 11. Dependency Audit (D15)

### 11.1 Scope

A one-time alignment task during implementation:

1. **Verify SNAPSHOT versions** — ensure chat-app's `.casehub-packages/` extractions are current with the latest `mvn install` from pages and blocks-ui.
2. **Density** — verify `.pages-density-compact` class is available and correctly overrides `--pages-space-*` and `--pages-font-*` tokens. Only compact exists (no normal/spacious). (R1-04)
3. **Semantic colour scales** — verify all `--pages-color-{accent,neutral,success,warning,danger,info}-{50..950}` tokens are available for border colors and theme designer.
4. **New components** — check for components added to pages-ui-components since initial integration that could replace custom implementations.
5. **a11y primitives** — verify `FocusTrap`, `RovingTabindex`, `LiveRegion`, `KeyboardShortcut` mixins from `pages-primitives` are used where applicable (hover toolbar keyboard navigation, context menus).

### 11.2 Expected Outcomes

- Updated `package.json` references if versions have drifted
- New `--pages-*` token usage throughout component styles
- Possible replacement of custom tooltip/popover code with pages primitives

---

## 12. Foundation Dependencies

These items require upstream issues against foundation repos. They are not implemented in chat-app.

### 12.1 qhorus-api: Correction/Retraction Modeling (R1-01, R1-02)

**Issue needed against:** `casehubio/qhorus` (qhorus-api module)

Design a backend model for message corrections and retractions that preserves ledger immutability. Two candidate approaches:
- **New MessageType values** — CORRECTION, RETRACTION added to the enum. Simple but conflates meta-operations with normative speech acts. Commitment service switch needs no-op cases.
- **Separate CorrectionRecord concern** — orthogonal to MessageType. Own service method reusing ACL, rate limiting, and ledger infrastructure. Cleaner separation.

Both require:
- `correctsMessageId` field on the message/record entity
- Validation: must reference an existing message in the same channel
- Authorization enforcement (sender-only for corrections, moderator for retractions)
- Rate limiting integration
- Push dataset support (fan-out of correction records to connected clients)

### 12.2 blocks-ui: TypeScript MessageType Sync (R1-09)

**Issue needed against:** `casehubio/blocks-ui`

Add PROPOSE and JUDGMENT to TypeScript `MESSAGE_TYPES` and `messageTypeCategory()` to match the Java `MessageType` enum. Pre-existing gap that should be resolved before any new types are added.

### 12.3 qhorus-api: Message-Scoped Erasure (R1-04)

**Issue needed against:** `casehubio/qhorus` (qhorus-api module)

The existing `LedgerErasureService.erase(rawActorId, reason)` is actor-scoped. If message-level content erasure is needed (e.g., retract + purge one message's content from the ledger without erasing all of the actor's data), a message-scoped erasure API is required. This is a gap for GDPR Art.17 scenarios where a user wants specific content removed, not their entire account erased.

### 12.4 Correction Visibility and Commitment Integrity (R1-16)

**Issue needed against:** `casehubio/qhorus` (qhorus-api module)

When a COMMAND message is corrected after it has been acknowledged or fulfilled, the commitment lifecycle ran against the original content. The correction changes the displayed content but doesn't invalidate the commitment. The platform should define semantics for corrections to commitment-bearing messages — does a correction to a COMMAND require re-acknowledgment?

---

## 13. References

### Source Files

| File | What it informs |
|------|----------------|
| `blocks-ui-channel-activity/src/channel-message.ts` | Message rendering, expand toggle, speech act badges |
| `blocks-ui-channel-activity/src/channel-feed.ts` | Feed rendering, message grouping, scroll handling |
| `blocks-ui-channel-activity/src/channel-reaction-bar.ts` | Reaction pills, "+" button, emoji picker |
| `blocks-ui-channel-activity/src/channel-input.ts` | Message input, topic selector |
| `blocks-ui-channel-activity/src/events.ts` | ChannelEventTopics (REACT, UNREACT, MESSAGE_SELECTED, etc.) |
| `blocks-ui-channel-activity/src/types.ts` | QhorusMessage type, MESSAGE_TYPES, ActorType |
| `chat-app/src/workbench/qhorus-workbench.ts` | App shell, dock strip, panel layout, event wiring |
| `chat-app/src/workbench/swipe-controller.ts` | Touch gesture handling |
| `chat-app/src/identity-widget.ts` | User identity display |
| `pages-ui-tokens/theme-picker.ts` | Theme picker component (compact popover variant) |
| `chat-app/src/workbench/responsive.ts` | Breakpoint media query constants |

### Review Findings

| ID | Finding | Resolution |
|----|---------|------------|
| R1-01 | Foundation-level changes not owned by chat-app | §12.1 — upstream issue |
| R1-02 | Corrections are meta-operations, not speech acts | §4.5 — foundation-agnostic UX |
| R1-03 | `inReplyTo` semantic overload | §4.5 — dedicated `correctsMessageId` |
| R1-04 | LedgerErasureService is actor-scoped | §4.7, §12.3 — gap acknowledged |
| R1-05 | No authorization model | §4.3 — authorization rules defined |
| R1-06 | CSS `resize` unsuitable | §9.2 — PointerEvent drag handles |
| R1-07 | Event names contradict no-mutation model | §5.2 — CORRECT/RETRACT events |
| R1-08 | ChannelProjection is correct mechanism | §4.6 — projection strategy documented |
| R1-09 | TypeScript MESSAGE_TYPES sync gap | §3.4, §12.2 — upstream issue |
| R1-10 | Scope creep (issue 34 vs UX overhaul) | §1 — new scope, separate epic needed |
| R1-11 | No correction depth/rate limits | §4.4 — limits defined |
| R1-12 | Abbreviations add cognitive overhead | §3.3 — dropped, hover tooltip only |
| R1-13 | Reaction bar breaking change | §2.2 — "+" button removed entirely |
| R1-15 | Offline handling for corrections | §4.4 — disabled when disconnected |
| R1-16 | Correction visibility to non-present participants | §12.4 — upstream issue |

**Spec review findings (post-spec):**

| ID | Finding | Resolution |
|----|---------|------------|
| SR1-02 | Resizable panels contradict issue-28 spec | §9.2 — interim solution, migration note added |
| SR1-03 | `<pages-theme-designer>` does not exist | §7.2 — descoped, settings panel with density toggle |
| SR1-04 | Density tokens mischaracterized | §7.3 — corrected: CSS class, not custom properties |
| SR1-05 | Breakpoint values contradict responsive.ts | §10.2 — corrected to 1280px desktop threshold |
| SR1-06 | `currentActorId` never wired | §4.3, §5.3 — wiring requirement documented |
| SR1-07 | `commitmentState` not passed through feed | §2.3 — use `message.commitmentId` instead |
| SR1-08 | Hover toolbar keyboard accessibility | §2.1 — keyboard interaction model specified |
| SR1-09 | PROPOSE/JUDGMENT missing from color mapping | §3.2 — added to table |
| SR1-10 | Identity widget location mischaracterized | §6.2 — corrected: rendered by workbench, not nav |
| SR1-11 | Long-press crosses layer boundary | §2.4 — handled entirely in app layer |
| SR1-12 | Event naming not committed | §5.2 — committed to CORRECT/RETRACT events |
| SR1-13 | `showAddButton` unnecessary shim | §2.2 — "+" button removed entirely |
| SR1-14 | Color-only indication violates WCAG 1.4.1 | §3.2 — aria-label + multi-cue design |
| SR1-15 | Correction collapsing needs data structure | §4.6 — Map specification added |
| SR1-16 | Hover toolbar position tracking | §2.1 — scroll, boundary, z-index specified |
| SR1-17 | Correction of SYSTEM messages UI gap | §4.3, §2.1 — conditional rendering specified |
| SR1-20 | Dock strip width change unmotivated | §6.1 — motivation stated |
| SR1-21 | "Correct a correction" rule unclear | §4.3 — clarified |
| SR1-22 | Max 10 corrections unjustified | §4.4 — configurable per deployment |
| SR1-23 | localStorage key collision | §9.1 — uses existing `createLocalLayoutStore` |

### Decisions

All 16 decisions are recorded in `specs/issue-34-space-nav-enhancements/decisions.md`.
