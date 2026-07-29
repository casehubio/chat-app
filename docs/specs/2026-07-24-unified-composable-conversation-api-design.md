# Chat-App Migration: SqliteChatBackend to Qhorus Runtime

**Issue:** casehubio/chat-app#22 — migrate from SqliteChatBackend to qhorus runtime with H2
**Date:** 2026-07-24
**Status:** Draft

## Problem

The chat-app has a standalone `SqliteChatBackend` (1033 lines) that reimplements channels, messages, threading, reactions, presence, members, topics, commitments, and correlation chains — all of which qhorus already provides with richer semantics. `ChatResource` and `ChatWebSocketBroadcaster` inject BOTH `ChatPlatform` (connectors chat-spi) AND `SqliteChatBackend`, creating a dual-path architecture with:

- Two type systems (`connectors.chat.model.Channel` alongside SqliteChatBackend's raw SQL maps)
- Enriched fields stored in a separate SQL table via `storeEnrichedFields()`, disconnected from the message
- Manual commitment orchestration: `if ("COMMAND".equals(msgType)) chatBackend.createCommitment(...)`
- No dispatch pipeline protections: no ACLs, rate limits, protocol enforcement, obligor trust, or ledger writes

## Design Decision

Three concerns, addressed together:

1. **Migrate the chat-app** from SqliteChatBackend to qhorus service interfaces with H2 persistence
2. **Fill genuine API gaps** in qhorus-api — extended consumer interface (ConsumerMessaging), consumer-safe read interfaces extracted from pipeline-protected stores, and new service facades (TopicManager, PresenceTracker, MembershipManager, BackendRegistry)
3. **Support embedded deployment** — qhorus-runtime must work without infrastructure services (ledger, delivery queues, message broker) via configuration profile

### Why Direct API Integration?

The existing qhorus-api interfaces provide the consumer-facing boundary the chat-app needs:

- **Service facades** (`MessageDispatcher`, `ChannelManager`) are consumer-facing interfaces in `qhorus-api`. `MessageDispatcher.dispatch(MessageDispatch) → DispatchResult` IS the consumer dispatch boundary. These are lightweight interfaces with zero transitive dependencies — CDI resolves them to runtime implementations.
- **Service facades and readers** (`ReactionManager`, `MembershipManager`, `ChannelReader`, etc.) provide validated operations and consumer-safe queries. Facades resolve identity context from `CurrentPrincipal`; readers expose the full non-mutating surface of their underlying stores.
- **Gateway interfaces** (`HumanParticipatingChannelBackend`) handle human-frontend integration — outbound message delivery.

The chat-app imports interfaces from `qhorus-api` (lightweight). At runtime, CDI resolves them to `qhorus-runtime` implementations. Same pattern as a Quarkus extension.

### Why Not a Composable Capability Layer?

During design we explored adding basic capability interfaces (Messaging, Reactions, Presence, Members, Channels) to qhorus-api — a composable API where different backends implement different capability levels. This was rejected for specific reasons:

1. **The `send()` trap.** A basic `Messaging.send(channelId, content)` on the qhorus backend would call `dispatch()` with hardcoded defaults (MessageType.EVENT, ActorType.HUMAN). This hides decisions that should be explicit and produces wrong results for agents or typed messages.

2. **Inheritance coupling.** `ConsumerMessaging extends Messaging` would mean platform-specific needs (Slack wanting `editMessage()`) propagate into qhorus's core API. Platform abstraction and qhorus semantics have different design pressures and should evolve independently.

3. **Qhorus owning someone else's definition.** The basic interfaces model what Slack/Discord/IRC can do. The connectors team — who builds those integrations — should own that definition, not qhorus.

4. **No consumer today.** The chat-app uses extended interfaces. Slack connectors use `ConnectorChannelBackend`. The basic tier has no consumer right now.

The connectors-chat-spi capability pattern stays where it is — a proven, independent abstraction for external platforms. It shares qhorus types but not hierarchy.

## Architecture

```
Consumer code (ChatResource, ChatAppChannelBackend)
    │
    │ imports from qhorus-api only
    ▼
┌───────────────────────────────────────────────────┐
│            qhorus-api interfaces                   │
│                                                    │
│  Extended consumer interface (new):                │
│    ConsumerMessaging                               │
│      extends MessageDispatcher                     │
│      adds: history, findById,                      │
│            findByCorrelationId,                    │
│            findAllByCorrelationId                  │
│                                                    │
│  Existing facades (unchanged):                     │
│    MessageDispatcher — part of ConsumerMessaging    │
│    ChannelManager — channel admin ops              │
│                                                    │
│  New facades:                                      │
│    TopicManager — create/rename/resolve/merge       │
│    PresenceTracker — heartbeat/getPresence          │
│    MembershipManager — join/leave/setRole           │
│    ReactionManager — react/unreact                  │
│    BackendRegistry — register/deregister backends  │
│                                                    │
│  Consumer-safe readers (new):                      │
│    CommitmentReader — full read surface             │
│    TopicReader — find, findByChannel               │
│    MessageReader — full read surface               │
│    ChannelReader — find, findByName, list,          │
│      findByNamePrefix, scan                        │
│    MembershipReader — find, findByChannel           │
│    ReactionReader — findByMessage, findByMessages   │
│                                                    │
│  Gateway:                                          │
│    HumanParticipatingChannelBackend                │
│      — chat-app implements this                    │
│                                                    │
│  Events:                                           │
│    ChannelInitialisedEvent — channel lifecycle      │
│    CommitmentStateChangedEvent — commitment updates │
│                                                    │
│  Rule: consumer interfaces never expose operations │
│  that mutate state outside the dispatch pipeline   │
│  or validated service facades.                     │
└───────────────────────┬───────────────────────────┘
                        │
           CDI resolves at runtime to:
                        │
┌───────────────────────┴───────────────────────────┐
│   qhorus-runtime                                   │
│                                                    │
│   MessageService implements ConsumerMessaging      │
│   ChannelService implements ChannelManager,        │
│     ChannelReader                                  │
│   PresenceService implements PresenceTracker       │
│   TopicService implements TopicManager             │
│   MembershipService implements MembershipManager   │
│   ReactionService implements ReactionManager       │
│   ChannelGateway implements BackendRegistry        │
│   JpaCommitmentStore implements CommitmentStore     │
│     (which extends CommitmentReader)               │
│   JpaMessageStore implements MessageStore          │
│     (which extends MessageReader)                  │
│   JpaTopicStore implements TopicStore              │
│     (which extends TopicReader)                    │
│   JpaChannelStore implements ChannelStore           │
│   JpaChannelMembershipStore implements             │
│     ChannelMembershipStore                         │
│     (which extends MembershipReader)               │
│   JpaReactionStore implements ReactionStore        │
│     (which extends ReactionReader)                 │
│                                                    │
│   JPA + H2/Postgres                               │
└────────────────────────────────────────────────────┘
```

### Consumer Code Pattern

```java
public class ChatResource {
    @Inject ConsumerMessaging messaging;          // dispatch + queries
    @Inject ChannelManager channels;              // channel admin ops (create, delete)
    @Inject ChannelReader channelReader;           // channel queries (findByName, list)
    @Inject ReactionManager reactions;            // validated react/unreact
    @Inject ReactionReader reactionReader;         // reaction queries
    @Inject PresenceTracker presence;             // heartbeat/getPresence
    @Inject MembershipManager members;            // validated membership ops
    @Inject MembershipReader memberReader;         // membership queries
    @Inject CommitmentReader commitments;         // query-only (full read surface)
    @Inject TopicManager topics;                  // admin ops
    @Inject TopicReader topicReader;              // topic queries
}
```

No ChatPlatform. No SqliteChatBackend. No NoOp defaults. No runtime `supports()` checks.

### Multi-tenancy

Qhorus is multi-tenant. `CurrentPrincipal.tenancyId()` provides the tenant context for the current request.

**Service facades resolve tenancyId from `CurrentPrincipal` automatically.** This applies to all facades: `MessageService.dispatch()`, `TopicManager`, `MembershipManager`, `ReactionManager`. Consumer code never passes `tenancyId` to a facade method.

**Reader queries that scope by tenant** require explicit `tenancyId`: `MembershipReader.findByMember(memberId, tenancyId)`. The chat-app derives this from `CurrentPrincipal`:

```java
String tenancyId = currentPrincipal.tenancyId();
memberReader.findByMember(memberId, tenancyId);
```

**Embedded/single-tenant mode:** The chat-app configures a default tenancy via `CurrentPrincipal` implementation. The chat-app's JWT auth (`pages-auth`) maps to a `CurrentPrincipal` that returns a configured `tenancyId` (e.g., `"chat-app"`).

### The Hard Design Decisions

#### 1. ConsumerMessaging — extending MessageDispatcher with queries

`ConsumerMessaging` extends `MessageDispatcher` with query methods the chat-app needs:

```java
// Existing — unchanged
public interface MessageDispatcher {
    DispatchResult dispatch(MessageDispatch dispatch);
}

// New — extends existing facade with queries
public interface ConsumerMessaging extends MessageDispatcher {
    List<Message> history(UUID channelId, long afterId, int limit);
    List<Message> history(UUID channelId, long afterId, int limit, boolean includeEvents);
    List<Message> historyBySender(UUID channelId, long afterId, int limit, String sender, boolean includeEvents);
    Optional<Message> findById(long messageId);
    Optional<Message> findByCorrelationId(String correlationId);
    List<Message> findAllByCorrelationId(String correlationId);
}
```

`MessageService implements ConsumerMessaging` replaces `MessageService implements MessageDispatcher`. Since `ConsumerMessaging extends MessageDispatcher`, existing code injecting `MessageDispatcher` is unaffected.

This extension is safe because `MessageDispatcher` has exactly one method (`dispatch()`) — the consumer dispatch boundary. Adding queries alongside dispatch does not expose any admin mutations.

**Channels do NOT use this pattern.** `ChannelManager` has 12 methods including admin mutations (`pause()`, `setAllowedWriters()`, `setRateLimits()`, etc.). Extending it would expose those mutations to consumers, violating the consumer-safety rule. Instead, channels use the reader/writer split: consumers inject `ChannelManager` for admin ops (create, delete) and `ChannelReader` for queries (findByName, list).

#### 2. Commitments are pipeline stages; topics are administrative metadata

**Commitments** are pipeline-triggered: COMMAND opens, RESPONSE fulfills, DECLINE declines. Consumers get read-only `CommitmentReader` — every non-mutating method from `CommitmentStore`:

```java
public interface CommitmentReader {
    Optional<Commitment> findById(UUID commitmentId);
    Optional<Commitment> findByCorrelationId(String correlationId);
    List<Commitment> findAllByCorrelationId(String correlationId);
    List<Commitment> findByIds(Collection<UUID> ids);
    List<Commitment> findByChannel(UUID channelId);
    List<Commitment> findOpenByObligor(String obligor, UUID channelId);
    List<Commitment> findOpenByObligor(String obligor);
    List<Commitment> findOpenByRequester(String requester, UUID channelId);
    List<Commitment> findByState(CommitmentState state, UUID channelId);
    List<Commitment> findOpenByChannelId(UUID channelId);
    List<Commitment> findAllOpen();
    List<Commitment> findExpiredBefore(Instant cutoff);
}

public interface CommitmentStore extends CommitmentReader {
    Commitment save(Commitment commitment);  // pipeline-internal only
    void deleteById(UUID commitmentId);
    long deleteAll(UUID channelId);
    long deleteExpiredBefore(Instant cutoff);
}
```

The split is reader vs writer — ALL non-mutating methods belong in the reader, not "methods I think consumers need right now." This prevents premature access restrictions and ensures the reader surface matches the store surface exactly.

**Topics** are organizational metadata. The pipeline auto-creates topics during dispatch, but rename, merge, resolve, and state transitions are administrative operations. Topics get `TopicManager` (validated writes) and `TopicReader` (queries):

```java
public interface TopicManager {
    record RenameResult(String oldName, String newName, int messagesUpdated) {}
    record MergeResult(String sourceTopic, String targetTopic, int messagesUpdated) {}

    Topic create(UUID channelId, String name);
    Topic resolve(UUID channelId, String topicName);
    Topic unresolve(UUID channelId, String topicName);
    RenameResult rename(UUID channelId, String oldName, String newName);
    MergeResult merge(UUID channelId, String sourceTopic, String targetTopic);
    List<TopicSummary> listTopics(UUID channelId);
}

public interface TopicReader {
    Optional<Topic> find(UUID channelId, String name);
    Optional<Topic> findById(Long topicId);
    List<Topic> findByChannel(UUID channelId);
}
```

`TopicManager` and `TopicReader` are independent interfaces (no extends) to avoid CDI `AmbiguousResolutionException`. Result records are inner types of `TopicManager` in qhorus-api.

The rule: **consumer interfaces never expose operations that mutate conversation state outside the dispatch pipeline or validated service facades.** Commitment writes go through `ConsumerMessaging.dispatch()`. Topic admin goes through `TopicManager` (validated, not raw store writes). Membership mutations go through `MembershipManager` (validated).

#### 3. Store splits — which stores need consumer-safe readers?

| Store | Split | Reason |
|-------|-------|--------|
| `CommitmentStore` | → `CommitmentReader` | `save()` bypasses commitment lifecycle |
| `MessageStore` | → `MessageReader` | `put()` bypasses 22-step dispatch pipeline |
| `ChannelStore` | `ChannelReader` (independent) | `ChannelReader` is an independent interface, not a store extraction — includes service-level query methods (`list()`, `findByNamePrefix()`) that live on `ChannelService`, not `ChannelStore`. `ChannelService implements ChannelManager, ChannelReader`. |
| `TopicStore` | → `TopicReader` | Consumers use `TopicManager` for writes, `TopicReader` for queries |
| `ChannelMembershipStore` | → `MembershipReader` + `MembershipManager` | `put(ChannelMembership)` bypasses membership lifecycle — accepts arbitrary `MemberRole`, `tenancyId`, `joinedAt` |
| `ReactionStore` | → `ReactionReader` + `ReactionManager` | `react()` takes explicit `actorId`/`tenancyId` (impersonation risk); `deleteByMessage()`/`deleteByChannel()` are admin operations |

**MembershipManager** — validated membership facade (same pattern as `TopicManager`):

```java
public interface MembershipManager {
    ChannelMembership join(UUID channelId, String memberId);
    void leave(UUID channelId, String memberId);
    void setRole(UUID channelId, String memberId, MemberRole role);
    void updateLastReadMessageId(UUID channelId, String memberId, Long messageId);
}

public interface MembershipReader {
    Optional<ChannelMembership> find(UUID channelId, String memberId);
    List<ChannelMembership> findByChannel(UUID channelId);
    List<ChannelMembership> findByMember(String memberId, String tenancyId);
}
```

`MembershipManager.join()` resolves `tenancyId` from `CurrentPrincipal` internally (consistent with all other facades). It validates: channel exists, channel is not paused, member is not already joined. It sets `joinedAt` to the current timestamp and `role` to the default (`MemberRole.MEMBER`). `ChannelMembershipStore.put()` is pipeline-internal only.

**ReactionManager** — validated reaction facade (same pattern as `MembershipManager`):

```java
public interface ReactionManager {
    Reaction react(Long messageId, String emoji);
    boolean unreact(Long messageId, String emoji);
}

public interface ReactionReader {
    List<Reaction> findByMessage(Long messageId);
    Map<Long, List<Reaction>> findByMessages(Collection<Long> messageIds);
}
```

`ReactionManager.react()` resolves `actorId` and `tenancyId` from `CurrentPrincipal` internally — consumers cannot impersonate another user's reactions. `ReactionStore.deleteByMessage()` and `deleteByChannel()` are admin/cleanup operations that stay on the internal `ReactionStore`.

#### 4. Gateway integration for the chat-app frontend

The chat-app implements `HumanParticipatingChannelBackend` — qhorus's existing pattern for human-facing frontends.

**Outbound (qhorus → browser):** `ChannelGateway.fanOut()` calls `post()` on the chat-app's backend for every dispatched message — from agents, other humans, and system events. The backend pushes to WebSocket clients.

**Inbound (browser → qhorus):** `ChatResource` calls `messaging.dispatch(MessageDispatch)` directly with full structured fields. No normalization needed — the frontend sends explicit messageType, actorType, correlationId.

**Channel lifecycle:** `ChannelInitialisedEvent` (CDI event from `ChannelGateway`) fires for every channel at startup (recovery of persisted channels) and on creation. The backend observes this and registers via `BackendRegistry`:

```java
public interface BackendRegistry {
    void registerBackend(UUID channelId, ChannelBackend backend, String backendType);
    void deregisterBackend(UUID channelId, String backendId);
    List<BackendRegistration> listBackends(UUID channelId);
}
```

`ChannelGateway implements BackendRegistry`. Consumer code imports `BackendRegistry` from `qhorus-api`, not `ChannelGateway` from `qhorus-runtime`. This preserves the "imports from qhorus-api only" principle.

```java
public record BackendRegistration(String backendId, String backendType, ActorType actorType) {}
```

```java
@ApplicationScoped
public class ChatAppChannelBackend implements HumanParticipatingChannelBackend {
    @Inject ChatWebSocketBroadcaster broadcaster;
    @Inject BackendRegistry registry;

    @Override public String backendId() { return "chat-app"; }
    @Override public ActorType actorType() { return ActorType.HUMAN; }

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        registry.registerBackend(event.channelId(), this, "human_participating");
        broadcaster.registerChannel(event.channelId(), event.channelName());
    }

    @Override public void post(ChannelRef channel, OutboundMessage message) {
        broadcaster.pushMessage(channel, message);
    }

    void onCommitmentChanged(@Observes(during = AFTER_SUCCESS) CommitmentStateChangedEvent event) {
        broadcaster.broadcastCommitment(event.commitment());
    }

    @Override public void close(ChannelRef channel) {
        broadcaster.deregisterChannel(channel);
    }
}
```

**Advantage:** Messages from ALL sources are delivered through one path. The current architecture requires explicit broadcaster calls after each operation — miss one and the WebSocket goes stale.

**Commitment broadcasting:** The `post()` callback runs on a virtual thread spawned by `ChannelGateway.fanOut()` — it executes OUTSIDE the dispatch transaction. Querying commitment state in `post()` would create a read-after-write race (the commitment change may not be committed yet).

Instead, commitment state changes are broadcast via `CommitmentStateChangedEvent` — a CDI event fired by the dispatch pipeline after a commitment is created, fulfilled, declined, or acknowledged. The chat-app backend observes this event with `@Observes(during = AFTER_SUCCESS)` to ensure the transaction has committed before broadcasting:

```java
public record CommitmentStateChangedEvent(
    UUID channelId,
    Commitment commitment,
    CommitmentState previousState) {}
```

**WebSocket non-message datasets:** Consumer-initiated operations (channel create, member join, reaction add, topic rename) are broadcast explicitly by `ChatResource`. Pipeline side effects (commitment state changes) are broadcast via `CommitmentStateChangedEvent` observation.

#### 5. OutboundMessage topic field

`OutboundMessage` has 9 fields but no `topic` — inconsistent with `Message` and `MessageReceivedEvent`. Fix: add `topic` field to `OutboundMessage` in qhorus-api, populated from `Message.topic()` in `ChannelGateway.fanOut()`.

#### 6. PresenceTracker — genuine API gap

`PresenceService` exists only in qhorus-runtime with no API-level interface. `PresenceTracker` is a new service facade:

```java
public interface PresenceTracker {
    void heartbeat(PresenceStatus status, String statusMessage);
    Presence getPresence(String memberId);
    List<Presence> getChannelPresence(UUID channelId);
    void setOffline();
}
```

`heartbeat()` and `setOffline()` are actor operations — they act on the calling user's own presence. The implementation resolves `memberId` from `CurrentPrincipal.actorId()` internally. `getPresence()` and `getChannelPresence()` are query operations that legitimately take target parameters.

`PresenceService implements PresenceTracker`.

### Embedded Deployment Profile

The chat-app embeds qhorus-runtime as a backend. Not all qhorus infrastructure is needed — the chat-app doesn't require a ledger service, delivery queues, message broker, or distributed tracing.

**Requirement:** qhorus-runtime must support an `embedded` configuration profile that provides no-op defaults for optional infrastructure:

| Infrastructure | Production | Embedded profile |
|---------------|-----------|-----------------|
| LedgerWriteService | Writes to ledger | No-op (skip ledger writes) |
| DeliverySignalQueue | Queues delivery signals | No-op (in-process delivery only) |
| ChannelActivityBroadcaster | Cluster broadcast | No-op (`NoOpChannelActivityBroadcaster` already exists) |
| Tracing (OpenTelemetry) | Full tracing | Disabled |
| InstanceService | Multi-instance coordination | Single-instance default |
| AgentChannelBackend | Agent mesh routing | No-op or absent |

The chat-app activates this profile via `%embedded.` configuration properties in `application.properties`, or via Quarkus profile activation. The pipeline still runs all validation steps — only infrastructure integration points are no-op'd.

**Pipeline dependencies and embedded defaults:**

| Pipeline Dependency | Embedded Behaviour |
|--------------------|--------------------|
| `CurrentPrincipal` | Chat-app provides its own implementation bridging `pages-auth` JWT claims to `actorId()`, `tenancyId()`, and `groups()`. Not a qhorus profile concern — the chat-app owns this. |
| `ObligorTrustPolicy` | Permissive default — all actors trusted. Chat-app has no agent trust hierarchy. |
| `RateLimiter` | No rate limits by default (effectively unlimited). Configurable via `casehub.qhorus.rate-limit.*` properties if needed. |
| `ProtocolRegistry` | Empty registry — no protocol enforcement unless explicitly configured. All message types pass validation. |
| `CorrelationIntegrityChecker` | Standard defaults — validates correlationId chain integrity. No embedded-specific behaviour needed. |

This is a qhorus concern, not a chat-app design decision. The chat-app spec records the requirement; the qhorus work satisfies it.

## Consumer API Surface

**Extended consumer interface** (new — in qhorus-api):

| Interface | Extends | Key methods |
|-----------|---------|------------|
| `ConsumerMessaging` | `MessageDispatcher` | `history(channelId, afterId, limit)`, `history(channelId, afterId, limit, includeEvents)`, `historyBySender(channelId, afterId, limit, sender, includeEvents)`, `findById(messageId)`, `findByCorrelationId(correlationId)`, `findAllByCorrelationId(correlationId)` |

**New service facades** (in qhorus-api):

| Interface | Key methods |
|-----------|------------|
| `TopicManager` | `create()`, `rename()→RenameResult`, `merge()→MergeResult`, `resolve()`, `unresolve()`, `listTopics()` |
| `PresenceTracker` | `heartbeat()`, `getPresence()`, `getChannelPresence()`, `setOffline()` |
| `MembershipManager` | `join(channelId, memberId)`, `leave()`, `setRole()`, `updateLastReadMessageId()` |
| `ReactionManager` | `react(messageId, emoji)`, `unreact(messageId, emoji)` — resolves actorId/tenancyId from CurrentPrincipal |
| `BackendRegistry` | `registerBackend(channelId, backend, backendType)`, `deregisterBackend()`, `listBackends()→List<BackendRegistration>` |

**Consumer-safe readers** (new — extracted from existing stores):

| Interface | Extracted from | Key methods |
|-----------|---------------|-------------|
| `CommitmentReader` | `CommitmentStore` | ALL non-mutating methods: `findById()`, `findByCorrelationId()`, `findAllByCorrelationId()`, `findByIds()`, `findByChannel()`, `findOpenByObligor()`, `findOpenByRequester()`, `findByState()`, `findOpenByChannelId()`, `findAllOpen()`, `findExpiredBefore()` |
| `TopicReader` | `TopicStore` | `find()`, `findById(Long)`, `findByChannel()` |
| `MessageReader` | `MessageStore` | ALL non-mutating methods: `find()`, `scan(MessageQuery)`, `findRecent()`, `count(MessageQuery)`, `countByChannel()`, `countAllByChannel()`, `distinctSendersByChannel()`, `findLastMessage()` |
| `ChannelReader` | Independent (implemented by `ChannelService`) | `findById()`, `findByName()`, `findByNamePrefix()`, `list()`, `scan(ChannelQuery)`, `findByIds()` |
| `MembershipReader` | `ChannelMembershipStore` | `find()`, `findByChannel()`, `findByMember()` |
| `ReactionReader` | `ReactionStore` | `findByMessage()`, `findByMessages()` |

**Gateway** (chat-app implements):

| Interface | Purpose |
|-----------|---------|
| `HumanParticipatingChannelBackend` | Outbound WebSocket delivery via gateway fan-out |

**Events** (new — in qhorus-api):

| Event | Purpose |
|-------|---------|
| `CommitmentStateChangedEvent` | Fired after dispatch when commitment state transitions — observed by backends for WebSocket broadcasting |

## Message Dispatch Mapping

The current three-step manual orchestration becomes a single dispatch call:

```java
DispatchResult result = messaging.dispatch(
    MessageDispatch.builder()
        .channelId(channelUuid)
        .sender(identity.getPrincipal().getName())
        .type(MessageType.valueOf(request.messageType()))
        .actorType(ActorType.valueOf(request.actorType()))
        .content(request.text())
        .correlationId(correlationId)
        .target(request.target())
        .artefactRefs(artefactRefs)
        .topic(topicName)
        .inReplyTo(parentMessageId)
        .build());
```

This replaces `storeMessage()` + `storeEnrichedFields()` + `createCommitment()` AND adds the full 22-step pipeline.

### Operation Mapping

| Current (ChatPlatform / SqliteChatBackend) | New (qhorus-api interface) |
|---|---|
| `chatPlatform.channelManagement().create(name, topic, desc, isPrivate)` | `channels.create(ChannelCreateRequest)` via `ChannelManager` |
| `chatPlatform.channelManagement().delete(id)` | `channels.delete(channelId, force)` via `ChannelManager` |
| `chatPlatform.discovery().listChannels()` | `channelReader.list()` via `ChannelReader` |
| `chatBackend.storeMessage() + storeEnrichedFields() + createCommitment()` | `messaging.dispatch(MessageDispatch)` via `ConsumerMessaging` |
| `chatPlatform.messageHistory().messages(channel, since)` | `messaging.history(channelId, sinceId, limit)` via `ConsumerMessaging` |
| `chatPlatform.reactions().add/remove/list(messageRef, emoji)` | `reactions.react/unreact(messageId, emoji)` via `ReactionManager`; `reactionReader.findByMessage(messageId)` via `ReactionReader` |
| `chatPlatform.presence().of/set(memberRef, status)` | `presence.getPresence(memberId)` / `presence.heartbeat(status, statusMessage)` via `PresenceTracker` |
| `chatPlatform.members().list(channelRef)` | `memberReader.findByChannel(channelId)` via `MembershipReader` |
| `chatPlatform.memberManagement().add/remove(channelRef, member)` | `members.join/leave(channelId, memberId)` via `MembershipManager` |
| `chatBackend.markRead(channelRef, memberRef, instant)` | `members.updateLastReadMessageId(channelId, memberId, messageId)` via `MembershipManager` — timestamp → message ID |
| `chatBackend.createTopic/listTopics/updateTopic/mergeTopic(...)` | `topics.create/listTopics/rename/merge(...)` via `TopicManager` |
| `chatBackend.listCommitments(channelId)` | `commitments.findByChannel(channelId)` via `CommitmentReader` |
| `chatBackend.updateCommitmentState(id, state, ackAt)` | Dispatch RESPONSE/DONE/DECLINE message via `ConsumerMessaging` |
| `chatBackend.correlationMessages(channelId, correlationId)` | `messaging.findAllByCorrelationId(correlationId)` via `ConsumerMessaging` |

### REST API Changes

**markRead — timestamp → message ID:** `PUT /channels/{id}/read` with body `{ "lastReadMessageId": 42 }`. Message-ID cursor is deterministic and immune to clock skew. Forward-only — marking a lower ID is a no-op.

**Commitment state changes — PATCH → dispatch:** Commitment state transitions are message semantics:

| State change | Dispatch message type |
|---|---|
| Acknowledge | `MessageType.STATUS` with correlationId |
| Fulfill | `MessageType.DONE` with correlationId |
| Decline | `MessageType.DECLINE` with correlationId |

Endpoint changes from `PATCH /channels/{id}/commitments/{commitmentId}` to `POST /channels/{id}/messages` with the appropriate MessageType. Gains ledger writes, observer notification, and protocol enforcement. Frontend uses `correlationId`, not commitment internal ID.

### Member Display Names

Qhorus `ChannelMembership` does not store display names — `memberId` is a string identifier. In dev/demo context, `memberId` values are human-readable ("alice", "bob", "agent-001"). Production resolves display names from an IdP — a chat-app concern, not a qhorus concern.

## Chat-App Migration

### Dependencies

**Remove:**
- `casehub-connectors-chat-spi`
- `casehub-connectors-chat-ref`
- `casehub-connectors-core`
- `sqlite-jdbc`, `HikariCP`

**Add:**
- `casehub-qhorus-api` — service facades, store interfaces, gateway interfaces
- `casehub-qhorus-runtime` — service implementations, JPA stores
- `quarkus-jdbc-h2` — dev/demo persistence
- `quarkus-hibernate-orm` — JPA
- `quarkus-flyway` — schema migration (qhorus V1–V32+)

### Files Changed

**Delete:**
- `SqliteChatBackend.java` (1033 lines)
- `SqliteChatBackendTest.java`

**New:**
- `ChatAppChannelBackend.java` — implements `HumanParticipatingChannelBackend` for outbound WebSocket delivery via gateway fan-out

**Rewrite:**
- `ChatResource.java` — inject `ConsumerMessaging`, `ChannelManager`, `ChannelReader`, `TopicManager`, `TopicReader`, `PresenceTracker`, `MembershipManager`, `MembershipReader`, `ReactionManager`, `ReactionReader`, `CommitmentReader`
- `ChatWebSocketBroadcaster.java` — receives outbound messages via `ChatAppChannelBackend.post()` and commitment updates via `CommitmentStateChangedEvent`. Preserves dataset protocol structure.

**Frontend updates:**
- Lit component updates for type changes (messageId: String → number, channelId: String → UUID, topicId: String → number, member model gains MemberRole, commitment gains delegatedTo/fulfilledAt/declinedAt)

**Update:**
- `pom.xml` — dependency swap
- `application.properties` — H2 datasource, Flyway settings, embedded profile config, remove SQLite config

### Seed Data

Replace SQLite DB file (`demo-seed.db`) with `import.sql` targeting qhorus tables (schema V32+). Configure `quarkus.hibernate-orm.database.generation=none` — Flyway owns schema creation. `import.sql` executes after Flyway migrations complete.

### WebSocket Protocol

Dataset protocol structure preserved. Column definitions change to qhorus types:

| Dataset | Key Changes |
|---------|-------------|
| `channels` | `id`: String → UUID |
| `messages` | `messageId`: String → Long; enriched fields become first-class Message fields; gains `topic` |
| `members` | gains `MemberRole` enum; `displayName` → `memberId` |
| `presence` | gains `reportedStatus`, `lastSeenAt`, `statusMessage` |
| `reactions` | structure unchanged |
| `commitments` | gains `delegatedTo`, `fulfilledAt`, `declinedAt` |
| `topics` | `topicId`: String → Long; from raw maps to Topic records |

## Repos Touched

| Repo | Work | Scope |
|------|------|-------|
| `qhorus` (api) | ConsumerMessaging; consumer-safe readers (CommitmentReader, TopicReader, MessageReader, ChannelReader, MembershipReader, ReactionReader); service facades (TopicManager, PresenceTracker, MembershipManager, ReactionManager, BackendRegistry); BackendRegistration; CommitmentStateChangedEvent; OutboundMessage topic field | M |
| `qhorus` (runtime) | Services implement new interfaces; stores extend readers; ChannelService implements ChannelReader; ChannelGateway implements BackendRegistry; MembershipService; ReactionService; embedded profile no-op defaults; fire CommitmentStateChangedEvent from dispatch pipeline | M |
| `chat-app` | Full migration: delete SqliteChatBackend, implement ChatAppChannelBackend, wire to qhorus-api interfaces, H2, seed data, frontend updates | L |
| `parent` (docs) | Add chat-app entry to APPLICATIONS.md | XS |

Connectors modules are not changed. The connectors-chat-spi stays as-is — a proven, independent abstraction for external platforms that evolves on its own timeline.

## Test Strategy

Migration verification requires:

1. **Endpoint contract tests** — every REST endpoint returns the same response shape (with documented type changes: messageId String→Long, channelId String→UUID, topicId String→Long)
2. **WebSocket protocol tests** — dataset broadcasts match the documented column changes
3. **Seed data validation** — `import.sql` produces equivalent initial state to `demo-seed.db`
4. **Embedded profile smoke test** — qhorus-runtime boots with H2, no infrastructure services, and processes a dispatch end-to-end
5. **Commitment event test** — `CommitmentStateChangedEvent` fires after COMMAND/RESPONSE/DONE/DECLINE dispatch and commitment snapshot is correct

These are implementation concerns, not design decisions — detailed test plans belong in the implementation issues.

## Accepted Trade-offs

**Two type systems persist.** Qhorus types and connectors-chat-spi types coexist across the platform. This is acceptable because they serve different consumers (qhorus frontends vs platform connectors) and don't interact. If connectors ever converge on qhorus types, that's a connectors decision informed by real requirements.

**No designed convergence path.** The connectors-chat-spi and qhorus-api are independent. Future convergence is deferred without a plan. Designing a convergence path now without real requirements would be premature abstraction.

## Design Rationale

### Not turning qhorus into a chat app

The chat-app consuming qhorus services directly does NOT make qhorus a chat product. Qhorus provides conversation infrastructure — channels, messages, commitments, protocols, topics — designed for agent-human collaboration. The chat-app is a UI over this infrastructure, not a consumer demanding chat-product features.

Would you build a standalone chat product on qhorus? No — no rich threading UX, no file attachments, no message formatting, no typing indicators. The basics are deliberately minimal: enough for humans to participate in structured agent conversations, not enough for a chat product.

### Connectors-chat-spi stays independent

The chat-spi capability pattern (Messaging, Reactions, Presence, etc.) is the right design for multi-platform bridging. It stays in connectors because:
- The connectors team owns the platform abstraction
- Platform-specific needs (Slack's `editMessage()`, Discord's threads) shouldn't propagate into qhorus's core API
- The basic interfaces have no qhorus consumer today — designing them in qhorus-api would be premature

## Future Compatibility

The qhorus-api interfaces work at the channel level. Embedding channel views in case workbenches (channels scoped to a task with their own agent and human participants) is a narrower query over the same interfaces — no architectural changes needed.

## Garden Entries Referenced

- GE-20260613-7b7ae1: ChannelService.create() requires ChannelCreateRequest (not String args)
- GE-20260607-a4d78a: ChannelSlugValidator constraints on channel names
- GE-20260607-d051f2: MessageObserver null content for EVENT type
- GE-20260608-757be3: artefactRefs silently rejects non-UUID
- GE-20260616-8a07b1: setTypeConstraints normalizes null to Set.of()
- GE-20260623-92964b: RESPONSE fulfills commitment via correlationId
- GE-20260623-ef0e7c: QUERY hard-blocks on typed channel
