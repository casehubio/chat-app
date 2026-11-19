---
title: "The Composable API That Wasn't"
date: 2026-07-25
type: diary
subtype: pivot
project: casehub-chat-app
tags: [qhorus, api-design, architecture, composability]
status: draft
entry_type: note
projects: [casehubio/chat-app]
---

# The Composable API That Wasn't

Issue #22 started as "swap a backend." The chat-app had a 1033-line `SqliteChatBackend` that reimplemented everything qhorus already provides — channels, messages, reactions, presence, topics, commitments. Replace it with qhorus services, use H2 instead of SQLite, delete the duplicate code. Straightforward migration.

It wasn't.

## The tension nobody designed

The first thing we found when we traced the code: `ChatResource` injects *both* `ChatPlatform` (from connectors-chat-spi) and `SqliteChatBackend`. Some reads go through the SPI. Some writes bypass it. The boundary between them is invisible. This dual-path architecture exists because the chat-app started as a consumer of the portable Chat API, then bolted on qhorus-like features (commitments, topics, correlation chains) that the Chat API was never designed to carry.

That raised a question bigger than the migration: what's the relationship between the Chat API and qhorus?

## Five approaches, four rejected

We explored this honestly — throwing ideas around, challenging each one until it broke or held.

**Approach 1: Chat API as the human view layer.** The Chat API sits between humans and qhorus. Agents bypass it. A `QhorusChatBackend` implements `ChatBackend`, and the chat-app keeps using `ChatPlatform`. The problem: the Chat API doesn't model commitments, protocols, or type enforcement. The chat-app would still need direct qhorus access for those. The "human view layer" is incomplete — humans see qhorus through two interfaces, not one.

**Approach 2: Unify everything into qhorus.** Move the Chat API's composable capability pattern into qhorus-api. One runtime, one API, one type system. Basic capabilities (Messaging, Reactions, Presence) for Slack. Extended capabilities (Commitments, Protocols, TypeEnforcement) for native frontends. Interface narrowing with generics gives compile-time safety — a Slack connector literally can't call commitment methods because the interface type doesn't expose them.

This was the most elegant design. We spent hours on it — interface hierarchies, CDI composition replacing the builder pattern, basic and extended tiers connected by inheritance. I was excited about it.

**Approach 3: Separate backends entirely.** Slack gets text in, prose out. No attempt to expose qhorus features on external platforms. The enhanced experience is native-only. This simplified the outbound path dramatically — but it meant the composable API was solving a problem we'd just decided not to solve.

**Approach 4: Direct service injection.** The first design review's recommendation. Just inject qhorus services (`ChannelService`, `MessageService`, etc.) and skip the abstraction entirely. The problem: `MessageService` has 13+ transitive dependencies — delivery queues, ledger services, instance registries. The consumer sees everything. No boundary between what it should use and what's internal plumbing.

**Approach 5: Consumer interfaces.** Extended facades (ConsumerMessaging extends MessageDispatcher) plus consumer-safe reader extractions from pipeline-protected stores (CommitmentReader, MessageReader, TopicReader). No basic tier. No composable builder. The chat-spi stays in connectors — independent, proven, not qhorus's concern.

## The composable API's undoing

I wanted Approach 2 to work. The composable pattern is genuinely elegant — capability interfaces that different backends implement to different degrees, with type-safe narrowing for consumers. We designed the interface hierarchy carefully: `StructuredMessaging extends Messaging, MessageDispatcher`, `StructuredChannels extends Channels, ChannelManager`. No duplication — existing facades become part of the hierarchy.

We ran it through adversarial design review twice. Both times, the reviewer stripped it out and fell back to direct service injection. Both times, we pushed back. But then we stress-tested it ourselves — properly, from a skeptical position. And the problems were real.

The `send()` method was a trap. A basic `Messaging.send(channelId, content)` on the qhorus backend calls `dispatch()` with hardcoded defaults — `MessageType.EVENT`, `ActorType.HUMAN`. An agent calling it gets the wrong actor type. A typed message gets the wrong type. The convenience method hides decisions that should be explicit, and the defaults are wrong for half the callers.

The inheritance created upward coupling. If `StructuredMessaging extends Messaging`, and Slack needs `editMessage()` on `Messaging`, that propagates into qhorus's core API. Platform-specific needs should not drive qhorus's API surface.

And qhorus would own someone else's definition. The basic interfaces model what Slack and Discord can do. The connectors team builds those integrations. They should own that definition.

The killer: nobody injects `Messaging` today. The chat-app uses extended interfaces. Slack uses `ConnectorChannelBackend`. The basic tier has no consumer — we'd be designing interfaces for a future migration that might reshape them entirely when real requirements surface.

## What the reviews actually contributed

The design reviews did find real value — just not where I expected. The first review was too aggressive (stripping the design rather than improving it). The second found the gateway integration pattern we'd completely missed: the chat-app should implement `HumanParticipatingChannelBackend`, the existing qhorus pattern for human-facing frontends. `ChannelGateway.fanOut()` delivers ALL messages through one path. The third caught CDI ambiguity issues, the `CommitmentStateChangedEvent` pattern for broadcasting commitment state changes (since `post()` runs outside the dispatch transaction), and the TopicManager/TopicReader independence requirement.

The reviews were right about consumer safety: no stores directly exposed to consumers. Every interaction through a validated facade or a read-only reader. Identity resolved from `CurrentPrincipal`. The rule applied universally — no exceptions.

## Where we landed

The final design uses qhorus's existing API taxonomy without extending it:

- **ConsumerMessaging** extends `MessageDispatcher` with query methods. One facade that gives consumers dispatch + history + correlation chain queries.
- **Consumer-safe readers** (CommitmentReader, MessageReader, TopicReader, MembershipReader, ReactionReader, ChannelReader) extracted from pipeline-protected stores. Type-level read-only enforcement.
- **New facades** for genuine API gaps: TopicManager (validated topic admin), PresenceTracker, MembershipManager, ReactionManager, BackendRegistry.
- **Gateway integration** via `HumanParticipatingChannelBackend` + `CommitmentStateChangedEvent` for WebSocket delivery.
- **Embedded profile** for lightweight deployment without infrastructure services.

The connectors-chat-spi stays independent. Two type systems coexist — and that's fine. They serve different consumers.

## The architectural insight

The design journey clarified something about qhorus that hadn't been explicit before. The basic capabilities — reactions, presence, membership — aren't chat features that crept into an agent mesh. They're collaboration primitives that exist because humans participate in structured conversations alongside agents.

An agent needs to know if a human is online before escalating a stalled commitment. A human needs to signal acknowledgment without posting a full message. These are coordination requirements, not social features. The composable API design would have made this boundary structural — basic capabilities as human participation infrastructure, extended capabilities as agent coordination. The final design achieves the same clarity through the interface taxonomy: facades for validated operations, readers for queries, the dispatch pipeline for everything else.

Qhorus isn't becoming a chat product. It's becoming a conversation platform where agents and humans are both first-class. The chat-app is the first native frontend to prove that architecture — and the migration, once complete, will demonstrate that a single dispatch call replaces three manual operations and gains the full 22-step pipeline.

The qhorus-api work is done — 20 files in api, 13 in runtime, all tests passing. The chat-app migration (Tasks 3-8) continues in the next session.
