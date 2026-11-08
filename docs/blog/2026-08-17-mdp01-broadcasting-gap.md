---
title: "When spec meets code: the broadcasting gap"
date: 2026-08-17
entry_type: note
subtype: diary
projects: [casehub-chat-app]
tags: [architecture, qhorus, cdi-events, push, implementation]
series: issue-33-consolidate-channel-ui
status: draft
issue: 33
---

*Continues from [channel consolidation design](2026-08-16-01-channel-consolidation-design.md).*

The design spec said "migrate reaction/topic/member endpoints from ChatResource to qhorus ChannelResource." Simple enough — move the JAX-RS methods, update the imports, done. Except ChatResource does something after every mutation that the spec didn't account for: it broadcasts.

Every `addReaction()` call is followed by `broadcaster.broadcastReactionAppend()`. Every `createTopic()` fires `broadcastTopicAppend()`. The push system delivers real-time updates to every connected WebSocket client. Move the endpoints to qhorus without broadcasting and the UI goes silent — reactions appear only after a page refresh.

The spec puts the broadcaster in `qhorus-push`. The endpoints live in `qhorus-runtime`. Runtime can't depend on push — push is optional, and pulling it in as a dependency drags WebSocket infrastructure into every qhorus consumer's test classpath. `Instance<QhorusWebSocketBroadcaster>` would work at runtime but CDI discovers push beans during `@QuarkusTest`, creating infrastructure conflicts with no obvious cause.

The fix was CDI events. A sealed interface in qhorus-api — seven record variants, one per mutation type:

```java
public sealed interface ChannelMutationEvent {
    record ReactionAdded(long messageId, String emoji)
        implements ChannelMutationEvent {}
    record MemberJoined(UUID channelId, ChannelMembership membership)
        implements ChannelMutationEvent {}
    // ... five more
}
```

ChannelResource injects `Event<ChannelMutationEvent>` and fires after each mutation. QhorusWebSocketBroadcaster adds an `@Observes` method with a pattern match. If push isn't on the classpath, events fire to nobody — CDI handles it gracefully. Zero coupling between runtime and push. The sealed interface gives exhaustive compile-time checking in the observer, so adding a new mutation type without handling it is a build error.

The other surprise was more pedestrian. rest-assured's `$.size()` GPath expression returns `null` on root-level JSON arrays of strings. Not zero, not an error — `null`. We lost fifteen minutes on a test that was correctly verifying a working endpoint before realising the assertion itself was the problem. `body("$", hasItem("thumbsup"))` works; `body("$.size()", greaterThan(0))` doesn't, but only for primitive arrays. Object arrays work fine.

Phase 1 is three commits in qhorus: the push module (dataset builder, broadcaster, WebSocket endpoint, infrastructure), SpaceResource for the already-existing-but-unexposed Space model, and ChannelResource with aggregation endpoints plus all the CRUD that used to live in ChatResource and MeshResource. Full build passes across all 14 modules.

The foundation is laid. Next is the consumer side — composable Lit controllers in blocks-ui, then refactoring both app workbenches to use them. That's where the spec's promise of "~800 lines → ~200 lines" gets tested against reality.
