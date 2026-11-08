---
layout: post
title: "The path split nobody planned"
date: 2026-08-17
entry_type: note
subtype: diary
projects: [casehub-chat-app]
tags: [refactoring, rest-api, lit-controllers, architecture]
series: issue-33-consolidate-channel-ui
---

*Continues from [controllers without connections](2026-08-17-mdp02-controllers-without-connections.md).*

The design spec called for a clean extraction. Move push infrastructure to qhorus-push, strip the endpoints from ChatResource, wire the frontend to composable controllers. Straightforward refactoring — remove what's been promoted, consume what's been extracted.

The backend half went as planned. We deleted ChatDatasetBuilder, ChatWebSocketBroadcaster, ChatPushWebSocket, and PushInfrastructure — all now in `casehub-qhorus-push`. ChatResource shrank from a 418-line god-resource to a focused 223-line messaging endpoint. Reactions, topics, members, presence, commitments — all served by qhorus ChannelResource, auto-mounted via classpath scanning. The `@Path` changed from `/api/channels` to `/api/chat` to avoid the collision. Net result: 1,095 lines deleted from the backend alone.

The interesting part came on the frontend.

The composable controllers from blocks-ui replaced ChatDemoAdapter and ~600 lines of data management with six focused controllers: PushController for the WebSocket connection, ChannelStateController for channel and message state, MessagingController for REST operations, plus Membership, Reaction, and Commitment controllers. The workbench becomes a layout shell that creates controllers in its constructor and forwards events.

But MessagingController uses a single `restBase` for all its REST calls — `${restBase}/channels/${id}/messages` for posting, `${restBase}/channels` for channel CRUD, `${restBase}/channels/${id}/topics` for topic operations. With the backend split, message posting lives at `/api/chat` while everything else stays at `/api/channels`. The controller's API doesn't support two base paths.

I considered three options. Modifying MessagingController to accept a `messageRestBase` would work but changes the blocks-ui contract for a single consumer's quirk. Using a single `restBase` and making ChatResource match qhorus's path structure would reintroduce the collision we just fixed. The third option: intercept `SEND_MESSAGE` in the workbench before it reaches the controller, handle the `/api/chat` path directly, and let the controller handle everything else with `restBase = '/api'`.

We went with the intercept. It's one method — `_sendMessage` — and it mirrors what the controller does internally. The workbench's event handler forwards all events to the controllers except `SEND_MESSAGE`, which it routes through `authenticatedFetch` to the correct ChatResource endpoint. The MessagingController still handles channel CRUD, topics, and reply-to state via `/api/channels`. Clean separation, no API change to blocks-ui.

The `BroadcastingChannelManager` decorator survived the refactor — an outcome I didn't expect. QhorusWebSocketBroadcaster handles reaction, member, and topic mutations via CDI events, but `ChannelMutationEvent` has no variants for channel create/delete. The decorator intercepts `ChannelManager.create()` and `delete()` to broadcast those operations. It's the one piece of push infrastructure that stays in chat-app because the qhorus event model doesn't cover it yet.

The full refactoring removed 3,382 lines across backend and frontend. Seven of ten plan tasks are complete. The remaining three — claudony backend, claudony frontend, and end-to-end verification — follow the same pattern. Claudony's workbench will face the same path-split question, and the answer will be the same intercept.
