---
title: "The last mile"
date: 2026-08-18
series: channel-consolidation
entry: 4
issue: 33
tags: [integration, verification, push-protocol, esbuild, seed-data]
---

# The last mile

Four sessions of refactoring across four repos — qhorus-push module, six composable controllers, two app rewrites — and every unit test green. All 589 claudony tests. All 337 blocks-ui tests. All 18 chat-app tests. The plan said Task 10 was "end-to-end integration verification." It sounded like a checkbox.

It wasn't.

## The gap between green and working

The first thing that happened when we started the app: blank page. Not a crash, not an error banner — a white rectangle with zero console output. The Quarkus health endpoint returned 200. The tests all passed. The app rendered nothing.

The root cause chain ran five layers deep. A stale Vite dev server from another project (casehub-life, "Household Hub") was sitting on port 5173. Quinoa's dev proxy latched onto it and served the wrong app entirely. Kill that, restart — blank again. This time a `pages-theme-picker` duplicate registration killed the module import chain. The same web component loaded from two physical paths: one via chat-app's `.casehub-packages`, another via blocks-ui's `node_modules` symlink chain. esbuild bundled both copies because its resolve plugin only handled deep imports (`@pkg/subpath`), not bare imports (`@pkg`).

Fix the resolver, rebuild — blank page, different error. `@casehubio/pages-ui/dist/dsl/builders.js` couldn't resolve because the Vite alias mapped `pages-ui` to `dist/`, so `dist/dsl/builders.js` became `dist/dist/dsl/builders.js`. Fix with two-layer aliases: `pkg/dist → dist` (identity), `pkg → dist` (mapping). Rebuild — now `commitment-viz/src/range-decorator.ts` fails because the `.casehub-packages` TypeScript source references a monorepo `tsconfig.base.json` that doesn't exist outside blocks-ui. Change the import to use the compiled `dist/` output.

Five distinct resolution bugs, each invisible to unit tests.

## When the workbench finally rendered

Login screen appeared. Logged in. Workbench shell rendered — dock strip, nav tabs, identity widget. "Reconnecting..." for the WebSocket. The push connection to `/ws/push` failed with "HTTP Authentication failed." The JWT was valid (REST endpoints accepted it). The token was in the query parameter, not the Authorization header.

SmallRye JWT's security filter runs on all HTTP requests, including WebSocket upgrades. It only checks the `Authorization` header. No header, no credentials, reject. The `HttpUpgradeCheck` that validates the query-param token never got a chance to run. Fix: explicit `permit` policy on `/ws/*` — the upgrade check handles auth independently.

Reconnection resolved. Channels loaded — four from seed data, badge showing "4." But the channel list was invisible. The `<channel-nav>` element existed in the DOM with all four channels bound to it. It had no shadow root. The custom element wasn't registered. The blocks-ui components register as `blocks-channel-nav`, `blocks-channel-feed`, `blocks-channel-input` — the workbench templates used `channel-nav`, `channel-feed`, `channel-input`. Five tags, all missing the `blocks-` prefix.

## What tests can't tell you

The unit tests verified that `ChannelStateController` parsed push data correctly. They verified that `MessagingController` routed events. They verified that `ChatResource` returned 200 for message posts. All true, all green, all useless for catching:

- A `MemberRole.MEMBER` enum value that doesn't exist (it's `PARTICIPANT`)
- A `CommitmentState.RESOLVED` that should be `FULFILLED`
- COMMAND messages rendering without content text because `_renderCommitmentBar` replaced the entire `.content` div with a commitment-range-bar widget
- The `MessagingController` posting to `/api/channels/{id}/messages` (qhorus generic endpoint) instead of `/api/chat/{id}/messages` (ChatResource with auto-join, commitment creation, topic resolution)

Each of these passed every automated check we had. Each broke the app for real users.

## The seed nobody wrote

There was never a message seed in this repo's history. The old SQLite backend accumulated demo data across dev sessions in a persistent `.db` file. When qhorus replaced SQLite, the persistent file went away and nobody replaced it with a Flyway seed. The app started empty every time.

We wrote one: four channels, 27 messages across a v2.0 release planning scenario. Human and agent participants. COMMAND messages that create commitments. Threaded replies with STATUS and DONE. Named topics including a resolved one. Reactions. The kind of data that makes every panel demonstrable — not synthetic test fixtures, but a conversation a real team might have.

## What the code review found

After verification, we ran a four-repo parallel code review. The blocks-ui finding was the most significant: the `MessagingController` was posting messages to the wrong endpoint. Every message sent from the UI would bypass ChatResource's orchestration — no auto-join, no commitment creation, no topic resolution. The feature looked like it worked (message appeared in the feed via push) but the side effects never fired.

The qhorus review caught unhandled `NumberFormatException` and `IllegalArgumentException` in ChannelResource and SpaceResource — bad input returning 500 instead of 400. The chat-app review found an empty `close()` method that would leak ghost channels in push snapshots.

## Looking forward

The consolidation is architecturally complete. Four repos, ten tasks, all landed. The push protocol works end-to-end. The composable controllers work. Both apps consume foundation without depending on each other.

What's still rough: the esbuild and Vite configs are more complex than they should be — the resolve plugins exist because the `.casehub-packages` WebJar pattern doesn't produce packages that standard bundler resolution can handle without help. That's not a chat-app problem; it's a packaging problem that will bite every new consumer. Worth revisiting when the next app needs channels.
