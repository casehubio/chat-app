---
title: "Channel consolidation: the tier hierarchy saves us from ourselves"
date: 2026-08-16
entry_type: note
subtype: diary
tags: [architecture, qhorus, chat-app, claudony, blocks-ui]
status: draft
issue: 33
---

chat-app and claudony have been independently wiring the same blocks-ui components over the same qhorus backend — two separate REST layers, two push mechanisms, two data adapters. Every upstream feature gets wired twice. Whichever app gets attention second goes stale.

The obvious fix: make claudony depend on chat-app as a Maven artifact. Chat-app already has the working push infrastructure, the dataset builder, the REST endpoints. Claudony just needs to include it and plug in its own layout. I nearly went with this.

Claude caught the problem during decision review. Both apps are integration-tier. Having one integration app depend on another violates the tier hierarchy — chat-app would become a de facto foundation component without the stability guarantees that implies. Every internal refactoring of chat-app would break claudony's build. And when a third app needs channels? Three-way coupling.

The fix is the boring one: promote the reusable pieces to qhorus, where they belong. Push dataset wiring (the 7-topic broadcaster, dataset builder, WebSocket endpoint) moves to a new `qhorus-push` module. Channel aggregation and Space REST endpoints go on qhorus's existing `ChannelResource`. Both apps depend on foundation. Neither depends on the other.

The second discovery was that qhorus already has a complete Space model — `Space`, `SpaceStore`, `SpaceService` with nesting depth limits, cycle detection, channel assignment, the lot. I'd been sketching metadata-field approaches and namespace conventions for channel hierarchy when the answer was already sitting in qhorus-api. Nobody documented it in any consumer guide. Issue #7 references it as a dependency but doesn't link to the classes.

On the frontend, we decomposed the shared logic into composable Lit reactive controllers rather than one thick controller. `PushController` owns the WebSocket. `ChannelStateController` owns channels, spaces, and the navigation tree. `MessagingController`, `MembershipController`, `ReactionController`, `CommitmentController` each handle their dataset. The host element creates only the controllers it needs — claudony's terminal-centric workbench doesn't need to import commitment tracking if it doesn't use it.

The controllers form an explicit dependency chain: `PushController` ← `ChannelStateController` ← everything else. Cross-controller state (like `selectedChannelId`) passes through constructor injection, not implicit shared state or event-based coordination. This was a review finding — the initial design had each controller independently tracking selection state, which would have caused subtle divergence.

A plan review caught a JAX-RS path collision I'd missed: both `ChatResource` and qhorus `ChannelResource` sit at `/api/channels`. Adding reaction/topic/member endpoints to the foundation resource would cause Quarkus to fail at startup with ambiguous routes. The fix: ChatResource moves to `/api/chat` for its remaining app-specific operations (message posting with auto-join, replies, read tracking).

The plan is 10 tasks across 4 repos — qhorus, blocks-ui, chat-app, claudony. Net effect: ~960 fewer lines of duplicated code. Both workbench elements drop from ~800 lines to ~200 (chat-app) and ~400 (claudony, which keeps its terminal integration, worker switching, and case context).

The part I'm most interested to see play out: whether composable controllers actually deliver on the promise. The pattern is clean in the spec, but Lit's reactive update cycle with 6 interacting controllers and a WebSocket data source is the kind of thing that works beautifully in isolation and then does something subtle under real load. The integration test in Task 10 will tell us.
