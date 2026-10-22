# Dockable Contextual Panels — Design Spec

**Date:** 2026-07-18
**Status:** Draft
**Issue:** casehubio/chat-app#4
**Depends on:** casehubio/connectors#61 (Phase 1 Foundation) — closed
**Spec ref:** connectors workspace `specs/2026-07-07-qhorus-chat-ui-design.md` §2 composites, §4 Layer 3

## Problem

The chat-app workbench renders qhorus channel conversations as a flat message
stream. The data model carries rich structured information — speech acts,
correlation chains (COMMAND→STATUS→DONE), commitment lifecycles, artifact
references — but the UI flattens it all into a Slack-like text feed.

Without surfacing this structure, the workbench is no different from Slack.
The point of qhorus-native UI is improved coordination and control for LLM
agents and human participants.

## Solution

Three dockable contextual panels that surface the structured data alongside
the feed:

- **Task panel** — commitment/obligation tracker (COMMAND messages + state)
- **Correlation panel** — full chain flow diagram for a selected message
- **Artifact panel** — side viewer for referenced documents/code/cases

Backed by enriching the chat-app SQLite backend to store and serve the
structured data that these panels need.

## Non-Goals

- Using the pages `dockBar()` runtime renderer — `dockBar()` is a DSL
  builder in `@casehubio/pages-ui` that creates `Component<"dock-bar">`
  model nodes. These model nodes require a pages-runtime renderer to
  display, and no such renderer exists (dock-bar is model-only). The
  workbench is a standalone LitElement, not a pages site rendered by
  pages-runtime. The spec uses the `DockItem` interface for the data model
  and `LayoutStore` for persistence — the same level of pages integration
  that issue #4 calls for — while rendering its own dock strip.
- Building a generic artifact content management system — the artifact panel
  displays referenced content, it does not manage or create artifacts.
- Multi-agent delegation fork visualization — HANDOFF renders as a regular
  node with a delegation indicator in v1.

---

## 1. Backend Enrichment

### Schema Changes

**Messages table — new columns:**

| Column | Type | Default | Purpose |
|--------|------|---------|---------|
| `message_type` | VARCHAR | `'EVENT'` | Speech act: COMMAND, QUERY, RESPONSE, STATUS, DONE, FAILURE, DECLINE, HANDOFF, EVENT |
| `actor_type` | VARCHAR | `'HUMAN'` | Actor type: HUMAN, AGENT, SYSTEM |
| `correlation_id` | VARCHAR | NULL | Groups a COMMAND→STATUS→DONE chain |
| `target` | VARCHAR | NULL | Delegation target — who is responsible for fulfilling |

**New table: `commitments`**

| Column | Type | Constraint | Purpose |
|--------|------|-----------|---------|
| `id` | VARCHAR | PK | Same as the COMMAND message ID |
| `channel_id` | VARCHAR | NOT NULL | Channel containing the commitment |
| `state` | VARCHAR | NOT NULL | OPEN, ACKNOWLEDGED, FULFILLED, FAILED, DECLINED, DELEGATED, EXPIRED |
| `deadline` | TIMESTAMP | NULL | Optional deadline for the obligation |
| `acknowledged_at` | TIMESTAMP | NULL | When the commitment was acknowledged |
| `created_at` | TIMESTAMP | NOT NULL | When the COMMAND was sent |
| `updated_at` | TIMESTAMP | NOT NULL | Last state change |

**New table: `artefact_refs`**

| Column | Type | Constraint | Purpose |
|--------|------|-----------|---------|
| `message_id` | VARCHAR | FK → messages | Owning message |
| `uri` | VARCHAR | NOT NULL | Artifact location |
| `type` | VARCHAR | NOT NULL | DOCUMENT, CODE, CASE, WORK_ITEM, CHANNEL, DEBATE, MESSAGE, EXTERNAL |
| `label` | VARCHAR | NOT NULL | Display label |
| `start_line` | INTEGER | NULL | Selection scope start line |
| `end_line` | INTEGER | NULL | Selection scope end line |
| `start_offset` | INTEGER | NULL | Selection scope start character offset |
| `end_offset` | INTEGER | NULL | Selection scope end character offset |
| `selected_text` | TEXT | NULL | Selected text excerpt |

### Correlation Auto-Assignment

When posting a COMMAND message:
- Set `correlationId = message.id` (self-referencing — the COMMAND is the
  root of its own chain)
- Auto-create a commitment record with `state = OPEN`

When posting a reply:
- If the parent message is a COMMAND: `correlationId = parent.id`
- If the parent message has a `correlationId`: inherit it
- Otherwise: no `correlationId`

**Invariant:** every message in a correlation chain shares the same
`correlationId`, which equals the root COMMAND's message ID. The
correlation panel's algorithm is: find `correlationId` → filter all
messages with that `correlationId` → display. This works regardless of
which node in the chain is selected, including the root COMMAND.

### REST API Changes

**Expanded `PostMessageRequest`:**

```java
record PostMessageRequest(
    String text,
    String messageType,    // default EVENT
    String actorType,      // default HUMAN
    String target,         // optional delegation target
    ArtefactRef[] artefactRefs  // optional
)
```

**New endpoints:**

| Method | Path | Purpose |
|--------|------|---------|
| PATCH | `/api/channels/{chId}/commitments/{commitmentId}` | Update commitment state (`{state, acknowledgedAt?}`) |
| GET | `/api/channels/{chId}/commitments` | List all commitments in channel |
| GET | `/api/channels/{chId}/correlation/{correlationId}` | Get all messages in a correlation chain |

### SPI Boundary

The connectors `ChatBackend` SPI is cross-platform and will NOT be
modified. The new fields (`message_type`, `actor_type`, `correlation_id`,
`target`, `artefact_refs`) are qhorus-specific and are stored via
`SqliteChatBackend`-specific methods that bypass the SPI — the same
pattern used for `markRead`, `memberRole`, and `lastActiveAt`.

The flow: REST request → `ChatResource` calls `ChatBackend.storeMessage()`
for the base message, then calls `SqliteChatBackend`-specific methods to
store the enriched fields in the same transaction.

### WebSocket Broadcast

**Messages dataset** — `MESSAGE_COLUMNS` expands from 6 to 12 columns:

| Index | Column ID | Name | Type |
|-------|-----------|------|------|
| 0 | `channelId` | Channel | LABEL |
| 1 | `messageId` | Message ID | LABEL |
| 2 | `parentId` | Parent | LABEL |
| 3 | `senderId` | Sender | LABEL |
| 4 | `text` | Text | LABEL |
| 5 | `timestamp` | Timestamp | DATE |
| 6 | `messageType` | Type | LABEL |
| 7 | `actorType` | Actor | LABEL |
| 8 | `topic` | Topic | LABEL |
| 9 | `correlationId` | Correlation | LABEL |
| 10 | `artefactRefs` | Artefacts | LABEL |
| 11 | `target` | Target | LABEL |

`messageToRow()` expands to emit all 12 fields. The enriched fields are
queried from the messages table (which now has the additional columns)
rather than from the `ReceivedMessage` record.

**New `commitments` dataset** — broadcast via snapshot/replace ops:
- Row format: `[commitmentId, channelId, state, deadline, acknowledgedAt, createdAt, updatedAt]`
- `replace` op sent on state change

### Cascade Delete

`SqliteChatBackend.deleteChannel()` must cascade to the new tables:
`artefact_refs` (via message_id) and `commitments` (via channel_id) are
deleted before messages and channels respectively.

---

## 2. Adapter Enrichment

### `ChatDemoAdapter` Changes

**`_toMessage` mapping** — new row positions:

| Index | Field | Current | New |
|-------|-------|---------|-----|
| 6 | `messageType` | Mapped (default EVENT) | Unchanged |
| 7 | `actorType` | Mapped (default HUMAN) | Unchanged |
| 8 | `topic` | Mapped (default General) | Unchanged |
| 9 | `correlationId` | Not mapped (hardcoded undefined) | Map from row |
| 10 | `artefactRefs` | Not mapped (hardcoded []) | Parse JSON from row |
| 11 | `target` | Not mapped (hardcoded undefined) | Map from row |

**New type** (defined in `@casehubio/agency-ui-channel-activity/types.ts`
alongside `CommitmentState`):

```typescript
interface CommitmentRecord {
  state: CommitmentState;
  deadline?: string;
  acknowledgedAt?: string;
  createdAt: string;
  updatedAt: string;
}
```

**New dataset:**

```typescript
commitments: Map<string, CommitmentRecord> = new Map();
```

New `_applyCommitments(op)` handler for snapshot/replace ops, following the
same pattern as the existing `_applyPresence`. Maps commitment rows to
`CommitmentRecord` objects.

### Workbench Wiring

`QhorusWorkbenchElement` gains:
- `@state() private _commitments: Map<string, CommitmentRecord>`
- `_onDataChange` handler populates from adapter
- Passes `_commitments` to `channel-feed` and `qhorus-task-panel`

**Companion blocks-ui change: `channel-feed` commitment wiring.**
`channel-feed.ts` currently declares `commitments: Map<string,
CommitmentState>` but never forwards `commitmentState` to
`channel-message` in its render method. This spec requires:
1. Define `CommitmentRecord` in blocks-ui alongside `CommitmentState` in
   `types.ts` (chat-app depends on blocks-ui — importing the other direction
   would create a circular dependency). Change `channel-feed`'s `commitments`
   prop to `Map<string, CommitmentRecord>`
2. In `_renderFeed()`, look up the commitment for each COMMAND message
   and pass `.commitmentState=${record?.state}` to `channel-message`

This is the same companion-PR pattern as the artefact chip click handler
(R1-08). Without it, commitment badges only appear in the task panel,
not in the main feed.
- `_sendMessage` forwards `speechAct` → `messageType` and `artefactRefs`
  from `SendMessagePayload` to the REST API (currently sends only
  `{ text }`). `target` is not in `SendMessagePayload` — it is set
  programmatically via the REST API by agents, not through the chat UI

---

## 3. Dock Strip — Pages DockItem Model

Replace the custom dock strip with one backed by the pages `DockItem`
interface from `@casehubio/pages-component`.

### DockItem Declaration

```typescript
private static readonly DOCK_ITEMS: DockItem[] = [
  { icon: '💬', label: 'Channels',    panelId: 'nav',         defaultOpen: true },
  { icon: '👥', label: 'Members',     panelId: 'members',     defaultOpen: true },
  { icon: '📋', label: 'Tasks',       panelId: 'tasks',       defaultOpen: false },
  { icon: '🔗', label: 'Correlation', panelId: 'correlation', defaultOpen: false },
  { icon: '📎', label: 'Artifacts',   panelId: 'artifacts',   defaultOpen: false },
];
```

The items array is open-ended — adding future panels (as qhorus alignment
deepens) is just another entry + component registration.

### State Management

```typescript
private _layoutStore = createLocalLayoutStore('qhorus-workbench:');
@state() private _layoutState: LayoutState = { splits: {}, docks: {}, panels: {} };
```

- `_layoutState.docks` holds the per-panel open/closed state
- Initialized from `DockItem.defaultOpen` values
- On `connectedCallback`, `_layoutStore.load('workbench')` loads the full
  `LayoutState` (preserving `splits` and `panels` for future use)
- On toggle, create a new `LayoutState` via immutable update (Lit compares
  object identity — in-place mutation would not trigger a re-render):
  ```typescript
  this._layoutState = {
    ...this._layoutState,
    docks: { ...this._layoutState.docks, [panelId]: !this._layoutState.docks[panelId] }
  };
  this._layoutStore.save('workbench', this._layoutState);
  ```

Theme toggle remains a standalone button at the bottom of the strip (not a
panel).

### Panel Slot Mapping

| panelId | Docking side | Component |
|---------|-------------|-----------|
| `nav` | left | `_renderNav()` (existing) |
| `tasks` | left | `<qhorus-task-panel>` |
| `members` | right | `_renderMembers()` (existing) |
| `correlation` | right | `<qhorus-correlation-panel>` |
| `artifacts` | right | `<qhorus-artifact-panel>` |

### Responsive Behavior

**Desktop (≥1280px):**
- Dock strip always visible on the far left
- Open panels render as side panels alongside the main chat
- Multiple panels can be open simultaneously
- Left panels (nav, tasks) render left of main; right panels (members,
  correlation, artifacts) render right of main

**Tablet (768–1279px):**
- Dock strip visible
- Only one panel open at a time — toggling a new panel closes the previous
- Open panel renders in the sidebar area
- Tab switcher groups panels: **Navigation** (Channels) |
  **People** (Members) | **Context** (Tasks, Correlation, Artifacts).
  Selecting a Context tab opens the corresponding panel in the sidebar.

**Phone (<768px):**
- No dock strip — panel access via phone header overflow menu
- Phone header keeps two direct buttons: hamburger (channels) and members.
  A `⋯` overflow button opens a bottom sheet listing Tasks, Correlation,
  and Artifacts as menu items.
- Panels open as full-height drawers (reusing existing drawer + backdrop +
  swipe infrastructure)
- Only one drawer at a time

---

## 4. Task Panel — `qhorus-task-panel`

### Props

```typescript
@property({ type: Array })  messages: QhorusMessage[] = [];
@property({ type: Object }) commitments: Map<string, CommitmentRecord> = new Map();
@property({ type: String }) selectedMessageId?: string;
```

### Rendering

Groups COMMAND messages by state, active first:

| Group | States | Visual |
|-------|--------|--------|
| Active | OPEN, ACKNOWLEDGED | Top of list, accent/info badge |
| Overdue | OPEN with deadline in the past | Red highlight, warning icon |
| Terminal | FULFILLED, FAILED, DECLINED, DELEGATED, EXPIRED | Dimmed, collapsed by default |

Each row:
- Commitment state badge (colors from `commitmentStateCategory` in
  `@casehubio/agency-ui-channel-activity` types.ts)
- Sender + target (who asked, who's responsible)
- Content preview (first line of COMMAND content, truncated)
- Relative timestamp
- Deadline indicator if present (overdue = red)

### Interactions

- Click row → dispatches `pages-event` with topic
  `ChannelEventTopics.MESSAGE_SELECTED` (`channel:message-selected`)
  so the feed scrolls to the COMMAND
- State badge is visual only — state changes come from the backend

### Empty State

"No commitments in this channel"

---

## 5. Correlation Panel — `qhorus-correlation-panel`

### Props

```typescript
@property({ type: Array })  messages: QhorusMessage[] = [];
@property({ type: Object }) commitments: Map<string, CommitmentRecord> = new Map();
@property({ type: String }) selectedMessageId?: string;
```

### Behavior

When a message is selected in the feed:
1. Find its `correlationId`
2. Filter all messages sharing that `correlationId`
3. Display as a vertical flow diagram, ordered by `createdAt`

Fallback for messages without `correlationId`: if the selected message has
`inReplyTo`, walk the reply chain to build a group.

### Rendering — Vertical Flow

Each node:
- Actor icon + sender name
- Speech act badge (same colors as `channel-message`)
- Content preview (truncated)
- Timestamp
- Duration between this node and the previous (on the connector line)

Root node gets the commitment state badge if it's a COMMAND.

HANDOFF messages render as regular nodes with a delegation indicator
("↳ Delegated to Agent-B"). Fork visualization is a future refinement.

### Interactions

- Click node → `channel:message-selected` → feed scrolls to that message
- Selected node highlighted with accent border

### Empty State

"Select a message to view its correlation chain"

---

## 6. Artifact Panel — `qhorus-artifact-panel`

### Props

```typescript
@property({ type: Object }) selectedArtefactRef?: ArtefactRef;
@property({ attribute: false })
  resolveArtifact?: (ref: ArtefactRef) => Promise<{content: string, language?: string}>;
```

### Rendering by Type

| ArtefactType | Rendering |
|-------------|-----------|
| DOCUMENT | Markdown-rendered content (reuse `renderMarkdown` from blocks-ui-channel-activity) |
| CODE | Syntax-highlighted code with line numbers; selection scope highlighting if `ref.scope` has `startLine`/`endLine` |
| CASE, WORK_ITEM | Structured card — type icon, label, URI as link |
| CHANNEL, MESSAGE | Link to navigate to that channel/message |
| EXTERNAL | External link with icon |

### Header Bar

- Artifact label + type badge
- URI (truncated, with copy button)
- Back/forward navigation through recently viewed artifacts (local history
  stack in `@state()`)

### Selection Scope Highlighting

When `ArtefactRef.scope` includes `startLine`/`endLine`, the code view
highlights those lines. If `selectedText` is present, shown as a
highlighted excerpt above the full content.

### Content Resolver

The workbench passes a `resolveArtifact` callback. For v1, the resolver
returns `{content: ref.label, language: undefined}` — showing the artifact
label as plain text. The panel renders the metadata (type, label, URI) and
the content area shows the label. This is sufficient to validate the panel
infrastructure; real content fetching (loading documents from REST, syntax
highlighting code) is a follow-up once artifact storage is richer.

### Event Integration

New event topic `channel:artefact-selected` — added to
`ChannelEventTopics` in `@casehubio/agency-ui-channel-activity/events.ts`
as `ARTEFACT_SELECTED: 'channel:artefact-selected'`.

Dispatched when a user clicks an artifact chip in `channel-message`. This
requires a companion change in `@casehubio/agency-ui-channel-activity`:
adding `@click` handlers on the `.artefact-chip` spans in
`channel-message.ts` to call `emitPagesEvent(this,
ChannelEventTopics.ARTEFACT_SELECTED, { artefactRef: ref })`.

The workbench catches this event and updates the panel's
`selectedArtefactRef` prop.

### Empty State

"Select a message with attachments"

---

## 7. Event Flow

```
channel-feed                         workbench                    panels
─────────────                       ──────────                   ──────
channel:message-selected ───────────→ updates _selectedMessage ──→ correlation-panel
                                                                  task-panel (highlights)

channel-message
  artefact chip click ─────────────→ channel:artefact-selected ─→ artifact-panel

task-panel
  row click ───────────────────────→ channel:message-selected ──→ channel-feed (scroll)

correlation-panel
  node click ──────────────────────→ channel:message-selected ──→ channel-feed (scroll)
```

All events use `pages-event` (`bubbles: true, composed: true`). The
workbench is the event coordinator — catches events, updates panel props.
Panels never communicate directly.

---

## 8. Build Order — Vertical Slices

### Slice 1: Task Panel (end-to-end)

1. Schema: `commitments` table + `correlation_id`, `message_type`,
   `actor_type`, `target` columns on messages + cascade delete expansion
2. REST: commitment CRUD endpoints, expanded `PostMessageRequest`
3. WebSocket: commitments dataset broadcast, enriched message rows
4. Adapter: map new fields, add commitments dataset
5. Dock strip: refactor to pages `DockItem` model + `LayoutStore`
6. Task panel component + tests
7. Workbench integration: wire commitments to feed + panel

### Slice 2: Correlation Panel

1. REST: correlation chain endpoint
2. Correlation panel component + tests
3. Workbench integration

Backend schema already in place from Slice 1.

### Slice 3: Artifact Panel

1. Schema: `artefact_refs` table
2. REST/WebSocket: include artefact refs in message data
3. Adapter: parse artefact refs from message rows
4. Artifact panel component + stub resolver + tests
5. Workbench integration: `channel:artefact-selected` event wiring

---

## 9. Styling

All panels use `--pages-*` design tokens exclusively, matching the
existing workbench and blocks-ui-channel-activity conventions. No hardcoded
colors, fonts, or spacing. Dark mode works via the OKLCH token system.

Commitment state badge colors follow `commitmentStateCategory` from
`@casehubio/agency-ui-channel-activity/types.ts`:

| State | Token group |
|-------|-------------|
| OPEN | `--pages-accent-*` |
| ACKNOWLEDGED | `--pages-info-*` |
| FULFILLED | `--pages-success-*` |
| FAILED | `--pages-danger-*` |
| DECLINED | `--pages-neutral-*` |
| DELEGATED | `--pages-transfer-*` (category `transfer` — falls through to `--pages-info-*` via `.commitment-transfer` CSS) |
| EXPIRED | `--pages-warning-*` |

---

## 10. Extensibility

The dock strip is designed as an open-ended `DockItem[]` list. As qhorus
alignment deepens, adding panels (e.g., normative triple view, space
navigator, agent control panel) requires only:

1. Add a `DockItem` entry
2. Register the panel component
3. Wire data through the workbench

No structural changes to the dock infrastructure.
