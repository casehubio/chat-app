---
title: "Controllers without connections"
date: 2026-08-17
entry_type: note
subtype: diary
projects: [casehub-chat-app]
tags: [architecture, blocks-ui, lit-controllers, push, typescript]
series: issue-33-consolidate-channel-ui
status: draft
issue: 33
---

*Continues from [when spec meets code: the broadcasting gap](2026-08-17-mdp01-broadcasting-gap.md).*

Phase 1 gave qhorus its push module and channel REST endpoints. Phase 2 is the other half of the consolidation — six composable Lit reactive controllers in blocks-ui that both chat-app and claudony will consume instead of each rolling their own data management.

The plan had PushController wrapping `createEventConnection` from pages-data — managing both the WebSocket lifecycle and dataset op routing in one controller. I changed the design. PushController is a pure op router: it receives dataset ops via `applyOp()` and dispatches them to registered handlers. No WebSocket. No pages-data dependency.

The reasoning is dependency direction. blocks-ui-channel-activity currently depends on blocks-ui-core, pages-ui-components, and lit. Adding pages-data would couple the component library to a specific transport mechanism. The plan's own tests showed the seam — they called `applyOp()` directly and never touched `hostConnected()`. The WebSocket connection is fifteen lines of host code that varies per app anyway: URL construction, auth tokens, protocol selection. That belongs in the host, not the library.

The controller decomposition follows the read/write split from the spec. ChannelStateController owns the read side — channels, topics, messages from push, filtered by selected channel, grouped into a `channelTree` by space hierarchy. MessagingController owns the write side — REST calls for sending messages, channel CRUD, topic operations. The remaining three controllers (Membership, Reaction, Commitment) each handle one dataset cleanly.

One surprise from TypeScript's strict mode: blocks-ui uses `exactOptionalPropertyTypes`, which means `description: value || undefined` won't compile — you can't assign `undefined` to an optional property. The fix is building the object with required fields first, then conditionally assigning optional fields via type assertion. Not difficult once you know; invisible until the compiler tells you.

The channel dataset now carries `spaceId`, `spaceName`, and `parentSpaceId` from the qhorus-push module built in Phase 1. ChannelStateController builds a `channelTree` from these — a `SpaceNode` hierarchy with nested children and ungrouped root channels. Both workbenches will render the same tree structure; they just lay it out differently.

Phase 3 starts next — refactoring chat-app's backend and frontend to consume these controllers. The ~800-line QhorusWorkbenchElement should drop to ~200 lines of layout code. ChatDemoAdapter and the local push infrastructure get deleted entirely.
