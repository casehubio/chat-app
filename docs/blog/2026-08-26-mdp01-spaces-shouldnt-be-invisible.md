---
layout: post
title: "Spaces Shouldn't Be Invisible"
date: 2026-08-26
entry_type: note
subtype: diary
projects: [casehubio/chat-app]
tags: [architecture, push, spaces, channel-nav]
series: issue-34-space-nav-enhancements
---

# Spaces Shouldn't Be Invisible

The channel nav derives its space structure from channel metadata. Each channel row carries a `spaceId` and `spaceName`, and the `channelTree` getter groups channels into `SpaceNode` entries by iterating over them. If a space has zero channels, it doesn't exist in the tree. That's architecturally wrong — it's deriving organizational structure from membership. Delete all the employees and the department vanishes from the org chart.

This surfaced in #34 when we added space CRUD operations. Creating an empty space made it appear briefly via a local `pendingSpaces` overlay, but the next push snapshot wiped it — no channels, no space. The overlay was always a temporary fix; #35 was the deferred "do it properly" issue.

I asked Claude to analyze the problem from first principles before I looked at approaches. The recommendation was clear: spaces should be a push dataset, the same way channels, messages, topics, members, and reactions already are. Every other domain entity has its own push topic. Spaces were the gap.

The alternative that tempted me was a REST supplementary call — `GET /api/spaces` on load, merge with channel-derived data. It would have been less code. But it creates two data sources with no synchronization guarantee, and the push system already solves exactly this problem for everything else. The "simpler" option would have been simpler code with a harder-to-debug failure mode.

The implementation has two halves. The frontend side we built in this session: a new `_applySpaces` handler in `ChannelStateController` that processes snapshot, append, replace, and remove ops for the `spaces` dataset. The `channelTree` getter was refactored to build spaces-first — iterate `this.spaces` to create the structure, then assign channels into it. If a channel references a `spaceId` not in the spaces dataset, it falls to ungrouped. The `pendingSpaces` overlay stays as optimistic-UI only: show the space immediately on create, discard when the push snapshot confirms it.

The backend half — mutation events in qhorus-api, `SpaceService` firing them, snapshot builder and delta broadcasts in qhorus-push — is tracked as casehubio/qhorus#416. That's roughly 65 lines of mechanical infrastructure following the established pattern. Until it ships, the frontend is ready but empty spaces won't appear from the server.

One finding worth noting from the decision review: the spec originally underestimated the qhorus scope at ~60 lines. The reviewer caught that the delta broadcast path requires `ChannelMutationEvent` sealed variants (`SpaceCreated`, `SpaceRenamed`, `SpaceDeleted`), event firing from `SpaceService`, and `onMutation` routing in the broadcaster — bringing the real total to ~110 lines across four modules. The pattern is mechanical, but the scope matters for planning.

The `channelTree` refactoring touched every existing test that assumed spaces would be derived from channels. Six tests needed a `push.applyOp({ dataset: 'spaces', ... })` call before their channel snapshot. Fourteen new tests cover the spaces handler and the spaces-first construction. All 66 pass.

What this opens up: #36 (drag-and-drop for channel-to-space moves) and #37 (multi-level nesting UI) both depend on spaces being first-class data. With the channelTree now building from the spaces dataset, nested space hierarchies are structurally supported — the parent-child wiring was part of the refactoring. The visibility question is answered; the interaction and nesting questions are next.
