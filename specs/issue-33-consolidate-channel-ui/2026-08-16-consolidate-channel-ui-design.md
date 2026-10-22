# Design: Consolidate Channel UI and Services (#33)

## Problem

chat-app and claudony independently compose the same blocks-ui-channel-activity components over the same qhorus backend, with two separate REST layers and frontend wiring. This duplication means every upstream enhancement must be wired twice, and whichever app gets attention second goes stale.

Both workbenches are ~800-870 lines of Lit composition code. The blocks-ui components (feed, nav, input, thread, member panel, task/correlation/artifact panels, topic bar) are identical. The divergence is in four areas: channel addressing (UUID vs string name), push mechanism (WebSocket vs SSE polling), layout (responsive dock vs terminal-centric), and context headers (none vs case/worker/lineage).

## Solution

Promote reusable channel infrastructure to foundation layers (qhorus, blocks-ui). Both apps consume foundation; neither depends on the other.

### Three repos change

- **qhorus** — gains SpaceResource, enhanced ChannelResource (aggregation + name resolution), and a new `qhorus-push` module (push dataset wiring for channels)
- **chat-app** — shrinks: push infrastructure and channel aggregation move to qhorus. ChatResource keeps only chat-specific operations. Frontend workbench refactored to use composable controllers from blocks-ui.
- **claudony** — shrinks: MeshResource drops to instances-only. ChannelEventBus + SSE deleted, replaced by qhorus-push. Frontend workbench refactored to use same composable controllers.

**blocks-ui** gains composable Lit reactive controllers for channel data management.

**Dependency flow:** `blocks-ui-channel-activity` ← both apps (frontend). `qhorus-push` + `qhorus-runtime` ← both apps (backend). No app-to-app dependency.

---

## Backend: qhorus changes

### SpaceResource (new, ~50 lines in qhorus-runtime)

Thin JAX-RS resource over existing `SpaceService`:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/spaces` | List root spaces |
| `GET` | `/api/spaces/{id}` | Get space |
| `GET` | `/api/spaces/{id}/children` | List child spaces (nested hierarchy) |
| `POST` | `/api/spaces` | Create space (name, description, parentSpaceId) |
| `PUT` | `/api/spaces/{id}` | Update space |
| `DELETE` | `/api/spaces/{id}` | Delete space |
| `GET` | `/api/spaces/{id}/channels` | List channels in space |

Existing qhorus model: `Space` (id, name, description, parentSpaceId, tenancyId, createdAt), `SpaceStore` (full CRUD + listByParent, listRoots, hasChildren), `SpaceService` (create with nesting depth limits, rename, move, cycle detection, channel assignment), `Channel.spaceId`, `ChannelQuery.bySpaceId()/topLevel()`, `ChannelDetail.spaceId/spaceName`.

### ChannelResource enhanced (~250 lines added to existing resource)

qhorus ChannelResource already has name-or-UUID resolution via its existing `resolve(String idOrName)` method — all endpoints already accept either format. The enhancements are new endpoints, not new resolution logic.

**New aggregation endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/channels` | Enhanced — returns `ChannelDetail` with counts, bindings, spaceId, spaceName via `QhorusDashboardService.listChannels()` |
| `GET` | `/api/channels/feed` | Cross-channel recent messages via `QhorusDashboardService.getFeed()` |
| `GET` | `/api/channels/{idOrName}/timeline` | Per-channel message timeline via `QhorusDashboardService.getTimeline()` |
| `POST` | `/api/channels/{idOrName}/reactions/batch` | Batch reaction fetch |

**CRUD endpoints migrated from chat-app ChatResource and claudony MeshResource:**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/channels/{idOrName}/messages/{messageId}/reactions` | Add reaction |
| `DELETE` | `/api/channels/{idOrName}/messages/{messageId}/reactions/{emoji}` | Remove reaction |
| `GET` | `/api/channels/{idOrName}/messages/{messageId}/reactions` | List reactions |
| `POST` | `/api/channels/{idOrName}/topics` | Create topic |
| `GET` | `/api/channels/{idOrName}/topics` | List topics with summaries |
| `PUT` | `/api/channels/{idOrName}/topics/{topicId}` | Update topic (rename/resolve/reopen) |
| `POST` | `/api/channels/{idOrName}/topics/{topicId}/merge` | Merge topics |
| `GET` | `/api/channels/{idOrName}/members` | List members |
| `POST` | `/api/channels/{idOrName}/members` | Join channel |
| `DELETE` | `/api/channels/{idOrName}/members/{memberId}` | Leave channel |
| `GET` | `/api/channels/{idOrName}/presence` | List presence |
| `PUT` | `/api/channels/{idOrName}/presence/{memberId}` | Update presence |
| `GET` | `/api/channels/{idOrName}/commitments` | List commitments |
| `GET` | `/api/channels/{idOrName}/correlation/{correlationId}` | Correlation chain |

### qhorus-push module (new, ~400 lines moved from chat-app)

New Maven module in the qhorus repo containing push dataset wiring for channels:

- **QhorusDatasetBuilder** (renamed from ChatDatasetBuilder) — column definitions, row builders, snapshot construction for 7+1 datasets. Channels dataset gains `spaceId`/`spaceName` columns.
- **QhorusWebSocketBroadcaster** (renamed from ChatWebSocketBroadcaster) — delegates to pages-push `EventBroadcaster` for durable event storage and fan-out
- **QhorusPushWebSocket** (renamed from ChatPushWebSocket) — WebSocket endpoint at `/ws/push`, pages-push protocol (Listen/Unlisten, per-topic since-map replay, gap detection with snapshot fallback)
- **QhorusPushInfrastructure** (renamed from PushInfrastructure) — CDI producer for `EventStore` (InMemoryEventStore), `TopicRegistry`, `EventBroadcaster`, WebSocket connection map

Push dataset topics (unchanged): `chat:channels`, `chat:topics`, `chat:messages`, `chat:members`, `chat:presence`, `chat:reactions`, `chat:commitments`.

---

## Backend: chat-app changes

### ChatResource shrinks

Aggregation endpoints, name resolution, and basic CRUD for reactions/topics/members/presence move to qhorus ChannelResource (these are qhorus SPI operations, not chat-app logic). What remains in ChatResource:
- Message posting with artefact refs, topic resolution, correlation ID generation, auto-join, auto-presence
- Reply posting with parent inheritance
- Read tracking (`PUT /api/channels/{id}/read`)
- Chat-app-specific orchestration (ensureMembership auto-join on first message, ensurePresence auto-online)

### Deleted from chat-app

- `ChatDatasetBuilder` → `QhorusDatasetBuilder` in qhorus-push
- `ChatWebSocketBroadcaster` → `QhorusWebSocketBroadcaster` in qhorus-push
- `ChatPushWebSocket` → `QhorusPushWebSocket` in qhorus-push
- `PushInfrastructure` → `QhorusPushInfrastructure` in qhorus-push

### Stays in chat-app

- `ChatAppChannelBackend` — implements `HumanParticipatingChannelBackend`, registers with `BackendRegistry`, pushes outbound messages via `QhorusWebSocketBroadcaster` (now from qhorus-push)
- `ChatAppCurrentPrincipal` — JWT identity
- `WebSocketTokenUpgradeCheck` — JWT validation for WebSocket upgrade
- Seed data, H2 config, dev-auth, application.properties

**pom.xml gains:** `casehub-qhorus-push` dependency (replaces local push classes)

---

## Backend: claudony changes

### MeshResource shrinks

All channel operations now come from qhorus's ChannelResource and SpaceResource via classpath. What remains:
- `GET /api/mesh/instances` — instance listing (claudony-only)
- `GET /api/mesh/config` — claudony-specific configuration
- `POST /api/mesh/channels/{name}/messages` — human message interjection with claudony-specific logic (VALID_HUMAN_TYPES filtering, allowed-type enforcement, deontic validation). Cannot move to qhorus — this is claudony's domain-specific orchestration over the generic messaging SPI.

### Deleted from claudony

- `ChannelEventBus` — SSE fan-out, replaced by qhorus-push EventBroadcaster
- All SSE endpoints (`/api/mesh/events`, `/api/mesh/channels/{name}/events`)
- All channel REST operations from MeshResource (channels, messages, reactions, topics, members, presence, commitments) — now served by qhorus ChannelResource
- `channel-adapter.ts` — replaced by composable controllers from blocks-ui

### ClaudonyChannelBackend

- Switches from `ChannelEventBus.emit(name)` to `QhorusWebSocketBroadcaster` (from qhorus-push)
- Still implements `HumanObserverChannelBackend` — claudony's gateway role

**pom.xml gains:** `casehub-qhorus-push` dependency

---

## Frontend: composable controllers in blocks-ui

Six Lit reactive controllers extracted into `@casehubio/agency-ui-channel-activity`:

### PushController

WebSocket connection lifecycle via `createEventConnection`. Listens to 7 dataset topics, parses dataset ops (snapshot/append/replace/remove). Exposes `connectionStatus`. Other controllers register with PushController to receive their dataset ops.

Config: push URL, auth token provider.

### ChannelStateController

Channel and space state. Consumes push ops for `channels` and `topics` datasets. Fetches spaces via REST.

Exposes: `channels`, `topics`, `channelTree` (SpaceNode hierarchy with unread counts + ungrouped root channels), `selectedChannelId`, `filteredMessages()`, `channelTopics()`, `viewMode`. Flat `channels` array also available for non-nav views (search, feed filters).

Handles: SELECT_CHANNEL, VIEW_MODE, SELECT_TOPIC events.

### MessagingController

Message sending and event routing. Consumes push ops for `messages` dataset.

REST calls: send message, reply, create/delete channel, topic CRUD (create, rename, merge, resolve/reopen).

Handles: SEND_MESSAGE, CREATE_CHANNEL, DELETE_CHANNEL, CREATE_TOPIC, RENAME_TOPIC, MERGE_TOPIC, RESOLVE_TOPIC, REOPEN_TOPIC events. Manages reply state.

Config: REST base URL, auth token provider.

### MembershipController

Member and presence state. Consumes push ops for `members` and `presence` datasets.

Exposes: `members`, `presence`, `filteredMembers()`. REST calls for join/leave.

### ReactionController

Reaction state. Consumes push ops for `reactions` dataset.

Exposes: `reactions`, `filteredReactions()`. REST calls for add/remove reaction. Handles REACT, UNREACT events.

### CommitmentController

Commitment tracking. Consumes push ops for `commitments` dataset.

Exposes: `commitments` map, `commitmentDecorations` (via `decorateCommitmentRanges`). Handles MESSAGE_SELECTED for correlation panel.

### Composition pattern

Controllers form a dependency chain: PushController is the data source, ChannelStateController owns shared selection state (`selectedChannelId`, `viewMode`), and other controllers accept ChannelStateController as a constructor param to access it. This makes cross-controller dependencies explicit — no implicit shared state or event-based coordination.

```ts
// chat-app: all controllers
this._push = new PushController(this, config);
this._channels = new ChannelStateController(this, this._push);
this._messaging = new MessagingController(this, this._push, this._channels, config);
this._members = new MembershipController(this, this._push, this._channels);
this._reactions = new ReactionController(this, this._push, this._channels);
this._commitments = new CommitmentController(this, this._push, this._channels);

// hypothetical minimal host: just nav + messaging
this._push = new PushController(this, config);
this._channels = new ChannelStateController(this, this._push);
this._messaging = new MessagingController(this, this._push, this._channels, config);
```

Dependency: `PushController` ← `ChannelStateController` ← all other controllers. MessagingController also takes config for REST base URL and auth.

Frontend distribution: via Maven SNAPSHOT WebJar (existing blocks-ui pattern per ADR-0001).

---

## Frontend: app workbench refactoring

### chat-app QhorusWorkbenchElement (~800 → ~200 lines)

Creates all 6 controllers. Owns:
- Responsive layout (desktop/tablet/phone), dock strip, swipe controller, drawers
- Binds controller state to blocks-ui components (feed, nav, input, topic bar, member panel)
- Forwards `pages-event` to controllers
- Theme toggle, identity widget
- Panel rendering (tasks, correlation, artifacts)

### claudony claudony-workbench.ts (~873 → ~400 lines)

Creates the controllers it needs. Owns:
- Terminal-centric layout (worker nav | terminal | conversation | context)
- Binds controller state to same blocks-ui components
- Claudony-specific features:
  - Case context header (role, status, elapsed time)
  - Worker lineage (prior workers chain)
  - Worker switching (SSE case-events — separate from channel push)
  - Terminal integration
  - Stale cursor detection
  - Mesh overview panel (3-view: overview/channel/feed)
  - `allowedTypes` per channel, message type validation

### claudony channel-panel.ts (~494 lines)

Also refactored to use controllers. Channel data management drops out, leaving only claudony-specific rendering. Case header duplication between workbench and channel-panel is a separate cleanup.

### Deleted from both apps

- `ChatDemoAdapter` (chat-app) — replaced by PushController + per-controller dataset parsing
- `channel-adapter.ts` (claudony) — same

---

## Space hierarchy

### Backend (qhorus — already exists)

- `Space`: id, name, description, parentSpaceId (nesting), tenancyId, createdAt
- `SpaceStore`: full CRUD + listByParent, listRoots, hasChildren
- `SpaceService`: create with nesting depth limits, rename, move, cycle detection
- `Channel.spaceId`: links channel to space
- `ChannelQuery.bySpaceId()` / `topLevel()`: query channels by space or root
- `ChannelDetail.spaceId` / `spaceName`: included in aggregation responses

### Push dataset change

`channels` dataset gains two columns: `spaceId`, `spaceName` (added to `QhorusDatasetBuilder`).

**Space rename/move propagation:** Space metadata itself has no push dataset (space operations are rare). When `SpaceService` processes a rename or move, it broadcasts a channel dataset replace for all channels in the affected space — this updates `spaceName` reactively without a dedicated spaces push topic.

### Frontend (ChannelStateController)

Builds `channelTree` from channels + spaces:

```ts
interface SpaceNode {
  space: Space;
  channels: QhorusChannel[];
  unreadCount: number;
  children: SpaceNode[];
}

interface ChannelTree {
  spaces: SpaceNode[];
  ungrouped: QhorusChannel[];
}
```

### Nav rendering

- `<channel-nav>` gets a `channelTree` property alongside existing flat `channels`
- Renders space groups as collapsible tree nodes with combined unread badges
- Root-level channels render at top level (no group)
- Claudony creates case spaces with normative triples (work/observe/oversight) — grouped under case space node
- Standalone chat-app shows flat channels by default, users can create spaces to organize

---

## Cross-repo impact summary

| Repo | Adds | Removes | Net |
|------|------|---------|-----|
| qhorus | SpaceResource (~50), ChannelResource enhancements (~250), qhorus-push module (~400 moved) | — | +700 |
| chat-app | qhorus-push dependency | ChatDatasetBuilder, ChatWebSocketBroadcaster, ChatPushWebSocket, PushInfrastructure (~400), CRUD endpoints (~200), ~600 lines from workbench | -1200 |
| claudony | qhorus-push dependency | ChannelEventBus, SSE endpoints, MeshResource channel ops (~350), channel-adapter.ts (~138), ~470 lines from workbench | -960 |
| blocks-ui | 6 composable controllers (~500) | — | +500 |

**Net effect:** ~960 fewer lines of duplicated code across the ecosystem.

## Design divergence from issue #33

Issue #33's body describes claudony depending on chat-app's composition layer. This spec rejects that direction (per decision review findings R1-02, R1-09) — an integration-tier app should not depend on another integration-tier app. Instead, reusable infrastructure is promoted to qhorus (foundation). The issue body should be updated when this spec lands.

## Decisions

See [decisions.md](decisions.md) for the full decision log (D1-D7).
