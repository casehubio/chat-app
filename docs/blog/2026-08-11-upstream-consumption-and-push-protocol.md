---
title: "Upstream consumption and push protocol adoption"
date: 2026-08-11
tags: [chat-app, mosaic, pages-push, websocket, real-time]
status: draft
entry_type: note
subtype: diary
---

# Upstream consumption and push protocol adoption

Six issues in a single slot. The original plan was consumption — pull in
the mosaic#119 enhancements that had been developed locally and
upstreamed. But once we started, we kept going.

## The consumption sweep

The dependency infrastructure needed fixing first. The `package.json`
used Yarn's `portal:` protocol in `resolutions`, but the project runs
npm. We moved the cross-repo packages to `optionalDependencies` with
`file:` references so npm resolves them gracefully — present after a
Maven build, absent otherwise. The vite config got conditional aliases
(`fs.existsSync` guards) so the dev server works whether or not sibling
repos are checked out.

Then the theming API. pages-ui-tokens had replaced `injectTheme` /
`applyThemeMode` / `DEFAULT_THEME` with a registry-based `applyTheme`
that takes a preset name. The old API was still in themes.ts but no
longer re-exported from the barrel. Three workbench tests failing with
"injectTheme is not a function" — the kind of silent API removal that
only surfaces at runtime.

From there: LayoutState + LayoutStore for dock panel persistence,
swapping the local task and correlation panels for the canonical
mosaic versions, and adding `<pages-status-dot>` / `<pages-badge>`
to the member panel and channel nav in mosaic.

## The push protocol adoption

This was the substantial piece. I wanted full adoption — not just
adding a persistence layer, but replacing the bespoke WebSocket
protocol entirely.

The existing `ChatWebSocketBroadcaster` managed its own connection set,
its own sequence counter, and broadcast directly to WebSocket sessions.
Every reconnect got a full snapshot of all seven datasets. No durability,
no replay, no gap detection.

pages-push already provides the infrastructure: `EventBroadcaster`
appends to an `EventStore` with per-topic sequence numbers and fans out
via `TopicRegistry`. Clients reconnect with a `since` map and get only
what they missed.

The extraction was clean. `ChatDatasetBuilder` took the column
definitions, row builders, and snapshot logic out of the broadcaster.
`ChatPushWebSocket` handles the `PushRequest.Listen` protocol — since=0
gets a snapshot from the database, since>0 replays from the EventStore
with gap detection (if the earliest replayed seq is too far ahead, fall
back to snapshot).

The broadcaster became a thin facade: each `broadcastXxx` method builds
the PushMessage and calls `eventBroadcaster.broadcast(topic, json)`.
Connection management, sequencing, fan-out — all gone from chat-app code.

On the frontend, the plan was to build a `PushClient` in pages-data.
Claude found that `createEventConnection` already implements the full
push protocol client — per-topic seq tracking, cursor persistence,
since-map reconnection, gap reporting. The whole thing was already there.
The workbench just needed to swap `ConnectionController` for
`createEventConnection` and pipe events to the existing
`ChatDemoAdapter`.

One gotcha worth noting: `EventConnection` only dispatches messages with
`op: "event"`. Raw dataset ops (snapshot, append, replace, remove) are
silently dropped. The server has to wrap everything in
`PushMessage.event(topic, payload)` — including initial snapshots and
replayed events. No error, no warning — just silence. Took a minute to
trace.

## The artefact chip wiring

Last piece: the artefact chips in `channel-message` had cursor and hover
styling but no click handlers. The artifact panel was already built and
mounted in the workbench dock. The `ARTEFACT_SELECTED` event constant
existed in chat-app but not in mosaic's `ChannelEventTopics`. Two
additions to mosaic — the event topic and a click handler with type
icons — and the full chain works: chip click → event dispatch →
workbench catches → artifact panel updates.

## What's next

The `JdbcEventStore` from push-store-jdbc is PostgreSQL-only — H2
doesn't support `TIMESTAMPTZ` or `ON CONFLICT ... RETURNING`.
`InMemoryEventStore` is fine for dev. When chat-app moves to a
PostgreSQL deployment, adding the JDBC store is a single dependency
and CDI auto-discovers it.
