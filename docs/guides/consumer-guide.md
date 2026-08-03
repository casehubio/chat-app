# casehub-chat-app -- Consumer Guide

> Chat workbench application -- a runnable chat UI with qhorus runtime backend (H2 persistence), REST/WebSocket endpoints, and a casehub-pages frontend.

**GitHub:** [casehubio/chat-app](https://github.com/casehubio/chat-app)
**Tier:** Integration

---

## Purpose

Chat workbench application that provides the app shell (workbench layout, WebSocket adapter, connection lifecycle, swipe gestures, contextual panels, JWT dev-auth) around `@casehubio/blocks-ui-channel-activity` components and `@casehubio/blocks-ui-commitment-viz` visualisations. The backend uses the qhorus runtime with H2 in-memory persistence, replacing the earlier SQLite/HikariCP approach (migrated in issue #22).

This is NOT a connector or library. It is an Integration-tier application that wires together foundation components (qhorus-api, qhorus-runtime, pages-auth, blocks-ui-channel-activity, blocks-ui-commitment-viz) into a runnable chat experience.

## Key Abstractions

### Backend (Java)

- **ChatResource** -- JAX-RS REST endpoints at `/api` for channels, messages, replies, reactions, members, presence, read tracking, commitments, correlation chains, and topics (create, list, update, rename, merge)
- **ChatAppChannelBackend** -- Implements `HumanParticipatingChannelBackend` from the qhorus gateway; registers with `BackendRegistry` on channel initialisation, pushes outbound messages via `ChatWebSocketBroadcaster`, and observes `CommitmentStateChangedEvent` for real-time commitment updates
- **ChatWebSocket** -- WebSocket endpoint at `/ws/chat`; on open sends a full dataset snapshot, delegates incoming ops to the broadcaster
- **ChatWebSocketBroadcaster** -- Manages WebSocket connections (`CopyOnWriteArraySet`), builds dataset snapshots, and broadcasts dataset operations (snapshot/append/replace/remove) across seven datasets: channels, topics, messages, members, presence, reactions, commitments
- **ChatAppCurrentPrincipal** -- Implements `CurrentPrincipal` from platform-api; extracts identity from `SecurityIdentity`/JWT, defaults tenant to `"chat-app"`
- **WebSocketTokenUpgradeCheck** -- `HttpUpgradeCheck` that validates JWT token from the `?token=` query parameter before allowing WebSocket upgrade; rejects with 401 if missing or invalid

### Frontend (TypeScript/Lit)

- **QhorusWorkbenchElement** (`<qhorus-workbench>`) -- Lit element app shell with three responsive layout modes (desktop >= 1280px, tablet 768-1279px, phone < 768px); dock strip with five panels (Channels, Members, Tasks, Correlation, Artifacts); theme toggle (dark/light via pages-ui-tokens); topic bar and view mode switching (flat/threaded/topics)
- **ChatDemoAdapter** -- WebSocket protocol adapter that parses dataset operations (snapshot/append/replace/remove) across seven datasets into typed arrays (`QhorusChannel[]`, `QhorusTopic[]`, `QhorusMessage[]`, `Reaction[]`, `ChannelMember[]`, `PresenceState[]`, `Map<string, CommitmentRecord>`); computes reply counts from `inReplyTo` references; resolves topic names from topic IDs
- **ConnectionController** -- Lit reactive controller for WebSocket lifecycle: connect, disconnect, exponential backoff reconnection (1s initial, 30s max), state machine (disconnected/connecting/connected/reconnecting), non-retryable close codes (1000, 1001, 4401, 4403)
- **SwipeController** -- Lit reactive controller for edge-swipe drawer gestures on phone layout; configurable edge width, velocity/distance thresholds, `prefers-reduced-motion` support
- **ChatDemoLogin** (`<chat-demo-login>`) -- Dev authentication overlay; calls `POST /dev/auth/login` with a name, stores JWT in `sessionStorage`, dispatches `pages-auth-success` event
- **ChatDemoIdentity** (`<chat-demo-identity>`) -- Identity switcher widget with dropdown picker, filter-as-you-type, avatar display; switches identity by re-authenticating and reloading
- **QhorusTaskPanelElement** (`<qhorus-task-panel>`) -- Dockable panel showing obligation-creating messages grouped as Overdue/Active/Completed; renders `commitment-range-bar` and `commitment-state-pill` from blocks-ui-commitment-viz
- **QhorusCorrelationPanelElement** (`<qhorus-correlation-panel>`) -- Dockable panel showing the correlation chain (or reply chain) for a selected message as a vertical flow with speech-act badges, actor icons, duration connectors, and commitment state pills
- **QhorusArtifactPanelElement** (`<qhorus-artifact-panel>`) -- Dockable panel for viewing artefact references with back/forward history navigation, type icons (DOCUMENT, CODE, CASE, WORK_ITEM, etc.), scope highlighting, and URI copy

## REST API

All endpoints require JWT authentication (`@Authenticated`). Base path: `/api`.

### Channels

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `POST` | `/api/channels` | `{ name, description? }` | Channel object | Create channel; broadcasts append |
| `GET` | `/api/channels` | -- | `Channel[]` | List all channels |
| `DELETE` | `/api/channels/{channelId}` | -- | 204 | Delete channel; broadcasts remove |

### Messages

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `POST` | `/api/channels/{channelId}/messages` | `{ text, messageType?, actorType?, target?, artefactRefs?, topic?, topicId? }` | `{ ok, messageId, correlationId? }` | Post message; auto-joins channel; COMMAND type auto-generates correlationId |
| `GET` | `/api/channels/{channelId}/messages?since=N` | -- | `Message[]` | List messages, optionally after sequence N |

### Replies

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `POST` | `/api/channels/{channelId}/messages/{messageId}/replies` | `{ text, messageType?, actorType?, target?, artefactRefs? }` | `{ ok, messageId }` | Post reply; inherits parent's correlationId and topic |

### Reactions

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `POST` | `/api/channels/{channelId}/messages/{messageId}/reactions` | `{ emoji }` | 200 | Add reaction; broadcasts append |
| `DELETE` | `/api/channels/{channelId}/messages/{messageId}/reactions/{emoji}` | -- | 200 | Remove reaction; broadcasts remove |
| `GET` | `/api/channels/{channelId}/messages/{messageId}/reactions` | -- | `String[]` | List reaction emojis |

### Members

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `GET` | `/api/channels/{channelId}/members` | -- | `ChannelMembership[]` | List channel members |
| `POST` | `/api/channels/{channelId}/members` | `{ memberId }` | 200 | Add member; broadcasts append |
| `DELETE` | `/api/channels/{channelId}/members/{memberId}` | -- | 200 | Remove member; broadcasts remove |

### Presence

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `GET` | `/api/presence/{memberId}` | -- | `{ memberId, status }` | Get presence status |
| `PUT` | `/api/presence/{memberId}` | `{ status }` | 200 | Set presence (ONLINE/AWAY/OFFLINE/DND); broadcasts replace |

### Read Tracking

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `PUT` | `/api/channels/{channelId}/read` | `{ lastReadMessageId }` | 200 | Mark channel read up to message ID |

### Commitments

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `GET` | `/api/channels/{channelId}/commitments` | -- | `Commitment[]` | List commitments in channel |

### Correlation

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `GET` | `/api/channels/{channelId}/correlation/{correlationId}` | -- | `Message[]` | Get full correlation chain |

### Topics

| Method | Path | Request Body | Response | Description |
|--------|------|-------------|----------|-------------|
| `POST` | `/api/channels/{channelId}/topics` | `{ name }` | `{ id, name }` | Create topic; name max 100 chars; "General" reserved; broadcasts append |
| `GET` | `/api/channels/{channelId}/topics` | -- | `TopicSummary[]` | List topics with message counts and activity timestamps |
| `PUT` | `/api/channels/{channelId}/topics/{topicId}` | `{ name?, state? }` | `{ ok }` | Rename or resolve/unresolve topic; broadcasts replace |
| `POST` | `/api/channels/{channelId}/topics/{topicId}/merge` | `{ targetTopicId }` | `{ ok }` | Merge source topic into target; cannot merge "General"; broadcasts remove + replace |

## WebSocket Protocol

**Endpoint:** `ws://host:8090/ws/chat?token=JWT`

Authentication is via `?token=` query parameter, validated by `WebSocketTokenUpgradeCheck` before upgrade.

On connect, the server sends a JSON array of seven snapshot operations (one per dataset). Subsequent mutations send individual operations.

### Dataset Operations

Each operation is a JSON object:

```json
{ "dataset": "messages", "op": "snapshot", "seq": "1", "columns": [...], "rows": [[...], ...] }
{ "dataset": "messages", "op": "append", "seq": "5", "columns": [...], "rows": [[...]] }
{ "dataset": "presence", "op": "replace", "seq": "6", "columns": [...], "key": "alice", "row": [...] }
{ "dataset": "channels", "op": "remove", "seq": "7", "columns": [...], "key": "ch-uuid" }
```

### Datasets

| Dataset | Key | Columns |
|---------|-----|---------|
| `channels` | `id` (UUID) | id, name, topic, description, isPrivate |
| `topics` | `topicId` | topicId, channelId, name, state, messageCount, latestActivityTs, createdAt |
| `messages` | `messageId` | channelId, messageId, parentId, senderId, text, timestamp, messageType, actorType, topicId, correlationId, artefactRefs, target |
| `members` | `channelId:memberId` | membershipId, channelId, memberId, displayName, role |
| `presence` | `memberId` | memberId, status, lastActiveAt |
| `reactions` | `messageId:emoji` | messageId, emoji |
| `commitments` | `correlationId` | correlationId, channelId, state, deadline, acknowledgedAt, resolvedAt, createdAt |

## Dependencies

| Dependency | What chat-app uses |
|---|---|
| `casehub-qhorus-api` | Channel/message/topic/reaction/membership/presence/commitment APIs, gateway interfaces (`HumanParticipatingChannelBackend`, `BackendRegistry`, `ChannelInitialisedEvent`, `CommitmentStateChangedEvent`, `OutboundMessage`) |
| `casehub-qhorus` (runtime) | H2-backed JPA implementations of all qhorus SPIs; Flyway migrations for qhorus schema |
| `casehub-platform-api` | `CurrentPrincipal`, `ActorType` |
| `casehub-pages-auth` | JWT dev-auth (`/dev/auth/login` endpoint), SmallRye JWT integration |
| `casehub-pages-npm` | pages-runtime, pages-ui, pages-ui-tokens, pages-component, pages-data (Maven SNAPSHOT WebJar) |
| `casehub-blocks-ui-npm` | channel-activity components (feed, nav, member panel, input, topic bar), commitment-viz (range-bar, state-pill), blocks-ui-core (Maven SNAPSHOT WebJar) |

### Frontend Package Dependencies

| npm Package | Source | What it provides |
|---|---|---|
| `@casehubio/blocks-ui-channel-activity` | blocks-ui | ChannelFeedElement, ChannelNavElement, ChannelMemberPanelElement, ChannelInputElement, ChannelTopicBarElement, ChannelEventTopics, message type helpers |
| `@casehubio/blocks-ui-commitment-viz` | blocks-ui | commitment-range-bar, commitment-state-pill, range-decorator |
| `@casehubio/blocks-ui-core` | blocks-ui | emitPagesEvent, isTerminalCommitmentState |
| `@casehubio/pages-runtime` | pages | loadSite, registerPanel, createLocalLayoutStore |
| `@casehubio/pages-ui` | pages | hostPanel |
| `@casehubio/pages-ui-tokens` | pages | injectTheme, applyThemeMode, DEFAULT_THEME |
| `@casehubio/pages-component` | pages | DockItem, LayoutState types |
| `@casehubio/pages-data` | pages | data pipeline |
| `lit` | npm | LitElement, html, css, decorators |
| `dompurify` | npm | HTML sanitisation |
| `marked` | npm | Markdown rendering |
| `emoji-picker-element` | npm | Emoji picker |

## Configuration

Application properties (`application.properties`):

| Property | Value | Purpose |
|----------|-------|---------|
| `quarkus.http.port` | `8090` | HTTP server port |
| `quarkus.datasource.qhorus.db-kind` | `h2` | Qhorus persistence uses H2 in-memory |
| `quarkus.datasource.qhorus.jdbc.url` | `jdbc:h2:mem:chat-app;DB_CLOSE_DELAY=-1` | Shared in-memory DB |
| `quarkus.flyway.qhorus.locations` | `classpath:db/qhorus/migration,classpath:db/ledger/migration,classpath:db/chatapp/seed` | Schema migrations + seed data |
| `quarkus.arc.exclude-types` | `QhorusInboundCurrentPrincipal` | Replaced by ChatAppCurrentPrincipal (JWT-based) |
| `casehub.ledger.enabled` | `false` | No audit trail for demo app |
| `smallrye.jwt.new-token.issuer` | `casehub-dev` | JWT issuer for dev auth |
| `casehub.pages.auth.default-tenant` | `chat-app` | Default tenant ID |

### Seed Data

Four channels are seeded on startup: `general`, `engineering`, `design`, `random`. Each channel gets a default `general` topic.

## Running

```bash
# Backend only (Java tests)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# With frontend (Quinoa builds the webui)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -Pui

# Dev mode
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -Pui

# Frontend tests only (vitest + happy-dom/jsdom)
cd src/main/webui && npx vitest run

# Frontend E2E tests (Playwright)
cd src/main/webui && npx playwright test
```

The app is accessible at `http://localhost:8090`. The login overlay prompts for an identity (alice, bob, charlie, agent-alpha, agent-beta, agent-gamma).

## What It Does NOT Do

- Does not define the qhorus Channel/Message/Topic SPIs -- those are in `casehub-qhorus-api`
- Does not provide the qhorus runtime persistence -- that is `casehub-qhorus` (H2/JPA/Flyway)
- Does not provide reusable UI components -- those are in `casehub-blocks-ui` (channel-activity, commitment-viz)
- Does not provide production-grade persistence -- H2 in-memory is dev/demo only
- Does not send outbound messages to external platforms -- that is the connectors' job
- Does not implement the pages runtime or UI framework -- those are in `casehub-pages`
