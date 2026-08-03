# casehub-chat-app -- Contributor Guide

> Internal architecture and extension points for platform builders working on chat-app.

**GitHub:** [casehubio/chat-app](https://github.com/casehubio/chat-app)

---

## Module Structure

Single-module Maven project (`casehub-chat-app`). Java backend and TypeScript frontend coexist in one deployable unit. The frontend is built by Quinoa (activated with `-Pui` profile) and served as static resources.

### Source Layout

```
src/main/java/io/casehub/chat/app/
    ChatResource.java                 -- REST endpoints (channels, messages, replies, reactions, members, presence, topics, commitments, correlation)
    ChatAppChannelBackend.java        -- HumanParticipatingChannelBackend implementation
    ChatWebSocket.java                -- WebSocket endpoint at /ws/chat
    ChatWebSocketBroadcaster.java     -- Connection management + dataset broadcast protocol
    ChatAppCurrentPrincipal.java      -- JWT-based CurrentPrincipal (replaces qhorus default)
    WebSocketTokenUpgradeCheck.java   -- JWT validation on WebSocket upgrade

src/main/webui/src/
    index.ts                          -- App entry point (registers panel, boots site)
    auth.ts                           -- JWT session management + ChatDemoLogin element
    identity-widget.ts                -- ChatDemoIdentity element (identity switcher)
    types.ts                          -- Shared types (CommitmentRecord re-export, ARTEFACT_SELECTED constant)
    workbench/
        qhorus-workbench.ts           -- App shell (responsive layout, dock strip, event routing, theme)
        chat-demo-adapter.ts          -- WebSocket protocol parser (7 datasets -> typed arrays)
        connection-controller.ts      -- WebSocket lifecycle with exponential backoff
        swipe-controller.ts           -- Edge-swipe drawer gestures (phone layout)
    panels/
        qhorus-task-panel.ts          -- Commitment/task list panel (overdue/active/completed groups)
        qhorus-correlation-panel.ts   -- Correlation chain visualisation panel
        qhorus-artifact-panel.ts      -- Artefact reference viewer with history navigation

src/main/resources/
    application.properties            -- Quarkus config (H2, JWT, Flyway, Quinoa)
    db/chatapp/seed/
        V1000__chat_app_seed.sql      -- 4 seed channels + default topics

src/test/java/io/casehub/chat/app/
    ChatResourceTest.java             -- 27 tests (REST endpoint integration tests via @QuarkusTest)
    ChatWebSocketTest.java            -- 12 tests (WebSocket protocol + broadcast tests)
    ChatAppCurrentPrincipalTest.java  -- 2 tests (principal extraction from SecurityIdentity)

src/main/webui/src/**/*.test.ts       -- 140 frontend tests (vitest + happy-dom/jsdom)
    auth.test.ts                      -- 8 tests
    workbench/chat-demo-adapter.test.ts   -- 39 tests
    workbench/qhorus-workbench.test.ts    -- 36 tests
    workbench/connection-controller.test.ts -- 20 tests
    workbench/swipe-controller.test.ts    -- 14 tests
    panels/qhorus-task-panel.test.ts      -- 9 tests
    panels/qhorus-correlation-panel.test.ts -- 8 tests
    panels/qhorus-artifact-panel.test.ts  -- 6 tests
```

## Internal Architecture

### Backend Data Flow

The backend delegates all persistence and domain logic to the qhorus runtime (H2/JPA). chat-app does not implement any storage directly -- it consumes qhorus-api interfaces:

- **ConsumerMessaging** -- message dispatch and history queries
- **ChannelManager / ChannelReader** -- channel CRUD and listing
- **MembershipManager / MembershipReader** -- join, leave, list members
- **PresenceTracker** -- heartbeat and channel presence
- **ReactionManager / ReactionReader** -- react, unreact, batch queries
- **TopicManager / TopicReader** -- create, rename, resolve, unresolve, merge topics
- **CommitmentReader** -- query commitments by channel

`ChatResource` orchestrates REST calls: it validates input, calls the appropriate qhorus SPI, then triggers WebSocket broadcasts via `ChatWebSocketBroadcaster`. Auto-membership and auto-presence are enforced -- posting a message auto-joins the channel and sets ONLINE presence if needed.

`ChatAppChannelBackend` integrates with the qhorus gateway pattern. It observes `ChannelInitialisedEvent` (fired by qhorus on startup for each channel) and registers itself with `BackendRegistry` as a `"human_participating"` backend. When qhorus routes an outbound message to this backend, `post()` delegates to the broadcaster's `pushMessage()` for WebSocket delivery. It also observes `CommitmentStateChangedEvent` (transactional, AFTER_SUCCESS phase) and broadcasts commitment updates.

### WebSocket Protocol

`ChatWebSocketBroadcaster` maintains a `CopyOnWriteArraySet<WebSocketConnection>` and an `AtomicLong` sequence counter. On connect, `buildSnapshot()` reads all current state from qhorus readers and constructs a JSON array of seven snapshot operations. Subsequent mutations broadcast individual append/replace/remove operations.

Each dataset defines its own column schema (static `List<Map<String, Object>>` constants). The protocol uses positional arrays (`rows: [[col0, col1, ...]]`) rather than named objects for wire efficiency. The adapter on the frontend reconstructs typed objects from column positions.

### Frontend Architecture

The frontend is a Lit-based single-page app bootstrapped via casehub-pages runtime:

1. **Boot sequence:** `index.ts` registers `"chat-workbench"` panel, calls `hostPanel()` with endpoint/restBase/identities config, waits for `pages-auth-success` event (or valid existing token), then calls `loadSite()` which mounts the workbench in the `#app` container with dark theme.

2. **Data flow:** `ConnectionController` manages WebSocket lifecycle. On message, raw JSON is parsed and passed to `ChatDemoAdapter.applyOp()`. The adapter updates typed arrays and notifies the workbench via `onChange` callback. The workbench re-renders, filtering data by selected channel/topic.

3. **Event routing:** Child components (`channel-feed`, `channel-nav`, `channel-input`, etc.) dispatch `pages-event` CustomEvents with topic/payload. `QhorusWorkbenchElement._onChatEvent` handles them: `SELECT_CHANNEL`, `SEND_MESSAGE`, `CREATE_CHANNEL`, `DELETE_CHANNEL`, `REACT`/`UNREACT`, `MESSAGE_SELECTED`, `ARTEFACT_SELECTED`, `SELECT_TOPIC`, `VIEW_MODE`, `RESOLVE_TOPIC`, `REOPEN_TOPIC`, `ARCHIVE_TOPIC`, `RENAME_TOPIC`, `MERGE_TOPIC`, `CREATE_TOPIC`.

4. **Responsive layout:** Three modes based on viewport width. Desktop: dock strip + independent left/right panels. Tablet: dock strip + unified sidebar with tab switcher. Phone: hamburger menu + slide-in drawers (left for nav, right for other panels) with swipe gesture support.

5. **Commitment visualisation:** `decorateCommitmentRanges()` from blocks-ui-commitment-viz computes range decorations from messages and commitments. The workbench passes a `renderContent` callback to `channel-feed` that renders `commitment-range-bar` inline at the start of each commitment range.

### Authentication

Dev-auth only. `ChatDemoLogin` calls `POST /dev/auth/login` (provided by casehub-pages-auth) with a name. The response JWT is stored in `sessionStorage` under `"pages-dev-auth-token"`. `authenticatedFetch()` attaches `Authorization: Bearer <token>` to all REST calls. `WebSocketTokenUpgradeCheck` validates the JWT from the `?token=` query parameter on WebSocket upgrade. On 401, `pages-auth-expired` is dispatched, clearing the session and re-showing the login overlay.

`ChatAppCurrentPrincipal` replaces the qhorus default (`QhorusInboundCurrentPrincipal` is excluded via `quarkus.arc.exclude-types`). It extracts `actorId` from `SecurityIdentity.getPrincipal().getName()`, roles from `getRoles()`, and tenant from the JWT `tenant_id` claim (defaulting to `"chat-app"`).

## Extension Points

### Adding a New REST Endpoint

Add the method to `ChatResource.java`. All endpoints follow the same pattern: parse path params, call qhorus SPI, broadcast via `ChatWebSocketBroadcaster`, return response. Create a record DTO in the same file for the request body.

### Adding a New Dataset

1. Add column definitions as a static `List<Map<String, Object>>` in `ChatWebSocketBroadcaster`
2. Add the dataset to `buildSnapshot()` in the broadcaster
3. Add broadcast methods (append/replace/remove) in the broadcaster
4. Add `_apply<Dataset>()` and `_to<Record>()` methods in `ChatDemoAdapter`
5. Add the dataset case to `ChatDemoAdapter.applyOp()`
6. Add state property and filtering in `QhorusWorkbenchElement`

### Adding a New Contextual Panel

1. Create `src/main/webui/src/panels/qhorus-<name>-panel.ts` as a `LitElement` with `@customElement`
2. Import and register it in `qhorus-workbench.ts` (void the element class for side-effect registration)
3. Add a `DockItem` entry to `QhorusWorkbenchElement.DOCK_ITEMS`
4. Add the panel's render case to `_renderPanel()`
5. Add `_dockState` default in the initial state

### Adding a New Channel Event

1. Define the event topic in blocks-ui-channel-activity's `ChannelEventTopics` (or as a local constant in `types.ts`)
2. Add the case to `QhorusWorkbenchElement._onChatEvent`
3. Implement the handler method (typically an `authenticatedFetch` call to a REST endpoint)

## Build System

### Maven

Single POM, parent is `casehub-parent` (0.2-SNAPSHOT). Build with:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install       # backend only
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -Pui   # with frontend
```

The `ui` profile activates `quarkus-quinoa` which installs Node, runs `npm install`, and builds the frontend via esbuild.

Frontend npm packages from casehub-pages and blocks-ui are delivered as Maven SNAPSHOT WebJars. The `maven-dependency-plugin` unpacks them to `src/main/webui/.casehub-packages/` during the `initialize` phase. Yarn `portal:` resolutions in `package.json` point to these unpacked directories.

### Frontend

```bash
cd src/main/webui
npx vitest run              # unit tests (140 tests, vitest + happy-dom/jsdom)
npx playwright test         # E2E tests
node esbuild.config.mjs     # production build -> dist/
npx vite                    # dev server with HMR
```

### CI/CD

Single workflow (`publish.yml`): triggers on push to main, PRs, `repository_dispatch` (upstream-published), and manual dispatch. Runs `mvn --batch-mode verify` with Java 21 on ubuntu-latest.

## Depended On By

None. This is a leaf application.

## Current State

Backend: 6 source files, 3 test files, 41 tests. Frontend: 10 source files, 8 test files, 140 tests. Total: 16 source files, 11 test files, 181 tests.

Migrated from `casehub-connectors/chat-demo` to a standalone repo. The original SQLite/HikariCP backend was replaced with the qhorus runtime (H2/JPA) in issue #22. Subsequent features added:

- **#4** Dockable contextual panels (task, correlation, artifact)
- **#6** Topic navigator with backend CRUD, seed data, and view mode switching
- **#8/#15** Connection lifecycle with exponential backoff and membership/presence model
- **#22** Migration from SqliteChatBackend to qhorus runtime with H2
- **#24** Commitment lifecycle visualisation (commitment-range-bar, commitment-state-pill integration)

### Open Issues

- **#21** Adopt casehub-pages-push typed protocol SDK
- **#18** Chat UI integration tests (topic sidebar, message grouping, reaction rendering)
- **#16** Scroll-to-new-messages pill
- **#13** Replace window.confirm/prompt with blocks-confirm-dialog
- **#12** Integrate blocks-ui-core accessibility mixins
- **#11** Add pages-data-request pipeline to composites
- **#10** Migrate workbench to split/dockBar layout
- **#9** Embed qhorus workbench in claudony for live channel observation
- **#7** Space-based channel hierarchy
- **#5** Rich artefact references with selection scope
- **#3** Responsive layout minor polish
- **#2** Touch-specific message interactions (long-press, swipe-to-reply)
