# Consolidate Channel UI and Services — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #33 — consolidate channel UI and services — make chat-app reusable for claudony
**Issue group:** #33, #7 (space hierarchy)

**Goal:** Promote reusable channel infrastructure to qhorus foundation, extract composable Lit controllers to blocks-ui, and refactor both chat-app and claudony to consume them.

**Architecture:** Foundation-first: qhorus gains a push module (dataset wiring) + SpaceResource + ChannelResource enhancements. blocks-ui gains 6 composable Lit reactive controllers. Both apps shrink to thin layout shells over shared infrastructure. No app-to-app dependency.

**Tech Stack:** Java 21 / Quarkus 3.32.2, TypeScript / Lit 3, vitest, pages-push WebSocket protocol

## Global Constraints

- All casehubio artifacts are `0.2-SNAPSHOT`
- Quarkus version: `3.32.2` across all projects
- Java 21 source, Java 26 JVM: `JAVA_HOME=$(/usr/libexec/java_home -v 26)`
- Frontend packages use Maven SNAPSHOT WebJar pattern (ADR-0001)
- IntelliJ MCP required for all code navigation and refactoring
- qhorus ChannelResource already has `resolve(String idOrName)` — do not duplicate
- qhorus `toResponse(Channel)` already includes `messageCount`, `spaceName`
- ChatResource base path changes from `/api/channels` to `/api/chat` to avoid collision with qhorus ChannelResource
- Auth on qhorus ChannelResource: applied via `quarkus.http.auth.permission` in each app's config (qhorus resources have no auth annotations — foundation code is app-agnostic)
- Verify qhorus#328 (Space model) is landed before starting Phase 1 Task 2
- After blocks-ui frontend build, run `mvn install` on blocks-ui to publish SNAPSHOT WebJar to local Maven

## Prerequisites

Add qhorus and blocks-ui repos to the slot before starting:
```bash
work-slot add-repo qhorus
work-slot add-repo blocks-ui
```

Repos after setup:
- `$SLOT/chat-app` — `/Users/mdproctor/claude/casehub/slots/121/chat-app`
- `$SLOT/claudony` — `/Users/mdproctor/claude/casehub/slots/121/claudony`
- `$SLOT/qhorus` — slot clone (branch: `issue-33-consolidate-channel-ui`)
- `$SLOT/blocks-ui` — slot clone (branch: `issue-33-consolidate-channel-ui`)

---

## Phase 1: qhorus Foundation

### Task 1: Create qhorus-push module

Move push dataset wiring from chat-app into a new qhorus module. This is the foundation that both apps will depend on.

**Files:**
- Create: `$SLOT/qhorus/push/pom.xml`
- Create: `$SLOT/qhorus/push/src/main/java/io/casehub/qhorus/push/QhorusDatasetBuilder.java`
- Create: `$SLOT/qhorus/push/src/main/java/io/casehub/qhorus/push/QhorusWebSocketBroadcaster.java`
- Create: `$SLOT/qhorus/push/src/main/java/io/casehub/qhorus/push/QhorusPushWebSocket.java`
- Create: `$SLOT/qhorus/push/src/main/java/io/casehub/qhorus/push/QhorusPushInfrastructure.java`
- Create: `$SLOT/qhorus/push/src/test/java/io/casehub/qhorus/push/QhorusDatasetBuilderTest.java`
- Modify: `$SLOT/qhorus/pom.xml` (add `push` module)

**Interfaces:**
- Consumes: `io.casehub.qhorus.api.channel.ChannelReader`, `io.casehub.qhorus.api.message.ConsumerMessaging`, `io.casehub.qhorus.api.store.*` (all SPI interfaces from qhorus-api)
- Consumes: `io.casehub.pages.push.EventBroadcaster`, `io.casehub.pages.push.EventStore`, `io.casehub.pages.push.TopicRegistry`, `io.casehub.pages.push.PushMessage`, `io.casehub.pages.push.PushColumn` (from pages-push)
- Produces: `QhorusDatasetBuilder` — column definitions (`CHANNEL_COLUMNS`, `MESSAGE_COLUMNS`, etc.), `ALL_TOPICS` list, `buildSnapshot(String topic)`, `messageToRow(Message)`, `commitmentToRow(Commitment)`, `topicToRow(UUID, Topic)`, `outboundMessageToRow(ChannelRef, OutboundMessage)`
- Produces: `QhorusWebSocketBroadcaster` — `pushMessage(ChannelRef, OutboundMessage)`, `broadcastChannelAppend(Channel)`, `broadcastChannelRemove(UUID)`, `broadcastPresenceReplace(String, PresenceStatus)`, `broadcastMemberAppend(UUID, ChannelMembership)`, `broadcastMemberRemove(UUID, String)`, `broadcastReactionAppend(Long, String)`, `broadcastReactionRemove(Long, String)`, `broadcastCommitment(Commitment)`, `broadcastTopicAppend(UUID, Topic)`, `broadcastTopicReplace(UUID, Topic)`, `broadcastTopicRemove(UUID, Long)`
- Produces: `QhorusPushInfrastructure` — CDI producers for `EventStore`, `TopicRegistry`, `EventBroadcaster`; `registerConnection(String, WebSocketConnection)`, `removeConnection(String)`
- Produces: `QhorusPushWebSocket` — WebSocket endpoint at `/ws/push`

- [ ] **Step 1: Create module pom.xml**

```xml
<!-- $SLOT/qhorus/push/pom.xml -->
<project>
    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-qhorus-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>
    <artifactId>casehub-qhorus-push</artifactId>
    <name>casehub-qhorus-push</name>
    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-qhorus-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-pages-push</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-websockets-next</artifactId>
        </dependency>
    </dependencies>
</project>
```

Add `<module>push</module>` to parent pom.xml.

- [ ] **Step 2: Write QhorusDatasetBuilder test**

```java
@QuarkusTest
class QhorusDatasetBuilderTest {
    @Inject QhorusDatasetBuilder builder;

    @Test
    void channelColumnsIncludeSpaceFields() {
        var cols = QhorusDatasetBuilder.CHANNEL_COLUMNS.stream()
            .map(PushColumn::name).toList();
        assertTrue(cols.contains("spaceId"));
        assertTrue(cols.contains("spaceName"));
    }

    @Test
    void buildChannelSnapshotIncludesAllChannels() {
        // setup: create channels via SPI
        var snapshot = builder.buildSnapshot("chat:channels");
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("\"op\":\"snapshot\""));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl push -f $SLOT/qhorus/pom.xml`
Expected: compilation failure — QhorusDatasetBuilder doesn't exist yet

- [ ] **Step 4: Copy ChatDatasetBuilder from chat-app, rename and enhance**

Copy `$SLOT/chat-app/src/main/java/io/casehub/chat/app/ChatDatasetBuilder.java` to `$SLOT/qhorus/push/src/main/java/io/casehub/qhorus/push/QhorusDatasetBuilder.java`.

Changes:
- Package: `io.casehub.qhorus.push`
- Class name: `QhorusDatasetBuilder`
- Add `spaceId`, `spaceName`, and `parentSpaceId` to `CHANNEL_COLUMNS`:
  ```java
  public static final List<PushColumn> CHANNEL_COLUMNS = List.of(
      new PushColumn("id", "ID", "LABEL"),
      new PushColumn("name", "Name", "LABEL"),
      new PushColumn("topic", "Topic", "LABEL"),
      new PushColumn("description", "Description", "LABEL"),
      new PushColumn("isPrivate", "Private", "LABEL"),
      new PushColumn("spaceId", "Space ID", "LABEL"),
      new PushColumn("spaceName", "Space Name", "LABEL"),
      new PushColumn("parentSpaceId", "Parent Space", "LABEL"));
  ```
- Update `buildChannelSnapshot` to include spaceId/spaceName/parentSpaceId in each row (requires `SpaceStore` injection to resolve names and parent chain)

- [ ] **Step 5: Copy and rename remaining push classes**

Copy from chat-app, rename packages and class names:
- `ChatWebSocketBroadcaster` → `QhorusWebSocketBroadcaster`
- `ChatPushWebSocket` → `QhorusPushWebSocket`
- `PushInfrastructure` → `QhorusPushInfrastructure`

All in package `io.casehub.qhorus.push`. Update internal references.

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl push -f $SLOT/qhorus/pom.xml`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git -C $SLOT/qhorus add push/ pom.xml
git -C $SLOT/qhorus commit -m "feat(#33): create qhorus-push module with dataset builder and broadcaster

Move push infrastructure from chat-app into qhorus foundation.
QhorusDatasetBuilder, QhorusWebSocketBroadcaster, QhorusPushWebSocket,
QhorusPushInfrastructure. Channel dataset gains spaceId/spaceName columns.

Refs casehubio/chat-app#33"
```

---

### Task 2: SpaceResource in qhorus-runtime

**Files:**
- Create: `$SLOT/qhorus/runtime/src/main/java/io/casehub/qhorus/runtime/api/SpaceResource.java`
- Create: `$SLOT/qhorus/runtime/src/test/java/io/casehub/qhorus/runtime/api/SpaceResourceTest.java`

**Interfaces:**
- Consumes: `io.casehub.qhorus.runtime.channel.SpaceService` (existing — create, rename, move, delete, cycle detection)
- Consumes: `io.casehub.qhorus.api.store.SpaceStore` (existing — find, listRoots, listByParent)
- Consumes: `io.casehub.qhorus.api.channel.ChannelQuery` (existing — bySpaceId)
- Produces: REST endpoints at `/api/spaces` — list, get, create, update, delete, children, channels

- [ ] **Step 1: Write SpaceResource test**

```java
@QuarkusTest
class SpaceResourceTest {
    @Test
    void listRootsReturnsEmptyInitially() {
        given().when().get("/api/spaces")
            .then().statusCode(200).body("$.size()", is(0));
    }

    @Test
    void createAndGetSpace() {
        var body = Map.of("name", "test-space", "description", "A test space");
        var id = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/spaces")
            .then().statusCode(200).extract().path("id").toString();

        given().when().get("/api/spaces/" + id)
            .then().statusCode(200)
            .body("name", is("test-space"))
            .body("description", is("A test space"));
    }

    @Test
    void nestedSpacesAppearInChildren() {
        var parent = createSpace("parent", null);
        var child = createSpace("child", parent);

        given().when().get("/api/spaces/" + parent + "/children")
            .then().statusCode(200).body("$.size()", is(1))
            .body("[0].name", is("child"));
    }

    @Test
    void channelsInSpaceReturnsFilteredList() {
        var spaceId = createSpace("my-space", null);
        // create channel with spaceId via ChannelResource
        // verify GET /api/spaces/{id}/channels returns it
    }

    private String createSpace(String name, String parentId) {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", name);
        if (parentId != null) body.put("parentSpaceId", parentId);
        return given().contentType(ContentType.JSON).body(body)
            .when().post("/api/spaces")
            .then().statusCode(200).extract().path("id").toString();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime -Dtest=SpaceResourceTest -f $SLOT/qhorus/pom.xml`
Expected: 404 — endpoint doesn't exist

- [ ] **Step 3: Implement SpaceResource**

```java
package io.casehub.qhorus.runtime.api;

@Path("/api/spaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Blocking
@Transactional
public class SpaceResource {
    @Inject SpaceService spaceService;
    @Inject SpaceStore spaceStore;
    @Inject ChannelReader channelReader;

    @GET
    public List<Space> listRoots() {
        return spaceStore.listRoots();
    }

    @GET @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return spaceStore.find(UUID.fromString(id))
            .map(s -> Response.ok(s).build())
            .orElse(Response.status(404).build());
    }

    @GET @Path("/{id}/children")
    public List<Space> children(@PathParam("id") String id) {
        return spaceStore.listByParent(UUID.fromString(id));
    }

    @POST
    public Response create(SpaceCreateRequest request) {
        var space = spaceService.create(request.name(), request.description(),
            request.parentSpaceId());
        return Response.ok(space).build();
    }

    @PUT @Path("/{id}")
    public Response update(@PathParam("id") String id, SpaceUpdateRequest request) {
        var uuid = UUID.fromString(id);
        if (request.name() != null) spaceService.rename(uuid, request.name());
        if (request.description() != null) spaceStore.find(uuid).ifPresent(s ->
            spaceStore.put(new Space(s.id(), s.name(), request.description(),
                s.parentSpaceId(), s.tenancyId(), s.createdAt())));
        if (request.parentSpaceId() != null) spaceService.move(uuid, request.parentSpaceId());
        return Response.ok().build();
    }

    @DELETE @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        spaceService.delete(UUID.fromString(id));
        return Response.noContent().build();
    }

    @GET @Path("/{id}/channels")
    public List<Channel> channelsInSpace(@PathParam("id") String id) {
        return channelService.scan(ChannelQuery.builder().spaceId(UUID.fromString(id)).build());
    }

    record SpaceCreateRequest(String name, String description, UUID parentSpaceId) {}
    record SpaceUpdateRequest(String name, String description, UUID parentSpaceId) {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime -Dtest=SpaceResourceTest -f $SLOT/qhorus/pom.xml`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git -C $SLOT/qhorus add runtime/
git -C $SLOT/qhorus commit -m "feat(#33): add SpaceResource REST endpoints

CRUD for spaces at /api/spaces with nesting support via parentSpaceId.
Delegates to existing SpaceService/SpaceStore.

Refs casehubio/chat-app#33"
```

---

### Task 3: ChannelResource enhancements

Add aggregation endpoints and migrate CRUD from chat-app ChatResource and claudony MeshResource.

**Files:**
- Modify: `$SLOT/qhorus/runtime/src/main/java/io/casehub/qhorus/runtime/api/ChannelResource.java`
- Modify: `$SLOT/qhorus/runtime/src/test/java/io/casehub/qhorus/runtime/api/ChannelResourceTest.java`

**Interfaces:**
- Consumes: `QhorusDashboardService.listChannels()`, `QhorusDashboardService.getFeed()`, `QhorusDashboardService.getTimeline()`
- Consumes: `ReactionManager`, `TopicManager`, `MembershipManager`, `PresenceTracker`, `CommitmentReader` (all existing qhorus SPIs)
- Produces: New endpoints on existing `/api/channels` resource:
  - `GET /api/channels/feed` — cross-channel feed
  - `GET /api/channels/{id}/timeline` — per-channel timeline
  - `POST /api/channels/{id}/reactions/batch` — batch reactions
  - `POST/DELETE/GET /api/channels/{id}/messages/{msgId}/reactions` — reaction CRUD
  - `POST/GET/PUT /api/channels/{id}/topics` — topic CRUD
  - `POST /api/channels/{id}/topics/{topicId}/merge` — topic merge
  - `GET/POST/DELETE /api/channels/{id}/members` — member CRUD
  - `GET/PUT /api/channels/{id}/presence` — presence
  - `GET /api/channels/{id}/commitments` — commitments
  - `GET /api/channels/{id}/correlation/{correlationId}` — correlation chain

- [ ] **Step 1: Write tests for new aggregation endpoints**

```java
@Test
void feedReturnsRecentMessagesAcrossChannels() {
    given().when().get("/api/channels/feed")
        .then().statusCode(200);
}

@Test
void timelineReturnsChannelMessages() {
    // create channel, post message, verify timeline
    given().when().get("/api/channels/" + channelId + "/timeline")
        .then().statusCode(200);
}

@Test
void reactionCrudWorks() {
    // post message, add reaction, list, remove
    given().contentType(ContentType.JSON).body(Map.of("emoji", "👍"))
        .when().post("/api/channels/" + chId + "/messages/" + msgId + "/reactions")
        .then().statusCode(200);
    given().when().get("/api/channels/" + chId + "/messages/" + msgId + "/reactions")
        .then().statusCode(200).body("$", hasItem("👍"));
}

@Test
void topicCrudWorks() {
    given().contentType(ContentType.JSON).body(Map.of("name", "design"))
        .when().post("/api/channels/" + chId + "/topics")
        .then().statusCode(200).body("name", is("design"));
}

@Test
void memberJoinAndLeave() {
    given().contentType(ContentType.JSON).body(Map.of("memberId", "alice"))
        .when().post("/api/channels/" + chId + "/members")
        .then().statusCode(200);
    given().when().get("/api/channels/" + chId + "/members")
        .then().statusCode(200).body("$.size()", greaterThan(0));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime -Dtest=ChannelResourceTest -f $SLOT/qhorus/pom.xml`
Expected: 404/405 for new endpoints

- [ ] **Step 3: Add aggregation endpoints to ChannelResource**

Inject `QhorusDashboardService`, `ReactionManager`, `ReactionReader`, `TopicManager`, `TopicReader`, `MembershipManager`, `MembershipReader`, `PresenceTracker`, `CommitmentReader`, `ConsumerMessaging`.

Add methods mirroring chat-app's ChatResource (lines 79-398) but using the existing `resolve()` method for channel lookup. Key pattern:

```java
@GET @Path("/feed")
public List<Map<String, Object>> feed(@QueryParam("limit") @DefaultValue("50") int limit) {
    return dashboard.getFeed(limit);
}

@GET @Path("/{id}/timeline")
public List<Map<String, Object>> timeline(@PathParam("id") String id,
        @QueryParam("after") Long after, @QueryParam("limit") Integer limit) {
    var channel = resolve(id);
    return dashboard.getTimeline(channel.name(),
        after != null ? after : 0, limit != null ? limit : 100);
}

@POST @Path("/{id}/messages/{messageId}/reactions")
public Response addReaction(@PathParam("id") String id,
        @PathParam("messageId") String messageId, ReactionRequest request) {
    reactions.react(Long.parseLong(messageId), request.emoji());
    return Response.ok().build();
}
// ... remaining endpoints following same pattern
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime -Dtest=ChannelResourceTest -f $SLOT/qhorus/pom.xml`
Expected: PASS

- [ ] **Step 5: Run full qhorus build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -f $SLOT/qhorus/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git -C $SLOT/qhorus add runtime/
git -C $SLOT/qhorus commit -m "feat(#33): add channel aggregation, CRUD endpoints to ChannelResource

Feed, timeline, reactions, topics, members, presence, commitments,
correlation chain. Migrates operations from chat-app ChatResource
and claudony MeshResource into qhorus foundation.

Refs casehubio/chat-app#33"
```

---

## Phase 2: blocks-ui Controllers

### Task 4: PushController + ChannelStateController

The core controller pair. PushController manages WebSocket connection. ChannelStateController manages channel/space state and exposes channelTree.

**Files:**
- Modify: `$SLOT/blocks-ui/components/channel-activity/src/types.ts` (add spaceId/spaceName to QhorusChannel)
- Create: `$SLOT/blocks-ui/components/channel-activity/src/push-controller.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/push-controller.test.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/channel-state-controller.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/channel-state-controller.test.ts`
- Modify: `$SLOT/blocks-ui/components/channel-activity/src/index.ts` (export new controllers)

**Interfaces:**
- Consumes: `createEventConnection` from `@casehubio/pages-data`
- Consumes: `ChannelEventTopics` from `events.ts`
- Produces: `PushController` — `constructor(host, config: {pushUrl, tokenProvider})`, `connectionStatus`, `listen(topics)`, `close()`, `registerDatasetHandler(dataset, handler)`
- Produces: `ChannelStateController` — `constructor(host, push)`, `channels`, `topics`, `channelTree`, `selectedChannelId`, `viewMode`, `filteredMessages()`, `channelTopics()`, `handleEvent(topic, payload)`
- Produces: `ChannelTree`, `SpaceNode` types
- Produces: `Space` type: `{id, name, description, parentSpaceId}`

- [ ] **Step 1: Add space fields to QhorusChannel**

In `types.ts`:
```ts
export interface QhorusChannel {
  id: string;
  name: string;
  description?: string;
  semantic: string;
  paused: boolean;
  spaceId?: string;         // NEW
  spaceName?: string;       // NEW
  parentSpaceId?: string;   // NEW — for nested space hierarchy
}
```

- [ ] **Step 2: Write PushController test**

```ts
// push-controller.test.ts
import { PushController } from './push-controller.js';

class MockHost {
  addController() {}
  removeController() {}
  requestUpdate() {}
}

describe('PushController', () => {
  it('exposes disconnected status initially', () => {
    const ctrl = new PushController(new MockHost() as any, {
      pushUrl: 'ws://localhost/ws/push',
      tokenProvider: () => 'test-token',
    });
    expect(ctrl.connectionStatus).toBe('disconnected');
  });

  it('dispatches dataset ops to registered handlers', () => {
    const ctrl = new PushController(new MockHost() as any, {
      pushUrl: 'ws://localhost/ws/push',
      tokenProvider: () => 'test-token',
    });
    const received: any[] = [];
    ctrl.registerDatasetHandler('channels', (op) => received.push(op));
    ctrl.applyOp({ op: 'snapshot', dataset: 'channels', rows: [] });
    expect(received).toHaveLength(1);
    expect(received[0].op).toBe('snapshot');
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd $SLOT/blocks-ui/components/channel-activity && npx vitest run src/push-controller.test.ts`
Expected: FAIL — module not found

- [ ] **Step 4: Implement PushController**

```ts
// push-controller.ts
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { createEventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import type { EventConnection, ConnectionStatus } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';

export interface PushConfig {
  pushUrl: string;
  tokenProvider: () => string | null;
}

interface DatasetOp {
  op: 'snapshot' | 'append' | 'replace' | 'remove';
  dataset: string;
  rows?: unknown[][];
  row?: unknown[];
  key?: string;
}

type DatasetHandler = (op: DatasetOp) => void;

export const ALL_TOPICS = [
  'chat:channels', 'chat:topics', 'chat:messages',
  'chat:members', 'chat:presence', 'chat:reactions', 'chat:commitments',
];

export class PushController implements ReactiveController {
  connectionStatus: ConnectionStatus = 'disconnected';
  private _conn?: EventConnection;
  private _handlers = new Map<string, DatasetHandler[]>();
  private _host: ReactiveControllerHost;
  private _config: PushConfig;

  constructor(host: ReactiveControllerHost, config: PushConfig) {
    this._host = host;
    this._config = config;
    host.addController(this);
  }

  registerDatasetHandler(dataset: string, handler: DatasetHandler) {
    const list = this._handlers.get(dataset) ?? [];
    list.push(handler);
    this._handlers.set(dataset, list);
  }

  applyOp(op: DatasetOp) {
    const handlers = this._handlers.get(op.dataset);
    if (handlers) for (const h of handlers) h(op);
  }

  hostConnected() {
    const token = this._config.tokenProvider();
    if (!token) return;
    const eventTarget = new EventTarget();
    this._conn = createEventConnection(this._config.pushUrl, {
      config: { eventTarget },
      onStatusChange: (status) => {
        this.connectionStatus = status;
        this._host.requestUpdate();
      },
    });
    this._conn.listen(ALL_TOPICS);
    eventTarget.addEventListener('pages-event', (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.payload) this.applyOp(detail.payload as DatasetOp);
    });
  }

  hostDisconnected() {
    this._conn?.close();
  }

  close() { this._conn?.close(); }
}
```

- [ ] **Step 5: Write ChannelStateController test**

```ts
// channel-state-controller.test.ts
describe('ChannelStateController', () => {
  it('builds channelTree with spaces and ungrouped', () => {
    const push = new MockPush();
    const ctrl = new ChannelStateController(new MockHost() as any, push as any);
    ctrl.applyChannelOp({
      op: 'snapshot', dataset: 'channels',
      rows: [
        ['ch-1', 'general', '', '', 'false', '', ''],
        ['ch-2', 'work', '', '', 'false', 'sp-1', 'Project Alpha'],
        ['ch-3', 'observe', '', '', 'false', 'sp-1', 'Project Alpha'],
      ],
    });
    const tree = ctrl.channelTree;
    expect(tree.ungrouped).toHaveLength(1);
    expect(tree.ungrouped[0].name).toBe('general');
    expect(tree.spaces).toHaveLength(1);
    expect(tree.spaces[0].space.name).toBe('Project Alpha');
    expect(tree.spaces[0].channels).toHaveLength(2);
  });

  it('selects channel and filters messages', () => {
    const ctrl = setupWithData();
    ctrl.handleEvent('select-channel', { channelId: 'ch-1' });
    expect(ctrl.selectedChannelId).toBe('ch-1');
    expect(ctrl.filteredMessages().every(m => m.channelId === 'ch-1')).toBe(true);
  });
});
```

- [ ] **Step 6: Implement ChannelStateController**

```ts
// channel-state-controller.ts
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import type { PushController } from './push-controller.js';
import type { QhorusChannel, QhorusMessage, QhorusTopic } from './types.js';
import { ChannelEventTopics } from './events.js';

export interface Space {
  id: string;
  name: string;
  description?: string;
  parentSpaceId?: string;
}

export interface SpaceNode {
  space: Space;
  channels: QhorusChannel[];
  unreadCount: number;
  children: SpaceNode[];
}

export interface ChannelTree {
  spaces: SpaceNode[];
  ungrouped: QhorusChannel[];
}

export class ChannelStateController implements ReactiveController {
  channels: QhorusChannel[] = [];
  topics: QhorusTopic[] = [];
  messages: QhorusMessage[] = [];
  selectedChannelId = '';
  viewMode: 'flat' | 'threaded' | 'topics' = 'flat';
  private _selectedTopicId: string | null = null;
  private _host: ReactiveControllerHost;

  constructor(host: ReactiveControllerHost, push: PushController) {
    this._host = host;
    host.addController(this);
    push.registerDatasetHandler('channels', (op) => { this.applyChannelOp(op); this._host.requestUpdate(); });
    push.registerDatasetHandler('topics', (op) => { this._applyTopics(op); this._host.requestUpdate(); });
    push.registerDatasetHandler('messages', (op) => { this._applyMessages(op); this._host.requestUpdate(); });
  }

  get channelTree(): ChannelTree {
    const spaceMap = new Map<string, SpaceNode>();
    const ungrouped: QhorusChannel[] = [];
    for (const ch of this.channels) {
      if (ch.spaceId && ch.spaceName) {
        let node = spaceMap.get(ch.spaceId);
        if (!node) {
          node = { space: { id: ch.spaceId, name: ch.spaceName, parentSpaceId: ch.parentSpaceId }, channels: [], unreadCount: 0, children: [] };
          spaceMap.set(ch.spaceId, node);
        }
        node.channels.push(ch);
      } else {
        ungrouped.push(ch);
      }
    }
    // Build nested hierarchy from parentSpaceId
    const roots: SpaceNode[] = [];
    for (const node of spaceMap.values()) {
      const parentId = node.space.parentSpaceId;
      if (parentId && spaceMap.has(parentId)) {
        spaceMap.get(parentId)!.children.push(node);
      } else {
        roots.push(node);
      }
    }
    return { spaces: roots, ungrouped };
  }

  filteredMessages(): QhorusMessage[] {
    if (!this.selectedChannelId) return [];
    let msgs = this.messages.filter(m => m.channelId === this.selectedChannelId);
    if (this._selectedTopicId) msgs = msgs.filter(m => m.topicId === this._selectedTopicId);
    return msgs;
  }

  channelTopics(): QhorusTopic[] {
    if (!this.selectedChannelId) return [];
    return this.topics.filter(t => t.channelId === this.selectedChannelId && t.state !== 'MERGED');
  }

  handleEvent(topic: string, payload: unknown) {
    switch (topic) {
      case ChannelEventTopics.SELECT_CHANNEL:
        this.selectedChannelId = (payload as { channelId: string }).channelId;
        this._selectedTopicId = null;
        this._host.requestUpdate();
        break;
      case ChannelEventTopics.VIEW_MODE:
        this.viewMode = (payload as { mode: 'flat' | 'threaded' | 'topics' }).mode;
        this._host.requestUpdate();
        break;
      case ChannelEventTopics.SELECT_TOPIC:
        this._selectedTopicId = (payload as { topicId: string }).topicId;
        this._host.requestUpdate();
        break;
    }
  }

  applyChannelOp(op: any) { /* snapshot/append/remove — same logic as ChatDemoAdapter._applyChannels but with spaceId/spaceName */ }
  private _applyTopics(op: any) { /* same as ChatDemoAdapter._applyTopics */ }
  private _applyMessages(op: any) { /* same as ChatDemoAdapter._applyMessages + recomputeReplyCounts */ }

  hostConnected() {}
  hostDisconnected() {}
}
```

- [ ] **Step 7: Run tests, verify pass**

Run: `cd $SLOT/blocks-ui/components/channel-activity && npx vitest run src/push-controller.test.ts src/channel-state-controller.test.ts`
Expected: PASS

- [ ] **Step 8: Export from index.ts and commit**

Add exports to `index.ts`:
```ts
export { PushController } from './push-controller.js';
export type { PushConfig } from './push-controller.js';
export { ChannelStateController } from './channel-state-controller.js';
export type { Space, SpaceNode, ChannelTree } from './channel-state-controller.js';
```

```bash
git -C $SLOT/blocks-ui add components/channel-activity/
git -C $SLOT/blocks-ui commit -m "feat(#33): add PushController and ChannelStateController

Composable Lit reactive controllers for WebSocket push connection
and channel/space state management with tree grouping.

Refs casehubio/chat-app#33"
```

---

### Task 5: MessagingController + MembershipController + ReactionController + CommitmentController

The remaining four controllers. Each is focused and small.

**Files:**
- Create: `$SLOT/blocks-ui/components/channel-activity/src/messaging-controller.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/messaging-controller.test.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/membership-controller.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/membership-controller.test.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/reaction-controller.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/reaction-controller.test.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/commitment-controller.ts`
- Create: `$SLOT/blocks-ui/components/channel-activity/src/commitment-controller.test.ts`
- Modify: `$SLOT/blocks-ui/components/channel-activity/src/index.ts`

**Interfaces:**
- Consumes: `PushController`, `ChannelStateController` (from Task 4)
- Produces: `MessagingController` — `constructor(host, push, channelState, config)`, `replyTo`, `handleEvent(topic, payload)`, REST methods for send/reply/channel CRUD/topic CRUD
- Produces: `MembershipController` — `constructor(host, push, channelState)`, `members`, `presence`, `filteredMembers()`
- Produces: `ReactionController` — `constructor(host, push, channelState)`, `reactions`, `filteredReactions()`, `handleEvent(topic, payload)`
- Produces: `CommitmentController` — `constructor(host, push, channelState)`, `commitments`, `commitmentDecorations`, `selectedMessageId`, `handleEvent(topic, payload)`

- [ ] **Step 1: Write tests for all four controllers**

Each controller test follows the same pattern: create with mock push/channelState, apply dataset ops, verify state. Key tests:

```ts
// messaging-controller.test.ts
describe('MessagingController', () => {
  it('sends message via REST', async () => {
    const ctrl = new MessagingController(host, push, channelState, {
      restBase: '/api', tokenProvider: () => 'tok',
    });
    // mock fetch, verify POST to /api/channels/{id}/messages
  });
  it('manages reply state', () => {
    ctrl.handleEvent(ChannelEventTopics.MESSAGE_SELECTED, { message: mockMsg });
    expect(ctrl.replyTo).toBeDefined();
  });
});

// membership-controller.test.ts
describe('MembershipController', () => {
  it('filters members by selected channel', () => {
    // apply snapshot with members across channels
    // verify filteredMembers returns only selected channel's members
  });
});

// reaction-controller.test.ts
describe('ReactionController', () => {
  it('handles reaction append and remove', () => {
    ctrl.applyOp({ op: 'append', dataset: 'reactions', rows: [['msg-1', '👍']] });
    expect(ctrl.reactions).toHaveLength(1);
    ctrl.applyOp({ op: 'remove', dataset: 'reactions', key: 'msg-1:👍' });
    expect(ctrl.reactions).toHaveLength(0);
  });
});

// commitment-controller.test.ts
describe('CommitmentController', () => {
  it('parses commitment rows into CommitmentRecord map', () => {
    ctrl.applyOp({ op: 'snapshot', dataset: 'commitments',
      rows: [['corr-1', 'ch-1', 'PENDING', '', '', '', '2026-01-01T00:00:00Z']] });
    expect(ctrl.commitments.get('corr-1')?.state).toBe('PENDING');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd $SLOT/blocks-ui/components/channel-activity && npx vitest run`
Expected: FAIL — modules not found

- [ ] **Step 3: Implement all four controllers**

Each follows the same pattern as ChannelStateController: implements `ReactiveController`, takes host + push + channelState, registers dataset handlers, exposes typed state.

MessagingController additionally takes `config: { restBase, tokenProvider }` for REST calls. Its `handleEvent` handles SEND_MESSAGE, CREATE_CHANNEL, DELETE_CHANNEL, and all topic events by making `fetch()` calls to the qhorus ChannelResource endpoints.

MembershipController handles `members` and `presence` datasets.
ReactionController handles `reactions` dataset.
CommitmentController handles `commitments` dataset and computes `commitmentDecorations` via `decorateCommitmentRanges`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd $SLOT/blocks-ui/components/channel-activity && npx vitest run`
Expected: PASS

- [ ] **Step 5: Export from index.ts, build, commit**

```bash
cd $SLOT/blocks-ui/components/channel-activity && npx tsc -p tsconfig.build.json
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn install -f $SLOT/blocks-ui/pom.xml
git -C $SLOT/blocks-ui add components/channel-activity/
git -C $SLOT/blocks-ui commit -m "feat(#33): add Messaging, Membership, Reaction, Commitment controllers

Complete composable controller set for channel data management.
Each controller is independently consumable by host elements.

Refs casehubio/chat-app#33"
```

---

## Phase 3: chat-app Refactoring

### Task 6: chat-app backend — switch to qhorus-push

**Files:**
- Modify: `$SLOT/chat-app/pom.xml` (add qhorus-push dep)
- Delete: `$SLOT/chat-app/src/main/java/io/casehub/chat/app/ChatDatasetBuilder.java`
- Delete: `$SLOT/chat-app/src/main/java/io/casehub/chat/app/ChatWebSocketBroadcaster.java`
- Delete: `$SLOT/chat-app/src/main/java/io/casehub/chat/app/ChatPushWebSocket.java`
- Delete: `$SLOT/chat-app/src/main/java/io/casehub/chat/app/PushInfrastructure.java`
- Modify: `$SLOT/chat-app/src/main/java/io/casehub/chat/app/ChatResource.java` (strip migrated endpoints)
- Modify: `$SLOT/chat-app/src/main/java/io/casehub/chat/app/ChatAppChannelBackend.java` (use QhorusWebSocketBroadcaster)
- Modify: `$SLOT/chat-app/src/test/java/io/casehub/chat/app/ChatResourceTest.java`

**Interfaces:**
- Consumes: `io.casehub.qhorus.push.QhorusWebSocketBroadcaster` (from Task 1)
- Produces: Slimmed `ChatResource` — message posting, replies, read tracking only

- [ ] **Step 1: Add qhorus-push to pom.xml**

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-qhorus-push</artifactId>
</dependency>
```

- [ ] **Step 2: Update ChatAppChannelBackend to use QhorusWebSocketBroadcaster**

Change import from `ChatWebSocketBroadcaster` to `QhorusWebSocketBroadcaster`. The API is identical — just a package/name change.

- [ ] **Step 3: Change ChatResource base path and strip migrated endpoints**

Change `@Path("/api/channels")` to `@Path("/api/chat")` to avoid collision with qhorus ChannelResource.

Remove: reaction endpoints (lines 178-205), member endpoints (lines 209-233), topic endpoints (lines 266-359), commitment endpoint (lines 249-252), correlation endpoint (lines 257-262). These are now in qhorus ChannelResource.

Keep: `postMessage` at `/chat/channels/{channelId}/messages`, `listMessages`, `postReply`, `markRead` at `/chat/channels/{channelId}/read`, and private helpers (`ensureMembership`, `ensurePresence`, `resolveTopicName`, `parseArtefactRefs`).

Update `ChatResource` to inject `QhorusWebSocketBroadcaster` instead of `ChatWebSocketBroadcaster`.

Update frontend controllers' `restBase` config: messaging controller uses `/api/chat` for message posting, other controllers use `/api` (qhorus ChannelResource).

- [ ] **Step 4: Delete local push infrastructure files**

Use `ide_refactor_safe_delete` for each:
- `ChatDatasetBuilder.java`
- `ChatWebSocketBroadcaster.java`
- `ChatPushWebSocket.java`
- `PushInfrastructure.java`

- [ ] **Step 5: Update tests**

Remove tests for deleted endpoints. Update remaining tests to use qhorus-push classes. Verify reactions/topics/members/presence are served by qhorus ChannelResource (auto-mounted via classpath).

- [ ] **Step 6: Build and verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -f $SLOT/chat-app/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git -C $SLOT/chat-app add -A
git -C $SLOT/chat-app commit -m "refactor(#33): switch to qhorus-push, strip migrated endpoints

Delete ChatDatasetBuilder, ChatWebSocketBroadcaster, ChatPushWebSocket,
PushInfrastructure — now in casehub-qhorus-push. Strip ChatResource to
message posting, replies, and read tracking. Channel CRUD, reactions,
topics, members, presence, commitments now served by qhorus ChannelResource.

Refs #33"
```

---

### Task 7: chat-app frontend — refactor workbench

**Files:**
- Modify: `$SLOT/chat-app/src/main/webui/src/workbench/qhorus-workbench.ts` (~800 → ~200 lines)
- Delete: `$SLOT/chat-app/src/main/webui/src/workbench/chat-demo-adapter.ts`
- Modify: `$SLOT/chat-app/src/main/webui/src/workbench/qhorus-workbench.test.ts`
- Delete: `$SLOT/chat-app/src/main/webui/src/workbench/chat-demo-adapter.test.ts`
- Modify: `$SLOT/chat-app/src/main/webui/package.json` (if blocks-ui version bump needed)

**Interfaces:**
- Consumes: All 6 controllers from blocks-ui (Task 4, 5)
- Produces: Slimmed `QhorusWorkbenchElement` — layout shell only

- [ ] **Step 1: Update blocks-ui dependency version if needed**

Ensure `@casehubio/agency-ui-channel-activity` version includes the new controllers.

- [ ] **Step 2: Rewrite QhorusWorkbenchElement to use controllers**

Replace the ~600 lines of data management, event handling, and REST calls with controller composition:

```ts
import { PushController, ChannelStateController, MessagingController,
  MembershipController, ReactionController, CommitmentController } from '@casehubio/agency-ui-channel-activity';

@customElement('qhorus-workbench')
export class QhorusWorkbenchElement extends LitElement {
  @property({ type: String }) endpoint = '';
  @property({ type: String }) restBase = '/api';
  @property({ type: String }) identities = '';

  private _push!: PushController;
  private _channels!: ChannelStateController;
  private _messaging!: MessagingController;
  private _members!: MembershipController;
  private _reactions!: ReactionController;
  private _commitments!: CommitmentController;

  // Layout state — unchanged from current
  private _layoutStore = createLocalLayoutStore('qhorus-workbench:');
  @state() private _layoutState: LayoutState = { /* same */ };
  @state() private _mode: LayoutMode = 'desktop';
  // ... swipe, drawers, theme — all unchanged

  constructor() {
    super();
    const config = {
      pushUrl: '', // set in connectedCallback from this.endpoint
      restBase: '/api',
      tokenProvider: getToken,
    };
    this._push = new PushController(this, { pushUrl: '', tokenProvider: getToken });
    this._channels = new ChannelStateController(this, this._push);
    this._messaging = new MessagingController(this, this._push, this._channels, config);
    this._members = new MembershipController(this, this._push, this._channels);
    this._reactions = new ReactionController(this, this._push, this._channels);
    this._commitments = new CommitmentController(this, this._push, this._channels);
  }

  private _onChatEvent = (e: CustomEvent) => {
    const { topic, payload } = e.detail;
    this._channels.handleEvent(topic, payload);
    this._messaging.handleEvent(topic, payload);
    this._reactions.handleEvent(topic, payload);
    this._commitments.handleEvent(topic, payload);
    // app-specific: artifact panel, drawer close on channel select
    if (topic === ChannelEventTopics.SELECT_CHANNEL && this._mode === 'phone') {
      this._drawerOpen = null;
    }
  };

  // Render methods use controller state instead of local state:
  // this._channels.channels, this._channels.filteredMessages(),
  // this._members.filteredMembers(), this._reactions.filteredReactions(),
  // this._commitments.commitments, this._push.connectionStatus
  // Layout code (~200 lines) stays unchanged.
}
```

- [ ] **Step 3: Delete ChatDemoAdapter**

Remove `chat-demo-adapter.ts` and `chat-demo-adapter.test.ts`.

- [ ] **Step 4: Update workbench tests**

Tests should verify controller integration — that the workbench creates controllers and forwards events.

- [ ] **Step 5: Run frontend tests**

Run: `cd $SLOT/chat-app/src/main/webui && npx vitest run`
Expected: PASS

- [ ] **Step 6: Full build with UI**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -Pui -f $SLOT/chat-app/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git -C $SLOT/chat-app add -A
git -C $SLOT/chat-app commit -m "refactor(#33): workbench uses composable controllers from blocks-ui

QhorusWorkbenchElement shrinks from ~800 to ~200 lines. Data management,
event routing, and REST calls replaced by PushController,
ChannelStateController, MessagingController, MembershipController,
ReactionController, CommitmentController. ChatDemoAdapter deleted.

Refs #33"
```

---

## Phase 4: claudony Refactoring

### Task 8: claudony backend — switch to qhorus-push

**Files:**
- Modify: `$SLOT/claudony/app/pom.xml` (add qhorus-push dep)
- Delete: `$SLOT/claudony/app/src/main/java/.../server/ChannelEventBus.java`
- Modify: `$SLOT/claudony/app/src/main/java/.../server/MeshResource.java` (strip channel ops, keep instances/config/interjection)
- Modify: `$SLOT/claudony/app/src/main/java/.../server/ClaudonyChannelBackend.java` (use QhorusWebSocketBroadcaster)

**Interfaces:**
- Consumes: `io.casehub.qhorus.push.QhorusWebSocketBroadcaster` (from Task 1)
- Produces: Slimmed `MeshResource` — instances, config, human interjection only

- [ ] **Step 1: Add qhorus-push to claudony app pom.xml**

- [ ] **Step 2: Update ClaudonyChannelBackend**

Change from `ChannelEventBus.emit(name)` to `QhorusWebSocketBroadcaster` broadcast methods.

- [ ] **Step 3: Strip MeshResource**

Remove all channel REST operations (channels, messages, reactions, topics, members, presence, commitments, SSE endpoints). Keep: `instances`, `config`, `postMessage` (human interjection with type validation).

- [ ] **Step 4: Delete ChannelEventBus**

Use `ide_refactor_safe_delete`.

- [ ] **Step 5: Delete SSE endpoint methods from MeshResource**

Remove `/api/mesh/events` and `/api/mesh/channels/{name}/events` SSE methods.

- [ ] **Step 6: Build and verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -f $SLOT/claudony/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git -C $SLOT/claudony add -A
git -C $SLOT/claudony commit -m "refactor(#33): switch to qhorus-push, strip MeshResource

Delete ChannelEventBus and SSE endpoints. Strip MeshResource to
instances, config, and human interjection only. Channel operations
now served by qhorus ChannelResource via classpath.

Refs casehubio/chat-app#33"
```

---

### Task 9: claudony frontend — refactor workbench

**Files:**
- Modify: `$SLOT/claudony/app/src/main/webui/src/components/claudony-workbench.ts` (~873 → ~400 lines)
- Modify: `$SLOT/claudony/app/src/main/webui/src/components/channel-panel.ts`
- Delete: `$SLOT/claudony/app/src/main/webui/src/util/channel-adapter.ts`

**Interfaces:**
- Consumes: All 6 controllers from blocks-ui (Task 4, 5)
- Produces: Slimmed workbench — layout + claudony-specific features only

- [ ] **Step 1: Rewrite claudony-workbench.ts to use controllers**

Replace ~470 lines of channel data management with controller composition. Keep claudony-specific code:
- Case context header, worker lineage, worker switching
- Terminal integration
- Stale cursor detection
- Mesh overview panel
- allowedTypes, message type validation

Pattern identical to Task 7 — create controllers, forward events, bind state to blocks-ui components.

- [ ] **Step 2: Refactor channel-panel.ts**

Replace channel data loading with controllers. Keep claudony-specific rendering.

- [ ] **Step 3: Delete channel-adapter.ts**

- [ ] **Step 4: Run frontend tests**

Run: `cd $SLOT/claudony/app/src/main/webui && npx vitest run`
Expected: PASS

- [ ] **Step 5: Full build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -f $SLOT/claudony/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git -C $SLOT/claudony add -A
git -C $SLOT/claudony commit -m "refactor(#33): workbench uses composable controllers from blocks-ui

Claudony workbench shrinks from ~873 to ~400 lines. Channel data
management replaced by composable controllers. ChannelEventBus SSE
replaced by PushController WebSocket. channel-adapter.ts deleted.
Claudony-specific features preserved: terminal, workers, case context.

Refs casehubio/chat-app#33"
```

---

## Phase 5: Integration

### Task 10: End-to-end verification

**Files:** None created — verification only.

- [ ] **Step 1: Build all repos in dependency order**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -f $SLOT/qhorus/pom.xml
cd $SLOT/blocks-ui/components/channel-activity && npx vitest run && npx tsc -p tsconfig.build.json
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -Pui -f $SLOT/chat-app/pom.xml
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -f $SLOT/claudony/pom.xml
```

- [ ] **Step 2: Run chat-app in dev mode and verify**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -Pui -f $SLOT/chat-app/pom.xml
```

Verify at `http://localhost:8090`:
- Login works
- Channels load (including space grouping in nav if spaces exist)
- Messages send and appear in real-time
- Reactions, topics, members, presence all work
- WebSocket reconnection works

- [ ] **Step 3: Run claudony and verify**

Start claudony, verify:
- WebSocket push works (replaces SSE polling)
- Channel operations route through qhorus ChannelResource
- Terminal integration still works
- Worker switching still works
- Case context headers still render

- [ ] **Step 4: Verify no endpoint conflicts**

Confirm that qhorus ChannelResource endpoints don't conflict with chat-app's remaining ChatResource endpoints or claudony's remaining MeshResource endpoints. Check path prefixes are distinct.

- [ ] **Step 5: Update issue #33 body**

Update the issue body to reflect the actual architecture (foundation promotion vs original chat-app dependency approach).

- [ ] **Step 6: Close superseded issues**

Close #9 (embed qhorus workbench in claudony) — superseded by this consolidation.
Close #11 (pages-data-request pipeline) — subsumed by ChannelController abstraction.

---

## References

- [2026-08-16-consolidate-channel-ui-design.md] — design spec this plan implements
- [decisions.md] — decision log (D1-D7, three revised after review)
- [ChatResource.java] — chat-app REST resource (stripped in Task 6)
- [ChatDatasetBuilder.java] — moved to qhorus-push (Task 1)
- [QhorusWorkbenchElement] — chat-app workbench (refactored in Task 7)
- [claudony-workbench.ts] — claudony workbench (refactored in Task 9)
- [MeshResource.java] — claudony REST resource (stripped in Task 8)
- [ChannelResource.java] — qhorus REST resource (enhanced in Task 3)
- [SpaceService.java, SpaceStore.java, Space.java] — existing qhorus Space model
- [GitHub #33] — focal issue
- [GitHub #7] — space hierarchy (included in scope)
