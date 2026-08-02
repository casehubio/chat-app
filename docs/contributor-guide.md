# casehub-chat-app -- Contributor Guide

> Internal architecture and extension points for platform builders working on chat-app.

**GitHub:** [casehubio/chat-app](https://github.com/casehubio/chat-app)

---

## Module Structure

Single module. Java backend (ChatResource, SqliteChatBackend, ChatWebSocket) and frontend app shell (QhorusWorkbench, ChatDemoAdapter, SwipeController) in one deployable unit.

## Internal Architecture

The backend implements `ChatBackend` (from casehub-connectors/chat-spi) with SQLite + HikariCP for dev/demo persistence. REST endpoints expose channels, messages, replies, reactions, members, and presence. The WebSocket layer broadcasts dataset operations (snapshot/append/replace/remove) to connected clients.

The frontend is a Lit-based app shell that consumes `@casehubio/blocks-ui-channel-activity` components. QhorusWorkbench provides responsive layout with dock strip and theme toggle. ChatDemoAdapter handles WebSocket protocol parsing. SwipeController adds edge-swipe drawer gestures as a Lit reactive controller.

The app implements `HumanParticipatingChannelBackend` for outbound WebSocket delivery via the qhorus gateway fan-out pattern.

## Depended On By

None currently.

## Current State

Scaffold complete. Java backend (5 source files, 3 test files, 40 tests) and frontend app shell (workbench, adapter, swipe controller, auth, 79 tests) migrated from connectors/chat-demo.
