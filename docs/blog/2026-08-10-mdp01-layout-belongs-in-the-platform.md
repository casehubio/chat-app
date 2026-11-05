---
title: "Layout belongs in the platform"
date: 2026-08-10
author: mdp
entry_type: note
subtype: diary
tags: [pages, layout, workbench, chat-app, design]
status: draft
---

The chat-app workbench has 770 lines of Lit element. About 370 of those are layout: CSS flexbox panels, dock strip buttons, tablet tab switching, phone drawers with swipe gestures, responsive breakpoints. The other 400 are app logic — data state, WebSocket handling, event coordination, REST calls.

That split is wrong. Layout is infrastructure. Every app that wants a dockable panel workbench rebuilds the same CSS, the same media queries, the same drawer transitions. The chat-app shouldn't own this.

Pages already has the pieces: `split()` for resizable panes, `dockBar()` for panel toggles, `dockWorkbench()` for composing them into an IDE-style layout, `LayoutStore` for persisting split ratios and dock state. What it doesn't have is the ability to render into a shadow root (the workbench is a Lit element) or a drawer primitive for phone layouts.

I worked through the design with Claude today. The interesting moment was when I pushed back on the recommended approach — option 1, "use only the data model, keep the CSS layout." That's the safe choice. Low risk, quick delivery, doesn't require changing pages. It's also the wrong direction. Every line of bespoke layout CSS in the workbench is a line that should live in pages instead.

The principle: when pages can't do what you need, improve pages. Don't work around it. The chat-app is one consumer. Pages serves all of them.

Five decisions came out of the session. The one worth noting: both left and right panel zones should be exclusive — one panel visible per side. I started to ask about this, then stopped and thought through it from first principles instead. Nav is transient (pick a channel, done). Tasks is monitoring. Correlation and artifacts are triggered by message selection. No workflow needs two panels on the same side simultaneously. Exclusive switching maps directly to `dockWorkbench()` with no API changes needed.

The plan is three vertical slices. Desktop first — fill the shadow root gap in pages, wire up `dockWorkbench()`, get resizable split panes working. Tablet second. Phone last, which is where the drawer primitive comes in. Each slice delivers a working layout mode and validates the pages additions with a real consumer.

The npm dependency work earlier in the session was less interesting but necessary. The Maven SNAPSHOT consumption pattern — where `mvn initialize` unpacks pages and mosaic packages into `.casehub-packages/`, and npm resolves from there — had several broken edges. Yarn `resolutions` silently ignored by npm. `file:` protocol cascading into devDependency resolution failures. `npx --prefix` not changing the working directory. Each one subtle, each one an hour lost. All four went into the garden.
