# Space CRUD Operations in Channel Nav — Design Spec

**Issue:** casehubio/chat-app#34
**Parent:** casehubio/chat-app#7 (space-based channel hierarchy)
**Dependencies:** #7 (CLOSED — tree rendering, push data, seed data all complete)
**Date:** 2026-08-25

## Summary

Add create, rename, delete space and move-channel-between-spaces to the channel nav sidebar. The backend infrastructure is complete (`SpaceResource` at `/api/spaces` auto-mounts via Quarkus JAR discovery). The work is primarily frontend: context menus, inline rename, event wiring, and local state updates after API success. Two cross-repo changes are needed in qhorus: a `moveChannelToSpace` REST endpoint, and a transactional `deleteWithChannelReassignment` method.

## Scope

**In scope:**
- Context menu on space headers: Rename, Delete, Create Channel Here
- Context menu on channel items: Move to Space (submenu listing available spaces)
- "+ Create Space" button at top of nav (next to space filter)
- Inline rename UX for spaces
- Confirmation dialog for space deletion (with channel-move explanation)
- New space event topics: CREATE_SPACE, RENAME_SPACE, DELETE_SPACE, MOVE_CHANNEL_TO_SPACE
- Workbench event handlers for space events + wire existing CREATE_CHANNEL / DELETE_CHANNEL
- Local state updates in ChannelStateController (after API success) with pendingSpaces overlay
- Cross-repo: `PUT /api/channels/{id}/space` endpoint in qhorus ChannelResource
- Cross-repo: `SpaceService.deleteWithChannelReassignment()` in qhorus (transactional delete)
- User-visible error feedback for failed operations
- Tests at all layers

**Out of scope:**
- Empty space visibility rules (#35) — this spec adds a minimal pendingSpaces overlay but the full visibility story is #35
- Drag-and-drop (#36) — after #34 merges, update #36 to remove context menus from its scope (only drag-and-drop remains)
- Multi-level space nesting UI (#37)
- Channel rename — not in #34 scope
- Touch-friendly alternatives to context menus

## Architecture

The feature touches three layers across two repos:

```
SpaceResource          ChannelResource         ChannelNavElement        Workbench
  (qhorus)              (qhorus)               (blocks-ui)            (chat-app)
  /api/spaces/*         /api/channels/{id}/space  context menu           event → REST
  deleteWithReassign    [NEW endpoint]            events                 state update
  [NEW method]                                                           error feedback
```

### Layer 1: Cross-repo — new REST endpoint (qhorus)

`SpaceService.moveChannelToSpace(channelId, spaceId)` exists but has no REST exposure. Add to `ChannelResource`:

```java
@PUT
@Path("/{id}/space")
public Response setSpace(@PathParam("id") String id, SpaceAssignmentRequest req) {
    var channel = resolve(id);
    if (channel == null) return error(404, "Channel not found");
    try {
        var updated = spaceService.moveChannelToSpace(channel.id(), req.spaceId());
        return Response.ok(toResponse(updated)).build();
    } catch (IllegalArgumentException e) {
        return error(400, e.getMessage());
    }
}

record SpaceAssignmentRequest(UUID spaceId) {}
```

`spaceId: null` unassigns the channel from any space. The `SpaceService.moveChannelToSpace()` already handles this (line 165: `if (spaceId != null)` guard).

**SpaceService injection:** `ChannelResource` does not currently inject `SpaceService`. Add `@Inject SpaceService spaceService` alongside the existing `ChannelService` injection.

#### Transactional delete with channel reassignment

`SpaceService.delete()` blocks if the space has channels or child spaces. A client-side multi-step saga (move channels, then delete) is non-atomic — partial failures leave channels scattered with no cleanup. Add a transactional method:

```java
@Transactional
public void deleteWithChannelReassignment(UUID spaceId) {
    Space space = spaceStore.find(spaceId)
        .orElseThrow(() -> new IllegalArgumentException("Space not found: " + spaceId));
    if (spaceStore.hasChildren(spaceId)) {
        throw new IllegalStateException("Cannot delete space with child spaces: " + spaceId);
    }
    // Move all channels to root (spaceId = null) within the same transaction
    List<Channel> channels = channelStore.findBySpace(spaceId);
    for (Channel ch : channels) {
        channelStore.put(ch.toBuilder().spaceId(null).build());
    }
    spaceStore.delete(spaceId);
}
```

Expose via `SpaceResource`:

```java
@DELETE
@Path("/{id}")
@QueryParam("reassign") // ?reassign=true triggers the new method
public Response delete(@PathParam("id") String id,
                       @QueryParam("reassign") @DefaultValue("false") boolean reassign) {
    // ... existing UUID parsing ...
    if (reassign) {
        spaceService.deleteWithChannelReassignment(uuid);
    } else {
        spaceService.delete(uuid);
    }
    return Response.noContent().build();
}
```

The existing strict `delete()` remains the default. The `?reassign=true` query parameter opts into the transactional reassignment. The frontend always uses `?reassign=true`.

**ChannelStore.findBySpace:** If `findBySpace(UUID spaceId)` doesn't exist, use `ChannelQuery.bySpaceId(spaceId)` which is already in the API.

### Layer 2: Event system (blocks-ui)

**New event topics** in `events.ts`:

```typescript
export const ChannelEventTopics = {
  // ... existing topics ...
  CREATE_SPACE: 'space:create',
  RENAME_SPACE: 'space:rename',
  DELETE_SPACE: 'space:delete',
  MOVE_CHANNEL_TO_SPACE: 'channel:move-to-space',
} as const;
```

**New payload interfaces:**

```typescript
export interface CreateSpacePayload {
  readonly name: string;
}

export interface RenameSpacePayload {
  readonly spaceId: string;
  readonly newName: string;
}

export interface DeleteSpacePayload {
  readonly spaceId: string;
}

export interface MoveChannelToSpacePayload {
  readonly channelId: string;
  readonly spaceId: string | null;  // null = ungrouped
}
```

`CREATE_CHANNEL` with `spaceId` set handles "Create Channel Here" — no new event needed.

### Layer 3: ChannelStateController — pendingSpaces overlay (blocks-ui)

Add a `pendingSpaces` array for optimistic display of newly created spaces:

```typescript
export class ChannelStateController implements ReactiveController {
  // ... existing fields ...
  private _pendingSpaces: Space[] = [];
  
  addPendingSpace(space: Space) {
    this._pendingSpaces = [...this._pendingSpaces, space];
    this._host.requestUpdate();
  }
  
  removePendingSpace(spaceId: string) {
    this._pendingSpaces = this._pendingSpaces.filter(s => s.id !== spaceId);
    this._host.requestUpdate();
  }
```

**channelTree getter modification:** After building the channel-derived tree, merge `_pendingSpaces` that don't already have channels:

```typescript
get channelTree(): ChannelTree {
    // ... existing channel-derived logic (lines 46-82) ...
    
    // Merge pending spaces that aren't already represented
    for (const ps of this._pendingSpaces) {
      if (!spaceMap.has(ps.id)) {
        roots.push({ space: ps, channels: [], unreadCount: 0, children: [] });
      }
    }
    
    return { spaces: roots, ungrouped };
}
```

**Snapshot reconciliation:** When `_applyChannels` processes a `snapshot` op, prune `_pendingSpaces` — only remove a pending space when its ID appears in the channel-derived space map (meaning a channel now exists in that space). Empty pending spaces survive snapshots until they gain a channel or are explicitly removed.

```typescript
private _applyChannels(op: DatasetOp) {
    if (op.op === 'snapshot') {
      this.channels = (op.rows ?? []).map(r => this._toChannel(r));
      // Prune pending spaces that now have channels (they're represented in channel data)
      const channelSpaceIds = new Set(this.channels.filter(ch => ch.spaceId).map(ch => ch.spaceId!));
      this._pendingSpaces = this._pendingSpaces.filter(s => !channelSpaceIds.has(s.id));
    }
    // ... rest unchanged
}
```

**Local state update methods** for the workbench to call after successful API operations:

```typescript
applyRenameSpace(spaceId: string, newName: string) {
    this.channels = this.channels.map(ch =>
      ch.spaceId === spaceId ? { ...ch, spaceName: newName } : ch
    );
    this._pendingSpaces = this._pendingSpaces.map(s =>
      s.id === spaceId ? { ...s, name: newName } : s
    );
    this._host.requestUpdate();
}

applyMoveChannel(channelId: string, spaceId: string | null, spaceName: string | null) {
    this.channels = this.channels.map(ch =>
      ch.id === channelId ? { ...ch, spaceId: spaceId ?? undefined, spaceName: spaceName ?? undefined } : ch
    );
    this._host.requestUpdate();
}

applyDeleteSpace(spaceId: string) {
    this.channels = this.channels.map(ch =>
      ch.spaceId === spaceId ? { ...ch, spaceId: undefined, spaceName: undefined, parentSpaceId: undefined } : ch
    );
    this.removePendingSpace(spaceId);
}
```

### Layer 4: ChannelNavElement — context menu and create space (blocks-ui)

#### New state

```typescript
@state() private _contextMenu: {
  x: number; y: number;
  type: 'space' | 'channel';
  target: SpaceNode | QhorusChannel;
} | null = null;

@state() private _renamingSpaceId: string | null = null;
@state() private _renameValue = '';
@state() private _showCreateSpaceDialog = false;
@state() private _showDeleteSpaceDialog = false;
@state() private _deleteSpaceTarget: SpaceNode | null = null;
@state() private _createChannelInSpaceId: string | null = null;  // tracks "Create Channel Here" target
```

#### Context menu rendering

```
Right-click space header:
┌──────────────────────┐
│ Rename               │
│ Delete               │
│ ──────────────────── │
│ Create Channel Here  │
└──────────────────────┘

Right-click channel item:
┌──────────────────────┐
│ Move to Space ▸      │
│ ┌──────────────────┐ │
│ │ Case Alpha       │ │
│ │ Case Beta        │ │
│ │ Case Gamma       │ │
│ │ ──────────────── │ │
│ │ No Space         │ │
│ └──────────────────┘ │
└──────────────────────┘
```

The context menu is a positioned `<div>` with `position: fixed` at the click coordinates. Dismiss on:
- Click anywhere outside the menu
- Escape key
- Selecting a menu item

The submenu for "Move to Space" appears on hover/focus of the "Move to Space" item. It lists all spaces from `channelTree.spaces` plus a "No Space" option for unassigning. The channel's current space is excluded from the list (can't move to where it already is).

#### Space header with inline rename and context menu

```typescript
private _renderSpaceGroup(node: SpaceNode): unknown {
    const expanded = this._expandedSpaces.has(node.space.id);
    const renaming = this._renamingSpaceId === node.space.id;
    return html`
      <div class="space-group">
        <div class="space-header"
             @click="${renaming ? nothing : () => this._toggleSpace(node.space.id)}"
             @contextmenu="${(e: MouseEvent) => this._showContextMenu(e, 'space', node)}"
             role="button" aria-expanded="${expanded}">
          <span class="space-disclosure">${expanded ? '▾' : '▸'}</span>
          ${renaming ? html`
            <input class="space-rename-input" type="text"
              .value="${this._renameValue}"
              @input="${(e: InputEvent) => { this._renameValue = (e.target as HTMLInputElement).value; }}"
              @keydown="${this._handleRenameKeyDown}"
              @blur="${this._commitRename}"
              autofocus>
          ` : html`
            <span class="space-name">${node.space.name}</span>
          `}
          ${!renaming && node.unreadCount ? html`<pages-badge variant="neutral" size="sm" label="${node.unreadCount}"></pages-badge>` : nothing}
        </div>
        ${expanded ? html`
          <ul class="space-channels">
            ${node.channels.map(ch => this._renderChannelItem(ch))}
          </ul>
          ${node.children.map(child => this._renderSpaceGroup(child))}
        ` : nothing}
      </div>
    `;
}
```

#### Channel item with context menu

Add `@contextmenu` handler to existing channel items:

```typescript
<li class="channel-item ..."
    @click="${() => this.handleChannelClick(channel.id)}"
    @contextmenu="${(e: MouseEvent) => this._showContextMenu(e, 'channel', channel)}">
```

#### "+ Create Space" button

Added to `_renderTree()` before the filter dropdown, on the same row:

```typescript
private _renderTree(): unknown {
    const tree = this.channelTree!;
    this._ensureExpanded(tree);
    return html`
      <div class="space-filter-row">
        <select class="space-filter" ...>...</select>
        ${this.showCreate ? html`
          <button class="create-space-btn" @click="${this._handleCreateSpace}"
            title="Create Space" aria-label="Create Space">+</button>
        ` : nothing}
      </div>
      <!-- rest of tree rendering -->
    `;
}
```

#### Key event handlers

```typescript
private _showContextMenu(e: MouseEvent, type: 'space' | 'channel', target: SpaceNode | QhorusChannel) {
    e.preventDefault();
    e.stopPropagation();
    this._contextMenu = { x: e.clientX, y: e.clientY, type, target };
    document.addEventListener('click', this._dismissContextMenu, { once: true });
}

private _dismissContextMenu = () => { this._contextMenu = null; };

private _startRename(spaceId: string, currentName: string) {
    this._contextMenu = null;
    this._renamingSpaceId = spaceId;
    this._renameValue = currentName;
}

private _commitRename() {
    if (this._renamingSpaceId && this._renameValue.trim()) {
        emitPagesEvent(this, ChannelEventTopics.RENAME_SPACE, {
            spaceId: this._renamingSpaceId,
            newName: this._renameValue.trim(),
        });
    }
    this._renamingSpaceId = null;
}

private _handleRenameKeyDown(e: KeyboardEvent) {
    if (e.key === 'Enter') { e.preventDefault(); this._commitRename(); }
    if (e.key === 'Escape') { e.preventDefault(); this._renamingSpaceId = null; }
}

private _handleCreateSpace() { this._showCreateSpaceDialog = true; }

private _onCreateSpaceConfirm(e: CustomEvent<{ reason?: string }>) {
    const name = e.detail?.reason?.trim();
    if (name) emitPagesEvent(this, ChannelEventTopics.CREATE_SPACE, { name });
    this._showCreateSpaceDialog = false;
}

private _handleCreateChannelInSpace(spaceId: string) {
    this._contextMenu = null;
    this._createChannelInSpaceId = spaceId;
    this._showCreateDialog = true;  // reuses existing create-channel dialog
}

// Modified from existing _onCreateConfirm to include spaceId when "Create Channel Here" was used
private _onCreateConfirm(e: CustomEvent<{ reason?: string }>) {
    const name = e.detail?.reason?.trim();
    if (name) {
        const payload: CreateChannelPayload = { name };
        if (this._createChannelInSpaceId) payload.spaceId = this._createChannelInSpaceId;
        emitPagesEvent(this, ChannelEventTopics.CREATE_CHANNEL, payload);
    }
    this._showCreateDialog = false;
    this._createChannelInSpaceId = null;
}

private _handleDeleteSpace(node: SpaceNode) {
    this._contextMenu = null;
    this._deleteSpaceTarget = node;
    this._showDeleteSpaceDialog = true;
}

private _onDeleteSpaceConfirm() {
    if (this._deleteSpaceTarget) {
        emitPagesEvent(this, ChannelEventTopics.DELETE_SPACE, {
            spaceId: this._deleteSpaceTarget.space.id,
        });
    }
    this._deleteSpaceTarget = null;
    this._showDeleteSpaceDialog = false;
}
```

#### Delete space confirmation dialog

Added to `_renderTree()` alongside existing dialogs:

```typescript
<pages-confirm-dialog
    .open=${this._showDeleteSpaceDialog}
    heading="Delete Space"
    message=${this._deleteSpaceTarget
        ? `Delete space "${this._deleteSpaceTarget.space.name}"? Its ${this._deleteSpaceTarget.channels.length} channel(s) will move to the top level.`
        : ''}
    confirmLabel="Delete"
    confirmVariant="danger"
    @confirm=${this._onDeleteSpaceConfirm}
    @cancel=${() => { this._deleteSpaceTarget = null; this._showDeleteSpaceDialog = false; }}>
</pages-confirm-dialog>
```

#### Context menu rendering

```typescript
private _renderContextMenu(): unknown {
    if (!this._contextMenu) return nothing;
    const { x, y, type, target } = this._contextMenu;
    // Viewport clamping: measure after first render and adjust if menu exceeds viewport bounds
    const clampedX = Math.min(x, window.innerWidth - 200);
    const clampedY = Math.min(y, window.innerHeight - 200);
    
    if (type === 'space') {
        const node = target as SpaceNode;
        return html`
          <div class="context-menu" style="left:${clampedX}px;top:${clampedY}px"
               @click="${(e: Event) => e.stopPropagation()}">
            <div class="context-menu-item" @click="${() => this._startRename(node.space.id, node.space.name)}">Rename</div>
            <div class="context-menu-item" @click="${() => this._handleDeleteSpace(node)}">Delete</div>
            <div class="context-menu-separator"></div>
            <div class="context-menu-item" @click="${() => this._handleCreateChannelInSpace(node.space.id)}">Create Channel Here</div>
          </div>
        `;
    }
    
    if (type === 'channel') {
        const channel = target as QhorusChannel;
        const spaces = this.channelTree?.spaces ?? [];
        return html`
          <div class="context-menu" style="left:${clampedX}px;top:${clampedY}px"
               @click="${(e: Event) => e.stopPropagation()}">
            <div class="context-menu-item submenu-trigger">
              Move to Space ▸
              <div class="context-menu submenu">
                ${spaces
                    .filter(s => s.space.id !== channel.spaceId)
                    .map(s => html`
                      <div class="context-menu-item"
                           @click="${() => { this._contextMenu = null; this._emitMoveChannel(channel.id, s.space.id); }}">
                        ${s.space.name}
                      </div>
                    `)}
                ${channel.spaceId ? html`
                  <div class="context-menu-separator"></div>
                  <div class="context-menu-item"
                       @click="${() => { this._contextMenu = null; this._emitMoveChannel(channel.id, null); }}">
                    No Space
                  </div>
                ` : nothing}
              </div>
            </div>
          </div>
        `;
    }
    return nothing;
}

private _emitMoveChannel(channelId: string, spaceId: string | null) {
    emitPagesEvent(this, ChannelEventTopics.MOVE_CHANNEL_TO_SPACE, { channelId, spaceId });
}
```

The submenu appears on hover via CSS (`.submenu-trigger:hover .submenu { display: block; }`). The submenu lists all spaces except the channel's current space. "No Space" appears only if the channel is currently in a space.

**Keyboard accessibility:** Add Shift+F10 and the Menu key as context menu triggers in `_handleTreeKeyDown`, using the focused item's position for menu coordinates.

#### Dialogs (added to `_renderTree()`)

### Layer 5: Workbench event wiring (chat-app)

Extend `_onChatEvent` in `qhorus-workbench.ts` to handle space events and wire existing channel CRUD:

```typescript
private _onChatEvent = (e: CustomEvent) => {
    const { topic, payload } = e.detail;
    
    // Message posting
    if (topic === ChannelEventTopics.SEND_MESSAGE) {
        this._sendMessage(payload as SendMessagePayload);
    }
    // Space CRUD — handled directly in workbench
    else if (topic === ChannelEventTopics.CREATE_SPACE) {
        this._createSpace(payload as CreateSpacePayload);
    }
    else if (topic === ChannelEventTopics.RENAME_SPACE) {
        this._renameSpace(payload as RenameSpacePayload);
    }
    else if (topic === ChannelEventTopics.DELETE_SPACE) {
        this._deleteSpace(payload as DeleteSpacePayload);
    }
    else if (topic === ChannelEventTopics.MOVE_CHANNEL_TO_SPACE) {
        this._moveChannelToSpace(payload as MoveChannelToSpacePayload);
    }
    // Channel CRUD — wire to REST
    else if (topic === ChannelEventTopics.CREATE_CHANNEL) {
        this._createChannel(payload as CreateChannelPayload);
    }
    else if (topic === ChannelEventTopics.DELETE_CHANNEL) {
        this._deleteChannel(payload as DeleteChannelPayload);
    }
    // Existing controller delegation
    else {
        this._channels.handleEvent(topic, payload);
        this._messaging.handleEvent(topic, payload);
        this._reactions.handleEvent(topic, payload);
        this._commitments.handleEvent(topic, payload);
    }
    
    // App-specific handlers (artifact panel, drawer close, markRead)
    // ... unchanged ...
};
```

#### Error feedback

CRUD failures must be visible to the user. Add a `_showError(message: string)` method to the workbench that displays a brief error message. The simplest mechanism: a timed `@state() private _errorMessage = ''` that renders as a banner at the top of the main panel and auto-dismisses after 5 seconds. If `pages-ui-components` provides a toast primitive, use that instead.

#### Space CRUD methods

```typescript
private async _createSpace(payload: CreateSpacePayload) {
    try {
        const res = await authenticatedFetch('/api/spaces', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: payload.name }),
        });
        if (!res.ok) throw new Error((await res.json()).error ?? 'Failed to create space');
        const space = await res.json();
        this._channels.addPendingSpace({ id: space.id, name: space.name });
    } catch (e) { this._showError(`Failed to create space: ${(e as Error).message}`); }
}

private async _renameSpace(payload: RenameSpacePayload) {
    try {
        const res = await authenticatedFetch(`/api/spaces/${payload.spaceId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: payload.newName }),
        });
        if (!res.ok) throw new Error((await res.json()).error ?? 'Failed to rename space');
        this._channels.applyRenameSpace(payload.spaceId, payload.newName);
    } catch (e) { this._showError(`Failed to rename space: ${(e as Error).message}`); }
}

private async _deleteSpace(payload: DeleteSpacePayload) {
    try {
        // Single transactional endpoint: moves channels to root and deletes the space atomically
        const res = await authenticatedFetch(`/api/spaces/${payload.spaceId}?reassign=true`, {
            method: 'DELETE',
        });
        if (!res.ok) throw new Error((await res.json()).error ?? 'Failed to delete space');
        this._channels.applyDeleteSpace(payload.spaceId);
    } catch (e) { this._showError(`Failed to delete space: ${(e as Error).message}`); }
}

private async _moveChannelToSpace(payload: MoveChannelToSpacePayload) {
    try {
        const res = await authenticatedFetch(`/api/channels/${payload.channelId}/space`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ spaceId: payload.spaceId }),
        });
        if (!res.ok) throw new Error((await res.json()).error ?? 'Failed to move channel');
        const spaceName = payload.spaceId
            ? this._channels.channelTree.spaces.find(s => s.space.id === payload.spaceId)?.space.name ?? null
            : null;
        this._channels.applyMoveChannel(payload.channelId, payload.spaceId, spaceName);
    } catch (e) { this._showError(`Failed to move channel: ${(e as Error).message}`); }
}
```

#### Channel CRUD methods (currently unhandled — wire them)

```typescript
private async _createChannel(payload: CreateChannelPayload) {
    try {
        const body: Record<string, unknown> = { name: payload.name };
        if (payload.description) body.description = payload.description;
        if (payload.spaceId) body.spaceId = payload.spaceId;
        if (payload.semantic) body.semantic = payload.semantic;
        const res = await authenticatedFetch('/api/channels', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (!res.ok) throw new Error(await res.text());
    } catch (e) { console.error('Failed to create channel:', e); }
}

private async _deleteChannel(payload: DeleteChannelPayload) {
    try {
        const res = await authenticatedFetch(`/api/channels/${payload.channelId}`, { method: 'DELETE' });
        if (!res.ok) throw new Error(await res.text());
    } catch (e) { console.error('Failed to delete channel:', e); }
}
```

### Layer 6: CSS additions (blocks-ui)

Context menu and inline rename styles added to `ChannelNavElement`:

```css
.context-menu {
    position: fixed;
    background: var(--pages-neutral-1, #fff);
    border: 1px solid var(--pages-neutral-5, #d4d4d4);
    border-radius: var(--pages-radius-1, 4px);
    box-shadow: var(--pages-shadow-3, 0 4px 12px rgba(0,0,0,0.1));
    z-index: 100;
    min-width: 180px;
    padding: var(--pages-space-1, 4px);
}
.context-menu-item {
    padding: var(--pages-space-2, 8px) var(--pages-space-3, 12px);
    cursor: pointer;
    border-radius: var(--pages-radius-1, 4px);
    font-size: 13px;
    display: flex;
    align-items: center;
    justify-content: space-between;
}
.context-menu-item:hover { background: var(--pages-neutral-3, #f5f5f5); }
.context-menu-separator {
    height: 1px;
    background: var(--pages-neutral-4, #e5e5e5);
    margin: var(--pages-space-1, 4px) 0;
}
.submenu {
    position: absolute;
    left: 100%;
    top: 0;
}
.space-rename-input {
    flex: 1;
    font-weight: 600;
    font-size: 14px;
    border: 1px solid var(--pages-accent-7, #818cf8);
    border-radius: var(--pages-radius-1, 4px);
    padding: 2px 4px;
    background: var(--pages-neutral-1, #fff);
    outline: none;
}
.space-filter-row {
    display: flex;
    gap: var(--pages-space-1, 4px);
    align-items: center;
    margin-bottom: var(--pages-space-2, 8px);
}
.space-filter-row .space-filter { flex: 1; }
.create-space-btn {
    width: 28px; height: 28px;
    border: 1px solid var(--pages-neutral-5, #d4d4d4);
    border-radius: var(--pages-radius-1, 4px);
    background: var(--pages-neutral-1, #fff);
    cursor: pointer;
    font-size: 16px;
    color: var(--pages-neutral-9, #999);
    display: flex;
    align-items: center;
    justify-content: center;
}
.create-space-btn:hover {
    background: var(--pages-neutral-3, #f5f5f5);
    color: var(--pages-neutral-12, #1a1a1a);
}
```

## Known Limitations

**Multi-client sync for space rename:** `SpaceService.rename()` updates only the Space record, not channel records. Since push snapshots denormalize `spaceName` at query time from the Space table, other connected clients won't see the renamed space until their next full snapshot cycle. This is acceptable for demo scale. The full fix (space lifecycle events in the push system) is tracked as part of #35.

**Permissions:** Space CRUD operations have no access control — any authenticated user can create, rename, delete spaces and move channels. This is acceptable for pre-release demo. Permissions are a separate concern.

## Cross-Repo Impact

| Repo | Changes | Reason |
|------|---------|--------|
| **qhorus** | `ChannelResource` — add `PUT /{id}/space` endpoint, inject `SpaceService`. `SpaceService` — add `deleteWithChannelReassignment()` transactional method. `SpaceResource` — extend `DELETE /{id}` with `?reassign=true` query param. | Expose `moveChannelToSpace` via REST; atomic delete-with-reassignment |
| **blocks-ui** | `events.ts` — new space event topics and payloads. `channel-state-controller.ts` — pendingSpaces overlay and local state update methods. `channel-nav.ts` — context menu, inline rename, create space button, new CSS | Component extensions for space CRUD UI |
| **chat-app** | `qhorus-workbench.ts` — event handlers for space CRUD + wire existing channel CRUD + error feedback | App-level REST wiring and error display |

## Testing Strategy

### Backend (Java — JUnit)
- `PUT /api/channels/{id}/space` with valid spaceId → 200, channel.spaceId updated
- `PUT /api/channels/{id}/space` with null spaceId → 200, channel.spaceId cleared
- `PUT /api/channels/{id}/space` with non-existent spaceId → 400
- `PUT /api/channels/{id}/space` with cross-tenancy spaceId → 400
- `DELETE /api/spaces/{id}?reassign=true` with channels → 204, channels moved to root, space deleted
- `DELETE /api/spaces/{id}?reassign=true` with child spaces → 400 (child spaces block deletion)
- `DELETE /api/spaces/{id}?reassign=false` with channels → 409 (existing behavior preserved)
- `deleteWithChannelReassignment()` is transactional — partial failure rolls back all channel moves

### Frontend (TypeScript — vitest)

**channel-nav.test.ts:**
- Context menu appears on right-click of space header with correct items (Rename, Delete, Create Channel Here)
- Context menu appears on right-click of channel item with correct items (Move to Space with submenu)
- Context menu dismissed on outside click and Escape
- "Move to Space" submenu lists all spaces except the channel's current space, plus "No Space"
- Inline rename: Rename menu item replaces space name with input; Enter commits and emits RENAME_SPACE; Escape cancels
- Space header click-to-expand is suppressed during rename
- "+ Create Space" button emits CREATE_SPACE after dialog confirm
- Delete space shows confirmation dialog with channel count; confirm emits DELETE_SPACE
- "Create Channel Here" emits CREATE_CHANNEL with spaceId set

**channel-state-controller.test.ts:**
- `addPendingSpace()` makes empty space appear in `channelTree.spaces`
- Pending space removed when snapshot arrives
- `applyRenameSpace()` updates spaceName on all matching channels
- `applyMoveChannel()` updates channel's spaceId/spaceName
- `applyDeleteSpace()` moves channels to ungrouped and removes pending space
- Snapshot reconciliation: pending spaces with channels in snapshot are pruned; empty pending spaces survive

**qhorus-workbench.test.ts:**
- CREATE_SPACE event triggers POST /api/spaces, then addPendingSpace
- RENAME_SPACE event triggers PUT /api/spaces/{id}, then applyRenameSpace
- DELETE_SPACE event triggers DELETE /api/spaces/{id}?reassign=true, then applyDeleteSpace
- MOVE_CHANNEL_TO_SPACE event triggers PUT /api/channels/{id}/space, then applyMoveChannel
- Failed operations show error message to user (not just console.error)
- CREATE_CHANNEL event triggers POST /api/channels with spaceId when present
- DELETE_CHANNEL event triggers DELETE /api/channels/{id}

## References

- `SpaceResource.java` (qhorus runtime:27) — complete CRUD REST API, auto-mounts in chat-app
- `SpaceService.java` (qhorus runtime:20) — create, rename, moveSpace, moveChannelToSpace, delete with guards
- `SpaceStore.java` (qhorus api:10) — SPI with put, find, findByName, listByParent, listRoots, hasChildren, delete, findByIds
- `ChannelResource.java` (qhorus runtime:52) — existing REST resource, target for new space assignment endpoint
- `channel-nav.ts` (blocks-ui) — current tree rendering with collapse/expand, filter, keyboard nav
- `channel-state-controller.ts` (blocks-ui) — channelTree getter, optimistic unread tracking, push handlers
- `events.ts` (blocks-ui) — event topics and payloads, CreateChannelPayload already has spaceId
- `qhorus-workbench.ts` (chat-app) — event handler, push connection, authenticatedFetch
- [GE-20260816-2058bc] — qhorus Space model is complete but undocumented
- [GE-20260801-75857d] — ChannelResource auto-mounts REST endpoints in consuming Quarkus apps
- Issue #7 spec — `specs/issue-7-space-channel-hierarchy/2026-08-20-space-channel-hierarchy-design.md`
- Issue #7 decisions — `specs/issue-7-space-channel-hierarchy/decisions.md` (D3: spaces are display grouping only — now being extended)
