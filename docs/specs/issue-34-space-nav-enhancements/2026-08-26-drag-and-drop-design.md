# Drag-and-Drop for Channel Nav — Design Spec

**Issue:** casehubio/chat-app#36
**Parent:** casehubio/chat-app#7 (space-based channel hierarchy)
**Dependencies:** #34 (space CRUD, context menus, MOVE_CHANNEL_TO_SPACE event)
**Date:** 2026-08-26

## Summary

Add drag-and-drop to the channel nav for moving channels between spaces and reordering channels within a space. Uses the Pointer Events API (not HTML5 DnD) for shadow DOM compatibility. Requires a new `position` field on the `Channel` record in qhorus and an extension to the existing `PUT /api/channels/{id}/space` endpoint to accept position alongside space assignment.

Context menus for space management were completed in #34. This issue adds only the drag-and-drop interaction.

## Scope

**In scope:**
- DnD to move channels between spaces (including to/from ungrouped)
- DnD to reorder channels within a space
- New `Integer position` field on `Channel` record (qhorus-api)
- `ChannelService.setPosition()` method (qhorus-runtime)
- Extended `PUT /api/channels/{id}/space` endpoint with optional `position` field
- `position` column in push channel snapshot
- `REORDER_CHANNEL` event topic + `MoveChannelToSpacePayload` extended with optional `position`
- DnD controller as a Lit ReactiveController in blocks-ui `ChannelNavElement`
- Ghost element + insertion line visual feedback
- Optimistic reorder in `ChannelStateController`
- Workbench wiring for `REORDER_CHANNEL` event
- Tests at all layers

**Out of scope:**
- Space reordering (#37 — nesting UI is the right context)
- Keyboard-accessible reordering (context menu from #34 is the keyboard path; DnD is mouse/touch)
- Touch long-press gesture differentiation (demo app, desktop-first)
- Auto-scroll during drag (nav is typically short enough; add if needed post-merge)

## Architecture

```
qhorus-api           qhorus-runtime        qhorus-push           blocks-ui              chat-app
Channel.position     ChannelService        CHANNEL_COLUMNS       ChannelNavDnD          workbench
  Integer nullable     setPosition()         +position col         ReactiveController     REORDER event
ChannelStore         ChannelResource       buildChannelSnapshot  ChannelNavElement        → REST
  put() (unchanged)    PUT /{id}/space       +position in row      ghost + drop zones     optimistic
                       +position field                            ChannelStateController    update
                                                                   applyReorder()
                                                                   channelTree sort
```

### Layer 1: qhorus-api — position field

Add `Integer position` to the `Channel` record. Nullable — `null` means "no explicit order, sort by name or creation time."

```java
public record Channel(
        // ... existing fields ...
        UUID spaceId,
        Integer position,      // NEW — nullable, display order within space
        List<String> reviewerInstances,
        // ... rest ...
```

Update `Builder` with `position(Integer v)` method. Update `toBuilder()` to carry position. Update `fromRequest()` to pass `req.position()` if `ChannelCreateRequest` has it (optional — default null).

The telescoping constructors pass `null` for position to maintain backward compatibility.

### Layer 2: qhorus-runtime — service + endpoint

**ChannelService** — add position mutation:

```java
public Channel setPosition(UUID channelId, Integer position) {
    Channel ch = channelStore.find(channelId)
        .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
    Channel updated = ch.toBuilder().position(position).build();
    channelStore.put(updated);
    return updated;
}
```

**ChannelResource** — extend `PUT /{id}/space` to accept optional position:

```java
record SpaceAssignmentRequest(UUID spaceId, Integer position) {}

@PUT
@Path("/{id}/space")
public Response setSpace(@PathParam("id") String id, SpaceAssignmentRequest req) {
    var channel = resolve(id);
    if (channel == null) return error(404, "Channel not found");
    try {
        Channel updated;
        if (req.spaceId() != null || channel.spaceId() != null) {
            updated = spaceService.moveChannelToSpace(channel.id(), req.spaceId());
        } else {
            updated = channel;
        }
        if (req.position() != null) {
            updated = channelService.setPosition(updated.id(), req.position());
        }
        return Response.ok(toResponse(updated)).build();
    } catch (IllegalArgumentException e) {
        return error(400, e.getMessage());
    }
}
```

When both `spaceId` and `position` are provided, both are applied atomically within the same request. When only `position` is provided (within-space reorder), `spaceId` can be the channel's current space.

### Layer 3: qhorus-push — position in channel snapshot

Add `position` to `CHANNEL_COLUMNS` after `unreadCount`:

```java
static final String[] CHANNEL_COLUMNS = {
    "id", "name", "semantic", "description", "paused",
    "spaceId", "spaceName", "parentSpaceId", "unreadCount",
    "position"  // NEW — index 9
};
```

In `buildChannelSnapshot`, include `channel.position()` (as string, null → empty string) at index 9.

### Layer 4: blocks-ui — DnD controller + nav interaction

#### New event topic and payload

In `events.ts`:

```typescript
export const ChannelEventTopics = {
  // ... existing ...
  REORDER_CHANNEL: 'channel:reorder',
} as const;

export interface ReorderChannelPayload {
  readonly channelId: string;
  readonly spaceId: string | null;
  readonly position: number;
}
```

Extend `MoveChannelToSpacePayload` with optional position:

```typescript
export interface MoveChannelToSpacePayload {
  readonly channelId: string;
  readonly spaceId: string | null;
  readonly position?: number;
}
```

#### ChannelStateController — position parsing + sorting + optimistic reorder

**_toChannel update** — parse `position` from row index 9:

```typescript
private _toChannel(row: unknown[]): QhorusChannel {
    // ... existing field parsing ...
    const position = row[9] as string;
    if (position) (ch as { position: number }).position = parseInt(position, 10) || 0;
    return ch;
}
```

**channelTree getter update** — sort channels within each space by position (null last), then by name:

```typescript
// After assigning channels to spaces, sort each space's channels
for (const node of spaceMap.values()) {
    node.channels.sort((a, b) => {
      const pa = (a as any).position ?? Number.MAX_SAFE_INTEGER;
      const pb = (b as any).position ?? Number.MAX_SAFE_INTEGER;
      if (pa !== pb) return pa - pb;
      return a.name.localeCompare(b.name);
    });
}
```

**applyReorder method** — optimistic position update:

```typescript
applyReorder(channelId: string, position: number) {
    this.channels = this.channels.map(ch =>
      ch.id === channelId ? { ...ch, position } as typeof ch : ch
    );
    this._host.requestUpdate();
}
```

#### ChannelNavElement — DnD interaction

Add a `DragController` as a Lit ReactiveController inside `ChannelNavElement`. Manages the full drag lifecycle:

**State:**
```typescript
@state() private _dragState: {
  channelId: string;
  ghostEl: HTMLElement;
  startY: number;
  dropTarget: { type: 'space' | 'position'; spaceId: string | null; position: number } | null;
} | null = null;
```

**Drag start** — on `pointerdown` on a channel item:
1. Record the channel being dragged and the start Y position
2. After 150ms hold (to distinguish from click), create ghost element and enter drag mode
3. Set `pointer-capture` on the element to receive all pointer events
4. Add `pointermove` and `pointerup` listeners

**Drag move** — on `pointermove`:
1. Update ghost element position (follow cursor)
2. Hit-test against space headers and channel items to determine drop zone
3. Show insertion line at the nearest valid drop position
4. Drop zone detection: compare pointer Y against channel item bounding rects. If pointer is in the top half of a channel item, insert before it. Bottom half, insert after it. If over a space header, move to that space (at end).

**Drag end** — on `pointerup`:
1. If valid drop target exists:
   - **Same space, different position**: emit `REORDER_CHANNEL` with `{ channelId, spaceId, position }`
   - **Different space**: emit `MOVE_CHANNEL_TO_SPACE` with `{ channelId, spaceId, position }`
2. Remove ghost element and insertion line
3. Clear drag state

**Drag cancel** — on `Escape` key or `pointercancel`:
1. Remove ghost element and insertion line
2. Clear drag state without emitting events

**Ghost element:**
```typescript
private _createGhost(channel: QhorusChannel): HTMLElement {
    const ghost = document.createElement('div');
    ghost.textContent = `# ${channel.name}`;
    ghost.style.cssText = 'position:fixed;pointer-events:none;opacity:0.7;' +
      'padding:4px 8px;background:var(--pages-neutral-2);border-radius:4px;' +
      'font-size:13px;z-index:1000;box-shadow:0 2px 8px rgba(0,0,0,0.15);';
    document.body.appendChild(ghost);
    return ghost;
}
```

The ghost is appended to `document.body` (not shadow DOM) so it renders above all shadow boundaries.

**Insertion line:**
```typescript
private _showInsertionLine(y: number) {
    if (!this._insertionLine) {
      this._insertionLine = document.createElement('div');
      this._insertionLine.style.cssText = 'position:absolute;left:0;right:0;height:2px;' +
        'background:var(--pages-accent-7,#818cf8);pointer-events:none;z-index:50;';
      this.shadowRoot!.appendChild(this._insertionLine);
    }
    this._insertionLine.style.top = `${y}px`;
}
```

**Position calculation on drop:**

When dropping between channels at positions 1000 and 2000, the new position is 1500. When dropping at the end of a space, position is `lastChannel.position + 1000` (or 0 if the space is empty). When dropping before the first channel, position is `firstChannel.position / 2` (integer division, minimum 1).

**Channel item markup update** — add `pointerdown` handler:

```typescript
private _renderChannelItem(channel: QhorusChannel): unknown {
    return html`
      <li class="channel-item ${this._dragState?.channelId === channel.id ? 'dragging' : ''} ..."
          @pointerdown="${(e: PointerEvent) => this._onDragStart(e, channel)}"
          @click="${() => this.handleChannelClick(channel.id)}"
          @contextmenu="${(e: MouseEvent) => this._showContextMenu(e, 'channel', channel)}">
        ...
      </li>
    `;
}
```

#### CSS additions

```css
.channel-item.dragging { opacity: 0.3; }
.space-header.drop-target { background: var(--pages-accent-2, #eef2ff); }
```

### Layer 5: chat-app — workbench event wiring

Add `REORDER_CHANNEL` handler to `_onChatEvent` in `qhorus-workbench.ts`:

```typescript
else if (topic === ChannelEventTopics.REORDER_CHANNEL) {
    this._reorderChannel(payload as ReorderChannelPayload);
}
```

```typescript
private async _reorderChannel(payload: ReorderChannelPayload) {
    try {
        const res = await authenticatedFetch(`/api/channels/${payload.channelId}/space`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ spaceId: payload.spaceId, position: payload.position }),
        });
        if (!res.ok) { const err = await res.json(); throw new Error(err.error ?? 'Unknown error'); }
        this._channels.applyReorder(payload.channelId, payload.position);
    } catch (e) { this._showError(`Failed to reorder channel: ${(e as Error).message}`); }
}
```

Update `_moveChannelToSpace` to pass position when present in the payload:

```typescript
private async _moveChannelToSpace(payload: MoveChannelToSpacePayload) {
    try {
        const body: Record<string, unknown> = { spaceId: payload.spaceId };
        if (payload.position != null) body.position = payload.position;
        const res = await authenticatedFetch(`/api/channels/${payload.channelId}/space`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        // ... rest unchanged
    }
}
```

## Known Limitations

**No keyboard-accessible reordering.** DnD is mouse/touch only. The context menu from #34 ("Move to Space" submenu) remains the keyboard-accessible path for space assignment. Position-specific reordering is only available via DnD.

**No auto-scroll.** If the nav is scrolled and the user drags to the edge, the nav does not auto-scroll. At demo scale the nav is typically short enough that scrolling isn't needed. Add post-merge if needed.

**No space reordering.** Only channels can be dragged. Space reordering is #37 scope.

**Position is global, not per-user.** All users see the same channel order within a space. This is correct for a shared workspace at demo scale.

## Cross-Repo Impact

| Repo | Module | Changes | Lines |
|------|--------|---------|-------|
| **qhorus** | qhorus-api | `Channel` record: add `Integer position`, Builder method, toBuilder, telescoping ctors | ~20 |
| **qhorus** | qhorus-runtime | `ChannelService.setPosition()`, `ChannelResource` extend `PUT /{id}/space` with position | ~15 |
| **qhorus** | qhorus-push | `CHANNEL_COLUMNS` add position, `buildChannelSnapshot` include position | ~5 |
| **blocks-ui** | channel-activity | `events.ts` new topic + payload, `channel-state-controller.ts` position parsing + sorting + applyReorder, `channel-nav.ts` DnD controller + ghost + drop zones + CSS | ~200 |
| **chat-app** | workbench | `REORDER_CHANNEL` handler, update `_moveChannelToSpace` for position | ~20 |

Total: ~260 lines across 5 modules in 3 repos.

## Testing Strategy

### Backend (Java — JUnit)
- `Channel` record includes position in builder and toBuilder
- `ChannelService.setPosition()` updates position on channel
- `PUT /api/channels/{id}/space` with position sets both space and position
- `PUT /api/channels/{id}/space` without position preserves existing position
- `buildChannelSnapshot` includes position column

### Frontend (TypeScript — vitest)

**channel-state-controller.test.ts:**
- `_toChannel` parses position from row index 9
- `channelTree` sorts channels within space by position (null last, then name)
- `applyReorder` updates channel position
- Channels without position sort by name (backward compat)

**channel-nav.test.ts:**
- Pointer down + hold on channel item enters drag mode
- Pointer move updates ghost position
- Pointer up on different space emits MOVE_CHANNEL_TO_SPACE with position
- Pointer up on same space different position emits REORDER_CHANNEL
- Escape cancels drag without emitting
- Click (no hold) still triggers channel select (not drag)

**qhorus-workbench.test.ts:**
- REORDER_CHANNEL event triggers PUT /api/channels/{id}/space with position
- MOVE_CHANNEL_TO_SPACE with position includes position in body

## Spec Review Findings (addressed during implementation)

- R1-02/R1-03: Endpoint and service methods need `@Transactional` for atomic compound operations
- R1-04: Verify actual `CHANNEL_COLUMNS` layout in qhorus-push via IntelliJ before adding position
- R1-05: `broadcastChannelAppend` must include position column in delta broadcasts
- R1-06: `QhorusChannel` TypeScript type needs `position?: number` field
- R1-07: `_moveChannelToSpace` should also call `applyReorder` when position is present
- R1-09: `ChannelResponse` DTO needs position field for REST responses
- R1-10: ChannelStore implementations (H2/JPA entity) need position column
- R1-13: 150ms hold delay must suppress click (track `_dragStarted` flag)

## References

- `Channel.java` (qhorus-api) — record, builder, 24 fields currently
- `ChannelResource.java` (qhorus-runtime) — `PUT /{id}/space`, `SpaceAssignmentRequest`
- `ChannelService.java` (qhorus-runtime) — channel mutations
- `channel-nav.ts` (blocks-ui) — `_renderChannelItem`, `_renderSpaceGroup`, tree rendering
- `channel-state-controller.ts` (blocks-ui) — `channelTree` getter, `_toChannel`, `applyMoveChannel`
- `events.ts` (blocks-ui) — `ChannelEventTopics`, payload interfaces
- `swipe-controller.ts` (chat-app) — pointer event pattern reference
- `qhorus-workbench.ts` (chat-app) — `_onChatEvent`, `_moveChannelToSpace`
- [GE-20260426-90563c] — preventDefault on mousedown suppresses click
- [GE-20260811-117018] — pointer-events:none blocks clicks on dynamically appended children
- [GE-20260826-ee71b5] — DOM event bubbling with stopPropagation for nested DnD
- Issue #34 spec — D3 (move-to-space submenu), D4 (event pattern)
- Decision review R1-09 (global vs client-side ordering), R1-17 (compound operation)
