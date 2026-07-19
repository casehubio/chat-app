# Topic Navigator and Topic View Mode — Design Spec

**Date:** 2026-07-19
**Issue:** casehubio/chat-app#6
**Status:** Draft
**Depends on:** casehubio/connectors#61 (Phase 1, CLOSED), casehubio/qhorus#328 (Topic field, CLOSED)
**Cross-repo:** casehubio/blocks-ui (channel-activity package):
  - `types.ts`: new `QhorusTopic` type, `TOPIC_STATES`, `topicId` on `QhorusMessage`
  - `events.ts`: new event topics (SELECT_TOPIC, VIEW_MODE, CREATE_TOPIC, RESOLVE_TOPIC, REOPEN_TOPIC, ARCHIVE_TOPIC, RENAME_TOPIC, MERGE_TOPIC), `topicId` on `SendMessagePayload`
  - New component: `channel-topic-bar`
  - `channel-feed`: `viewMode` and `topics` props
  - `channel-input`: topic selector UI
  - Requires blocks-ui version publish before chat-app can consume
**Parent issues:** casehubio/qhorus#328 (topic field epic), casehubio/connectors#61 §Phase 4

---

## Problem

Channels carry all conversation in a single flat stream. When multiple tasks, investigations, or discussion threads run in the same channel, the feed becomes unreadable — five agents working five tasks in one channel produces an interleaved stream where context-switching between topics requires manual scanning.

The conversation model spec (§1) defines topics as "named, persistent sub-conversations within a channel" — inspired by Zulip's mandatory topic model. The qhorus model already has `topic` on Message (qhorus#328, closed). The chat-app backend hardcodes every message's topic to `"General"`. The blocks-ui channel-feed has no topic awareness.

## Solution

Add topic support end-to-end: a `topics` entity in the backend with full lifecycle (active → resolved → archived, plus merge), a topic navigator bar and topic view mode in the channel-feed, and a topic selector in the message input. Topics use mandatory assignment with progressive disclosure — every message belongs to a topic (default "General"), but the UI only surfaces topic controls when a channel has more than one topic.

## Non-Goals

- Space-based channel hierarchy (issue #7 — separate concern)
- Topic-level permissions or access control
- Cross-channel topic linking
- Topic templates or auto-creation rules

---

## 1. Data Model

### Topic entity

Topics are first-class entities with IDs, not denormalized strings on messages. Topic is a mutable relationship (rename, merge) — normalizing it avoids O(n) message rewrites on rename and keeps the WebSocket protocol efficient.

**Relationship to qhorus#329:** The qhorus API model (`io.casehub.qhorus.api.message.Message`) defines `String topic` as an implicit, string-based field — topics emerge from usage, rename is a bulk message rewrite, and lifecycle state lives in a separate `TopicState` table. This spec deliberately supersedes that model for the chat-app context. The normalized `topics` table provides O(1) rename, first-class merge semantics, and integrated lifecycle — capabilities the string-based model cannot efficiently support. The qhorus `Message.topic` field continues to carry the display name for wire compatibility; the chat-app's local `topic_id` FK is the structural reference. A follow-up issue should be filed on qhorus to evolve the platform model toward normalized topics if other connectors need these capabilities.

```sql
CREATE TABLE topics (
    id          TEXT PRIMARY KEY,
    channel_id  TEXT NOT NULL,
    name        TEXT NOT NULL,
    state       TEXT NOT NULL DEFAULT 'ACTIVE',
    merged_into TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    UNIQUE(channel_id, name),
    FOREIGN KEY (channel_id) REFERENCES channels(id),
    FOREIGN KEY (merged_into) REFERENCES topics(id)
);
```

Messages gain a `topic_id` column referencing the topics table.

### Migration strategy

The `NOT NULL` constraint on `topic_id` cannot be added directly to a table with existing rows (SQLite does not support `ALTER COLUMN`). The migration proceeds in order:

1. Create the `topics` table (schema above).
2. For each existing channel, insert a "General" default topic row.
3. `ALTER TABLE messages ADD COLUMN topic_id TEXT REFERENCES topics(id)` — nullable initially.
4. Backfill: `UPDATE messages SET topic_id = (SELECT id FROM topics WHERE channel_id = messages.channel_id AND name = 'General')`.
5. Recreate the `messages` table with `topic_id TEXT NOT NULL REFERENCES topics(id)` and copy data (SQLite table rebuild pattern, as used in `createSchema()` for similar constraints).

The `addColumnIfMissing()` utility already handles idempotent column additions. Step 5 uses the same table-rebuild pattern the codebase uses for constraint changes.

**Seed database:** The seed `.db` file (copied from classpath in `seedIfNeeded()`) must be regenerated with the new schema and pre-populated topics. The §6 seed data defines the topic content; the seed `.db` must include both the `topics` table rows and `topic_id` values on all message rows.

### Default topic

Channel creation auto-creates a "General" topic. Every channel always has at least one topic. The default topic cannot be archived or merged — it is the catch-all for messages without explicit topic assignment.

### Lifecycle state machine

```
ACTIVE  ←→  RESOLVED  →  ARCHIVED
  ↑            ↑              |
  |            +--------------+  (reopen)
  +---------------------------+  (reopen)
  |
  +--→ ARCHIVED  (direct archive)

Any non-default, non-MERGED topic  →  MERGED  (terminal, sets merged_into)
```

| State | Navigator bar | Accepts messages | Notes |
|-------|--------------|-----------------|-------|
| ACTIVE | Visible, normal | Yes | Default state |
| RESOLVED | Visible, dimmed, sorted to end | Yes (reopens to ACTIVE) | "Investigation complete" signal |
| ARCHIVED | Hidden (visible via toggle) | Yes (reopens to ACTIVE) | Fully hidden from default view |
| MERGED | Removed | No (source topic absorbed) | Terminal — messages already rewritten to target |

**Valid state transitions** (enforced by `PUT /api/channels/{channelId}/topics/{topicId}`):

| From | To | Valid? | Notes |
|------|----|--------|-------|
| ACTIVE | RESOLVED | Yes | Mark investigation complete |
| ACTIVE | ARCHIVED | Yes | Direct archive without resolving first |
| RESOLVED | ACTIVE | Yes | Reopen |
| RESOLVED | ARCHIVED | Yes | Archive after resolution |
| ARCHIVED | ACTIVE | Yes | Reopen (via PUT or implicit via message post) |
| ARCHIVED | RESOLVED | No | Must reopen to ACTIVE first |
| Any | MERGED | No | Merge endpoint only (`POST .../merge`) |
| Same → Same | — | 200 no-op | Idempotent — avoids race condition errors |

The default topic ("General") cannot transition to ARCHIVED (see §1 Default topic).

### Rename

Single row update: `UPDATE topics SET name = ?, updated_at = ? WHERE id = ?`. Zero message changes. One WebSocket `replace` op on the topics dataset.

### Merge

Merge topic A into topic B (steps 1–2 in a single database transaction, matching the `deleteChannel()` pattern of `conn.setAutoCommit(false)` / `commit()` / `rollback()`):

1. `UPDATE messages SET topic_id = B.id WHERE topic_id = A.id` — rewrites message assignments
2. `UPDATE topics SET state = 'MERGED', merged_into = B.id WHERE id = A.id`
3. Commit transaction.
4. Broadcast (after commit): `remove` op for A on topics dataset, `replace` op for B (updated message count), channel message snapshot

Constraints:
- Merge target must be non-MERGED (no recursive chains)
- If the merge target is RESOLVED or ARCHIVED: auto-reopen it to ACTIVE before completing the merge. Receiving merged messages is a significant content change — the topic should be visible. This parallels the existing behavior for posting a message to a RESOLVED/ARCHIVED topic.
- Default topic ("General") cannot be the merge source
- Merge is not reversible

---

## 2. Backend API

### New REST endpoints

```
POST   /api/channels/{channelId}/topics
       Body: { name: string }
       Returns: { id, channelId, name, state, createdAt }
       Creates a new ACTIVE topic. 409 if name already exists in channel.
       Validation: reject empty/whitespace-only names (400), trim leading/trailing
       whitespace, max 100 characters after trim (400), "General" is reserved and
       cannot be created explicitly (409 — the default topic is auto-created with
       the channel). Same validation applies to implicit topic creation via message post
       and to rename via PUT.

GET    /api/channels/{channelId}/topics
       Query: ?state=ACTIVE,RESOLVED (optional filter, comma-separated)
       Returns: [{ id, channelId, name, state, messageCount, latestActivityTs, createdAt }]

PUT    /api/channels/{channelId}/topics/{topicId}
       Body: { name?: string, state?: 'ACTIVE' | 'RESOLVED' | 'ARCHIVED' }
       Rename and/or state change. 409 if new name conflicts. 400 if invalid transition.

POST   /api/channels/{channelId}/topics/{topicId}/merge
       Body: { targetTopicId: string }
       Merges this topic into the target. 400 if target is MERGED or source is default.
```

### Modified endpoints

`PostMessageRequest` gains `topic` and `topicId` fields:

```java
record PostMessageRequest(String text, String messageType, String actorType,
                          String target, List<Map<String, Object>> artefactRefs,
                          String topic, String topicId)
```

**`POST /api/channels/{channelId}/messages`:**
- If `topicId` is provided: validate that the topic exists AND its `channel_id` matches the `{channelId}` from the URL path. Return 400 if the topic doesn't exist or belongs to a different channel. Use it directly if valid (stable reference, avoids rename race).
- Else if `topic` name is provided: resolve name → topic_id within the specified channel. If name doesn't exist, create a new ACTIVE topic in this channel. On UNIQUE constraint violation (concurrent creation race), retry the resolve — the topic now exists from the other request's insert.
- If neither is provided: use the channel's default ("General") topic_id.
- If the resolved topic is RESOLVED or ARCHIVED: reopen it to ACTIVE.
- Store `topic_id` on the message row via `storeEnrichedFields()` — the same pattern used for `messageType`, `actorType`, `correlationId`, and `target` (see `ChatResource.postMessage()` line 110). The `ChatBackend` SPI (`storeMessage()` / `ReceivedMessage`) is not modified — topic is a chat-app concern managed at the resource layer, not a connector SPI concern. `storeEnrichedFields()` gains a `topicId` parameter.

**`POST /api/channels/{channelId}/messages/{messageId}/replies`:**
- Always inherits the parent message's `topic_id`. Any `topicId` or `topic` fields in the request body are **ignored**.
- Rationale: a reply is part of its parent's conversation thread, which belongs to a topic. Allowing cross-topic replies creates inconsistent reply counts when the workbench filters by topic — `replyCount` is computed globally in the adapter but `_separateRootsAndReplies()` only sees the topic-filtered set, causing "3 replies" badges on messages with 0 visible replies. Prohibiting cross-topic replies eliminates this class of bugs at the design level. To continue a discussion in a different topic, send a new root message (not a reply) in the target topic.

### WebSocket protocol changes

New `topics` dataset added to the snapshot:

```
TOPIC_COLUMNS: [topicId, channelId, name, state, messageCount, latestActivityTs, createdAt]
```

**Snapshot ordering:** `buildSnapshot()` must send the `topics` dataset before `messages`. The adapter resolves `topic_id` → topic name using the topics array, so topics must be available before message rows are processed. Current snapshot order (channels → messages → members → presence → reactions → commitments) becomes: channels → **topics** → messages → members → presence → reactions → commitments.

**Message row[8] semantic change:** Index 8 changes from hardcoded `"General"` (human-readable string, see `messageToRow()` line 294) to the message's `topic_id` (UUID). This changes `QhorusMessage.topic` from wire-delivered to adapter-derived — the adapter resolves the UUID to a display name via the topics array. The `chat-demo-adapter.ts` `_toMessage()` method (currently `topic: (row[8] as string) || 'General'` at line 100) must be updated to map `row[8]` as `topicId` and resolve the name from the topics array. Unknown `topicId` values fall back to empty string for topic name.

Broadcast ops for topic changes:
- `topics` / `append` — new topic created
- `topics` / `replace` — rename, state change, or message count update
- `topics` / `remove` — topic merged (absorbed into another)

**`deleteChannel` cascade update:** The existing transactional cascade in `SqliteChatBackend.deleteChannel()` must include topics. Since `messages.topic_id` references `topics.id`, messages must be deleted before topics. Updated cascade order: artefact_refs → commitments → reactions → messages → **topics** → members → channels.

---

## 3. Frontend — blocks-ui-channel-activity

### New types

```typescript
export const TOPIC_STATES = ['ACTIVE', 'RESOLVED', 'ARCHIVED', 'MERGED'] as const;
export type TopicState = typeof TOPIC_STATES[number];

export interface QhorusTopic {
  readonly id: string;
  readonly channelId: string;
  readonly name: string;
  readonly state: TopicState;
  readonly messageCount: number;
  readonly latestActivityTs?: string;
  readonly createdAt: string;
}
```

`QhorusMessage` gains `topicId: string` (stable ID for filtering/grouping, alongside existing `topic: string` which carries the display name).

### New events

```typescript
SELECT_TOPIC: 'channel:select-topic',       // { channelId, topicId: string | null }
VIEW_MODE: 'channel:view-mode',             // { mode: 'flat' | 'threaded' | 'topics' }
CREATE_TOPIC: 'channel:create-topic',       // { channelId, name }
RESOLVE_TOPIC: 'channel:resolve-topic',     // { channelId, topicId }
REOPEN_TOPIC: 'channel:reopen-topic',       // { channelId, topicId }
ARCHIVE_TOPIC: 'channel:archive-topic',     // { channelId, topicId }
RENAME_TOPIC: 'channel:rename-topic',       // { channelId, topicId, newName }
MERGE_TOPIC: 'channel:merge-topic',         // { channelId, sourceTopicId, targetTopicId }
```

`SendMessagePayload` gains `topicId?: string` alongside the existing `topic?: string`. When sending a message to an existing topic, `topicId` provides a stable reference that survives renames. When creating a new topic inline, `topic` carries the name. This is a cross-repo change to the blocks-ui `channel-activity` package's `events.ts`.

### New component: `<channel-topic-bar>`

Horizontal scrollable bar rendered above the channel-feed.

**Props:**
- `topics: QhorusTopic[]`
- `selectedTopicId: string | null` (null = "All")
- `viewMode: 'flat' | 'threaded' | 'topics'`

**Internal state:**
- `_showArchived: boolean = false` — managed locally within the component, not exposed as a prop or event. This is a view filter (which pills are visible), not domain state.

**Renders:**
- "All" pill (always first, selected when `selectedTopicId` is null)
- Topic pills sorted: ACTIVE by latest activity descending, then RESOLVED (dimmed) at end, ARCHIVED only if `_showArchived`
- Each pill: topic name, message count badge, state indicator dot (green/gray/hollow)
- Selected pill: accent background
- **"Show archived" toggle** (after the last pill, before view mode toggle): an eye icon button, visible only when the channel has ARCHIVED topics. Click toggles `_showArchived`. When active, ARCHIVED pills appear (dimmed + italic). `aria-pressed` reflects the toggle state.
- View mode toggle: Flat / Threaded / Topics buttons
- Context menu on pills: state-dependent actions — ACTIVE: Resolve, Archive, Rename, Merge into...; RESOLVED: Reopen, Archive, Rename, Merge into...; ARCHIVED: Reopen, Rename. Default topic ("General") omits Archive and Merge.

**Accessibility:** `RovingTabindexMixin` for keyboard navigation across pills. `aria-pressed` on selected pill. View mode toggle uses `role="radiogroup"`.

### `<channel-feed>` changes

New props:
- `viewMode: 'flat' | 'threaded' | 'topics'` (default: `'flat'`)
- `topics: QhorusTopic[]` (for topic section headers in Topics mode)

Rendering by mode:
- **flat** — current behavior unchanged. Chronological order, sender-grouped via `_groupFlat()` (consecutive messages from the same sender within 2 minutes are collapsed into one group). Roots with replies render as `<channel-thread>` inline within their sender group; roots without replies render as standalone `<channel-message>`. This is the existing semi-threaded rendering.
- **threaded** — **no sender grouping**. Each root-with-replies renders as its own `<channel-thread>` regardless of sender adjacency. Messages with no parent and no replies render as standalone single-message entries. Sort order: by root message timestamp. The key difference from flat mode is removing sender-based visual grouping — each conversation thread stands independently, making it easier to scan distinct discussions without the visual merging that sender grouping creates.
- **topics** — group messages by `topicId`, render topic section headers (name + state badge + message count). Chronological within each section. ACTIVE topics first, RESOLVED dimmed, ARCHIVED dimmed + italic.

Filtering is NOT the feed's responsibility — the workbench filters messages before passing them. In Topics mode with "All" selected, the feed receives all messages and groups by topic. With a specific topic selected, it receives only that topic's messages.

### `<channel-input>` changes

New props:
- `topic: string` — current topic name (display)
- `topicId: string` — current topic ID
- `topics: QhorusTopic[]` — for autocomplete dropdown
- `showTopicSelector: boolean` — progressive disclosure

Topic selector UI:
- Pill above the textarea showing current topic name
- Click opens autocomplete dropdown listing existing ACTIVE + RESOLVED topics
- "Create new topic..." option at bottom of dropdown
- Selecting a topic updates the pill
- Send includes `topicId` (existing topic) or `topic` name (new topic) in `SendMessagePayload`

When replying: always inherits parent message's topic. The topic pill shows the inherited topic name but is **read-only** (not clickable). This enforces the invariant that replies stay in the parent's topic — see §2 reply endpoint rationale.

---

## 4. Frontend — chat-app wiring

### Adapter changes (`ChatDemoAdapter`)

- New array: `topics: QhorusTopic[]`
- New method: `_applyTopics(op)` — processes snapshot/append/replace/remove on topics dataset
- `_toMessage()`: maps `row[8]` as `topicId`, resolves topic name from `topics` array to set `topic`
- `_notify('topics')` fires on topic dataset changes

### Workbench changes (`QhorusWorkbench`)

New state:
```typescript
@state() private _topics: QhorusTopic[] = [];
@state() private _selectedTopicId: string | null = null;
@state() private _viewMode: 'flat' | 'threaded' | 'topics' = 'flat';
```

Modified `_onDataChange`: copies `_adapter.topics`.

Modified `_filteredMessages()`:
```typescript
private _filteredMessages(): QhorusMessage[] {
  if (!this._selectedChannelId) return [];
  let msgs = this._messages.filter(m => m.channelId === this._selectedChannelId);
  if (this._selectedTopicId) {
    msgs = msgs.filter(m => m.topicId === this._selectedTopicId);
  }
  return msgs;
}
```

Modified `_renderChat()`:
- Renders `<channel-topic-bar>` above feed when channel topics > 1
- Passes `viewMode`, `topics` to feed
- Passes `topic`, `topicId`, `topics`, `showTopicSelector` to input
- Default topic for input: selected topic from navigator bar, or channel's "General"

Modified `_sendMessage()`: includes `topicId` (existing) or `topic` name (new) in REST body.

New event handlers in `_onChatEvent`:
- `SELECT_TOPIC` → updates `_selectedTopicId`
- `VIEW_MODE` → updates `_viewMode`
- `RESOLVE_TOPIC` / `ARCHIVE_TOPIC` / `REOPEN_TOPIC` → `PUT /api/channels/{channelId}/topics/{topicId}` with state
- `RENAME_TOPIC` → `PUT` with new name
- `MERGE_TOPIC` → `POST .../merge` with targetTopicId
- `CREATE_TOPIC` → `POST /api/channels/{channelId}/topics` with name

---

## 5. Progressive Disclosure

| Condition | Topic bar | Topic selector in input | View mode toggle |
|-----------|-----------|------------------------|-----------------|
| Channel has 1 topic ("General" only) | Hidden | Hidden (but see below) | Hidden |
| Channel has 2+ non-MERGED topics | Visible | Visible | Visible |
| All topics except General are MERGED | Hidden | Hidden (but see below) | Hidden |

**Topic creation escape hatch:** When the full topic selector is hidden (single-topic channel), the `channel-input` renders a subtle "New topic" affordance — a `+` icon button adjacent to the send button. Clicking it opens an inline name input to create a topic and assign the current message to it. This prevents the UX dead end where humans cannot create the first non-General topic. Once the topic is created and the channel has 2+ topics, the full topic bar and selector become visible via progressive disclosure. The `+` button is not rendered when the full topic selector is already visible (no duplication).

**Channel switch:** `_selectedTopicId` resets to `null` (All). `_viewMode` persists (user preference).

**New topic via input:** When a user creates a new topic and sends a message, the navigator bar auto-selects that topic.

**Message to resolved/archived topic:** Backend reopens to ACTIVE. Topic bar updates via WebSocket broadcast.

---

## 6. Seed Data

Update demo seed to showcase multi-topic channels:

**Channel "engineering":**
- "General" (default) — 4 messages, casual discussion
- "deployment-pipeline" — 6 messages, ACTIVE, agent COMMAND/STATUS/DONE chain about CI fix
- "incident-2024-03" — 5 messages, RESOLVED, investigation with human + agent collaboration

**Channel "case-investigation":**
- "General" — 2 messages
- "evidence-review" — 8 messages, ACTIVE, document artifact references
- "timeline-reconstruction" — 5 messages, ACTIVE, correlation chains
- "witness-analysis" — 3 messages, ARCHIVED

This showcases: progressive disclosure (multi-topic channels show the topic bar), lifecycle states (resolved, archived), topic view mode (grouping visible), and the topic selector (messages tagged to different topics).

---

## 7. Testing Strategy

### blocks-ui-channel-activity (vitest + jsdom)

**`channel-topic-bar` (new):**
- Renders "All" pill + topic pills from props
- Sort order: ACTIVE by latest activity, RESOLVED dimmed at end, ARCHIVED hidden/shown by toggle
- Emits `channel:select-topic` on pill click with correct topicId
- Emits `channel:view-mode` on toggle click
- Context menu: state-dependent actions — ACTIVE shows Resolve/Archive/Rename/Merge, RESOLVED shows Reopen/Archive/Rename/Merge, ARCHIVED shows Reopen/Rename only
- Context menu: default topic ("General") omits Archive and Merge
- Keyboard: arrow keys navigate pills, Enter selects
- Does not render when topics array has ≤ 1 entry (tested at consumer level)
- "Show archived" eye icon: hidden when no ARCHIVED topics exist
- "Show archived" eye icon: visible when ARCHIVED topics exist, click toggles archived pill visibility
- "Show archived" eye icon: `aria-pressed` reflects toggle state

**`channel-feed` (modified):**
- Flat mode: existing behavior unchanged (regression suite)
- Threaded mode: groups by root parent, renders channel-thread per group, standalone messages render individually
- Topics mode: groups by topicId, renders section headers with topic name + state badge, chronological within sections
- Empty topic sections not rendered
- `viewMode` prop defaults to 'flat'

**`channel-input` (modified):**
- Topic selector visible when `showTopicSelector` is true, hidden when false
- Autocomplete lists ACTIVE + RESOLVED topics, excludes ARCHIVED and MERGED
- "Create new topic" option present in dropdown
- Send payload includes topicId (existing topic) or topic name (new topic)
- Reply shows inherited topic in read-only pill (not overridable)
- Escape hatch `+` button: renders when `showTopicSelector` is false
- Escape hatch `+` button: does NOT render when `showTopicSelector` is true
- Escape hatch `+` button: click opens inline name input
- Escape hatch `+` button: submit emits `channel:create-topic` with name and associates current message
- Escape hatch `+` button: accessible (aria-label, keyboard reachable)

### chat-app backend (JUnit)

**`SqliteChatBackend`:**
- `createTopic` / `listTopics` / `findTopic` / `updateTopic` / `mergeTopic` CRUD
- Default "General" topic auto-created with channel
- Topic name uniqueness constraint within channel
- Merge rewrites message topic_id, sets source MERGED with merged_into
- Rename updates name only, messages unchanged
- State transitions enforce valid paths: ACTIVE→RESOLVED, ACTIVE→ARCHIVED, RESOLVED→ACTIVE, RESOLVED→ARCHIVED, ARCHIVED→ACTIVE all succeed; ARCHIVED→RESOLVED returns 400; same-state transitions return 200 (no-op)
- Cannot archive or merge default topic ("General")
- Cannot merge into MERGED topic
- Message storage with topic_id FK

**`ChatResource`:**
- POST message with topic name → resolves to correct topic_id
- POST message with unknown topic name → creates new topic
- POST message with no topic → defaults to "General"
- POST reply → always inherits parent topic (explicit topicId/topic in body ignored)
- POST message with topicId from different channel → 400
- POST message with concurrent duplicate topic name → upsert succeeds (no 500)
- Topic name validation: empty → 400, whitespace-only → 400, >100 chars → 400, "General" → 409
- Message to RESOLVED/ARCHIVED topic → reopens to ACTIVE
- Topic REST endpoints: create (201/409), list (with state filter), update (rename/state), merge (400 on invalid)

**`ChatWebSocketBroadcaster`:**
- Snapshot includes topics dataset with correct columns
- Message rows carry topicId at index 8
- Topic create → append op
- Topic rename/state change → replace op
- Topic merge → remove op for source, replace for target

### chat-app frontend (vitest)

**`ChatDemoAdapter`:**
- Processes topics dataset ops (snapshot, append, replace, remove)
- `_toMessage` maps topicId from row[8], resolves topic name from topics array
- Unknown topicId falls back to empty string for topic name

**`QhorusWorkbench`:**
- Topic bar rendered when channel has >1 non-MERGED topic, hidden otherwise
- `_filteredMessages` filters by selectedTopicId when set
- selectedTopicId resets to null on channel switch
- viewMode persists across channel switches
- Topic lifecycle events → correct REST calls
- `_sendMessage` includes topic in body
- Auto-select topic on new topic creation + message send
