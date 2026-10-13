# Decisions — issue-36-drag-and-drop

## D0: DnD implementation — Pointer Events

**Choice:** Implement drag-and-drop using the Pointer Events API (`pointerdown`/`pointermove`/`pointerup`) with manual ghost element and drop zone tracking. Not the HTML5 Drag and Drop API.
**Alternatives:**
- HTML5 Drag and Drop API — built-in browser support (`draggable`, `dragover`, `drop`). Known issues with shadow DOM event targeting in Lit components, limited drag preview customization, and cross-browser inconsistencies with custom elements.
- Third-party library (Sortable.js, dnd-kit) — adds a dependency for a self-contained interaction. Overkill for a demo app with a single drag context.
**Rationale:** Shadow DOM + Lit makes HTML5 DnD unreliable — `dragover`/`drop` events don't cross shadow boundaries cleanly. Pointer Events give full control over visual feedback and work naturally with Lit's event system. The codebase already uses this pattern (`SwipeController` in the workbench uses `pointerdown`/`pointermove`/`pointerup`).
**Trade-offs:** More code than HTML5 DnD (~80 lines vs ~30). Full control over behavior justifies this at the cost of boilerplate.
**Sources:** `SwipeController` (swipe-controller.ts), `channel-nav.ts` rendering, GE-20260426-90563c (preventDefault suppresses click), GE-20260811-117018 (pointer-events inheritance)
**Exploration:** quick
**Status:** captured

## D1: Channel position field — integer with gaps

**Choice:** Add `Integer position` to the `Channel` record (nullable — null means "append at end"). Store positions with gaps (0, 1000, 2000, ...) so insertions between existing channels don't require renumbering. When inserting between position 1000 and 2000, assign 1500. Renumber the entire space only when a gap reaches 1.
**Alternatives:**
- Fractional positions (e.g., "1.5") — infinite insertions without renumbering but complex comparison and display logic
- Simple sequential integers (0, 1, 2) — simplest but requires renumbering all channels below the insertion point on every reorder
**Rationale:** Integer-with-gaps is the standard pattern for ordered lists in databases. At demo scale (3-10 channels per space), renumbering is effectively never needed. Server-persisted ordering (not client-side localStorage) is the right choice — shared across all users, persistent across sessions, and no additional state management. Client-side ordering only makes sense when different users need different views, which isn't a demo requirement.
**Trade-offs:** Nullable position requires null-handling in the sort comparator (null sorts to end). Adds a new domain concept to qhorus (Channel, ChannelStore, ChannelService, push columns, REST endpoint). Mechanical but touches multiple modules. Not per-user — all users see the same order.
**Sources:** `Channel.java` record (qhorus-api), `ChannelStore` SPI, `QhorusDatasetBuilder` channel columns
**Exploration:** quick
**Status:** revised (R1-09 global vs client-side ordering acknowledged, R1-10 client-side alternative dismissed)

## D2: Visual feedback — ghost + drop zone indicators

**Choice:** During drag: a semi-transparent clone of the channel item follows the cursor (absolute positioned, `opacity: 0.7`). Valid drop zones — space headers and gaps between channel items — show a 2px insertion line. The dragged channel's original position shows a dimmed placeholder. Invalid zones get no indicator.
**Alternatives:**
- Minimal (cursor change only) — less visual clutter but worse UX; user can't see where the drop will land
- Full preview (show the tree rearranged in real-time) — best UX but complex to implement and distracting during short drags
**Rationale:** Ghost + insertion line is the standard tree-view DnD pattern (file explorers, IDE project trees). Sufficient feedback for the user to understand where the channel will land without over-engineering the visual.
**Trade-offs:** Ghost element needs z-index management and cleanup on drag cancel. Bounded complexity.
**Sources:** Standard tree-view DnD patterns (VS Code, Finder, IntelliJ project tree)
**Exploration:** quick
**Status:** captured

## D3: Events — reuse MOVE_CHANNEL_TO_SPACE + new REORDER_CHANNEL

**Choice:** Moving a channel between spaces via DnD emits the existing `MOVE_CHANNEL_TO_SPACE` event from #34. Reordering within a space emits a new `REORDER_CHANNEL` event with `{ channelId: string, position: number }`. The workbench wires `REORDER_CHANNEL` to a new `PUT /api/channels/{id}/position` endpoint.
**Alternatives:**
- Single unified event for all DnD outcomes — conflates two distinct operations (space assignment vs ordering) into one payload. The workbench handler becomes a conditional branch.
**Rationale:** Move-to-space and reorder are semantically different operations with different REST endpoints and different state mutations. Separate events keep the handler logic clean and match the existing event pattern from #34.
**Trade-offs:** Two code paths in the DnD drop handler — one for cross-space drops, one for within-space reorders. Straightforward conditional.
**Depends on:** D1 (position field exists for the reorder event to target)
**Sources:** `ChannelEventTopics` (events.ts), `MOVE_CHANNEL_TO_SPACE` from #34 spec D4, `qhorus-workbench.ts` event handler
**Exploration:** quick
**Status:** revised (R1-17 compound operation addressed by D4)

## D4: Compound move+position — single atomic endpoint

**Choice:** Extend the existing `PUT /api/channels/{id}/space` endpoint to accept an optional `position` field in the request body: `{ spaceId: UUID | null, position?: Integer }`. A cross-space DnD drop sends one request that atomically sets both space and position. A within-space reorder sends the same endpoint with the channel's current `spaceId` and the new `position`.
**Alternatives:**
- Separate endpoints — two sequential calls for cross-space moves. Race condition: space changes but position call fails, leaving channel at wrong position.
- New dedicated `PUT /api/channels/{id}/move` — creates a third mutation path. Unnecessary indirection.
**Rationale:** The existing endpoint already handles space assignment. Adding `position` to its request body is backward-compatible (field is optional). One call, one transaction, no partial-failure state.
**Trade-offs:** Overloads the space endpoint with ordering concerns. Acceptable — both are "where does this channel go?" operations.
**Depends on:** D1 (position field exists), D3 (events carry position for cross-space drops)
**Sources:** `ChannelResource` `PUT /{id}/space` (qhorus runtime), `SpaceAssignmentRequest`, decision review R1-17
**Exploration:** quick (surfaced by decision review)
**Status:** captured
