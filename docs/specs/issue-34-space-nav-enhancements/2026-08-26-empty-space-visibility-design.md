# Empty Space Visibility Rules — Design Spec

**Issue:** casehubio/chat-app#35
**Parent:** casehubio/chat-app#7 (space-based channel hierarchy)
**Dependencies:** #34 (OPEN — space CRUD operations, pendingSpaces overlay)
**Date:** 2026-08-26

## Summary

Make spaces first-class data on the frontend by adding a `spaces` push dataset in qhorus. All server-side spaces are always visible in the channel nav, including empty ones. The `channelTree` getter is refactored to build from spaces-first (structure from spaces, content from channels). The `pendingSpaces` overlay simplifies to optimistic-UI only.

## Scope

**In scope:**
- New `spaces` push dataset in qhorus (snapshot + delta broadcasts)
- New `ChannelMutationEvent` sealed variants for space lifecycle (`SpaceCreated`, `SpaceRenamed`, `SpaceDeleted`)
- `SpaceService` fires mutation events on create/rename/delete
- `QhorusWebSocketBroadcaster` space broadcast methods + `onMutation` routing
- `ChannelStateController` new `_applySpaces` handler with spaces state
- `channelTree` getter refactored to spaces-first construction
- `pendingSpaces` reconciliation moved to `_applySpaces` (discard when push confirms)
- Tests at all layers

**Out of scope:**
- Configurable visibility rules (YAGNI at demo scale — D1)
- Access control for spaces (all spaces within a tenancy are public — acknowledged as security-relevant, acceptable pre-release)
- Removing `spaceName` from channel wire format (backward-compat, future cleanup)
- Space lifecycle events for multi-client rename sync beyond push snapshots

## Architecture

The change touches three repos across four modules:

```
qhorus-api          qhorus-push              blocks-ui                  chat-app
ChannelMutationEvent  QhorusDatasetBuilder     ChannelStateController    (no changes)
  SpaceCreated        buildSpaceSnapshot       _applySpaces
  SpaceRenamed        SPACE_COLUMNS            spaces: Space[]
  SpaceDeleted        ALL_TOPICS               channelTree (refactored)
SpaceService        QhorusWebSocketBroadcaster  pendingSpaces (simplified)
  fire events         broadcastSpace*
                      onMutation space cases
```

### Layer 1: qhorus-api — mutation event variants

Add sealed variants to `ChannelMutationEvent`:

```java
record SpaceCreated(UUID spaceId, String name, String tenancyId) implements ChannelMutationEvent {}
record SpaceRenamed(UUID spaceId, String newName) implements ChannelMutationEvent {}
record SpaceDeleted(UUID spaceId) implements ChannelMutationEvent {}
```

### Layer 2: qhorus-runtime — fire events from SpaceService

`SpaceService.create()`, `rename()`, and `delete()`/`deleteWithChannelReassignment()` fire the corresponding mutation events via CDI `Event<ChannelMutationEvent>`:

```java
@Inject Event<ChannelMutationEvent> mutationEvent;

public Space create(SpaceCreateRequest request) {
    // ... existing creation logic ...
    mutationEvent.fire(new ChannelMutationEvent.SpaceCreated(space.id(), space.name(), space.tenancyId()));
    return space;
}
```

### Layer 3: qhorus-push — snapshot builder + delta broadcasts

**Snapshot builder** — add `buildSpaceSnapshot` alongside existing dataset builders:

```java
static final String[] SPACE_COLUMNS = {"id", "name", "description", "parentSpaceId"};

public DatasetSnapshot buildSpaceSnapshot(UUID tenancyId) {
    // SpaceStore has listRoots + listByParent — collect all spaces recursively.
    // If this is too slow at scale, add a listAll(tenancyId) to the SPI.
    List<Space> allSpaces = collectAllSpaces(tenancyId);
    List<Object[]> rows = new ArrayList<>();
    for (Space s : allSpaces) {
        rows.add(new Object[]{
            s.id().toString(),
            s.name(),
            s.description() != null ? s.description() : "",
            s.parentSpaceId() != null ? s.parentSpaceId().toString() : ""
        });
    }
    return new DatasetSnapshot("spaces", SPACE_COLUMNS, rows);
}
```

Register `"spaces"` in `ALL_TOPICS` so the push subscription includes it. Add a case to the snapshot builder's dataset routing switch that delegates to `buildSpaceSnapshot` when the topic is `"spaces"`.

**Delta broadcasts** — add to `QhorusWebSocketBroadcaster`:

```java
void broadcastSpaceAppend(UUID tenancyId, UUID spaceId, String name, String description, UUID parentSpaceId) {
    broadcast(tenancyId, DatasetOp.append("spaces",
        new Object[]{spaceId.toString(), name, description != null ? description : "",
                      parentSpaceId != null ? parentSpaceId.toString() : ""}));
}

void broadcastSpaceReplace(UUID tenancyId, UUID spaceId, String name, String description, UUID parentSpaceId) {
    broadcast(tenancyId, DatasetOp.replace("spaces", spaceId.toString(),
        new Object[]{spaceId.toString(), name, description != null ? description : "",
                      parentSpaceId != null ? parentSpaceId.toString() : ""}));
}

void broadcastSpaceRemove(UUID tenancyId, UUID spaceId) {
    broadcast(tenancyId, DatasetOp.remove("spaces", spaceId.toString()));
}
```

**onMutation routing** — add cases to the `@Observes ChannelMutationEvent` handler:

```java
case SpaceCreated e -> {
    Space s = spaceStore.find(e.spaceId()).orElseThrow();
    broadcastSpaceAppend(tenancyId, s.id(), s.name(), s.description(), s.parentSpaceId());
}
case SpaceRenamed e -> {
    Space s = spaceStore.find(e.spaceId()).orElseThrow();
    broadcastSpaceReplace(tenancyId, s.id(), s.name(), s.description(), s.parentSpaceId());
}
case SpaceDeleted e -> broadcastSpaceRemove(tenancyId, e.spaceId());
```

### Layer 4: blocks-ui — controller and channelTree refactoring

**New spaces state and handler** in `ChannelStateController`:

```typescript
spaces: Space[] = [];

constructor(host: ReactiveControllerHost, push: PushController) {
    // ... existing registrations ...
    push.registerDatasetHandler('spaces', (op) => { this._applySpaces(op); this._host.requestUpdate(); });
}

private _applySpaces(op: DatasetOp) {
    if (op.op === 'snapshot') {
      this.spaces = (op.rows ?? []).map(r => this._toSpace(r));
      this._pendingSpaces = this._pendingSpaces.filter(ps => !this.spaces.some(s => s.id === ps.id));
    } else if (op.op === 'append' && op.rows) {
      const newSpaces = op.rows.map(r => this._toSpace(r));
      this.spaces = [...this.spaces, ...newSpaces];
      this._pendingSpaces = this._pendingSpaces.filter(ps => !newSpaces.some(s => s.id === ps.id));
    } else if (op.op === 'replace' && op.row && op.key) {
      this.spaces = this.spaces.map(s => s.id === op.key ? this._toSpace(op.row!) : s);
    } else if (op.op === 'remove' && op.key) {
      this.spaces = this.spaces.filter(s => s.id !== op.key);
    }
}

private _toSpace(row: unknown[]): Space {
    const space: Space = { id: row[0] as string, name: row[1] as string };
    const desc = row[2] as string;
    const parentId = row[3] as string;
    if (desc) (space as { description: string }).description = desc;
    if (parentId) (space as { parentSpaceId: string }).parentSpaceId = parentId;
    return space;
}
```

**Refactored channelTree getter** — spaces-first construction:

```typescript
get channelTree(): ChannelTree {
    // Build space nodes from spaces dataset (authoritative source)
    const spaceMap = new Map<string, { space: Space; channels: QhorusChannel[]; children: SpaceNode[] }>();
    for (const s of this.spaces) {
      spaceMap.set(s.id, { space: s, channels: [], children: [] });
    }
    // Merge pending spaces (optimistic-UI)
    for (const ps of this._pendingSpaces) {
      if (!spaceMap.has(ps.id)) {
        spaceMap.set(ps.id, { space: ps, channels: [], children: [] });
      }
    }

    // Assign channels to spaces
    const ungrouped: QhorusChannel[] = [];
    for (const ch of this.channels) {
      if (ch.spaceId) {
        const node = spaceMap.get(ch.spaceId);
        if (node) {
          node.channels.push(ch);
        } else {
          // Channel references unknown space — treat as ungrouped
          ungrouped.push(ch);
        }
      } else {
        ungrouped.push(ch);
      }
    }

    // Build hierarchy from parent-child relationships
    const roots: SpaceNode[] = [];
    for (const node of spaceMap.values()) {
      const parentId = node.space.parentSpaceId;
      const channelUnread = node.channels.reduce((sum, ch) => sum + (ch.unreadCount ?? 0), 0);
      if (parentId) {
        const parent = spaceMap.get(parentId);
        if (parent) {
          parent.children.push({ ...node, unreadCount: channelUnread });
          continue;
        }
      }
      roots.push({ ...node, unreadCount: channelUnread });
    }
    // Roll up child unread counts
    for (const root of roots) {
      const childrenUnread = root.children.reduce((sum, child) => sum + child.unreadCount, 0);
      (root as { unreadCount: number }).unreadCount += childrenUnread;
    }

    return { spaces: roots, ungrouped };
}
```

**pendingSpaces reconciliation** moves from `_applyChannels` to `_applySpaces`. The `_applyChannels` snapshot handler no longer prunes pending spaces — that responsibility belongs to the spaces handler. Remove lines 173-174 from `_applyChannels`.

**applyRenameSpace** update — when the workbench calls `applyRenameSpace` for optimistic UI, update both `this.spaces` and `this._pendingSpaces`:

```typescript
applyRenameSpace(spaceId: string, newName: string) {
    this.spaces = this.spaces.map(s =>
      s.id === spaceId ? { ...s, name: newName } : s
    );
    this._pendingSpaces = this._pendingSpaces.map(s =>
      s.id === spaceId ? { ...s, name: newName } : s
    );
    this._host.requestUpdate();
}
```

The existing `applyRenameSpace` also updated channel `spaceName` — this is no longer needed since the getter uses space names from the spaces dataset. But keep it for backward safety until `spaceName` is removed from channels.

**applyDeleteSpace** update — also remove from `this.spaces`:

```typescript
applyDeleteSpace(spaceId: string) {
    this.spaces = this.spaces.filter(s => s.id !== spaceId);
    this.channels = this.channels.map(ch => {
      if (ch.spaceId !== spaceId) return ch;
      const { spaceId: _, spaceName: _s, parentSpaceId: _p, ...rest } = ch;
      return rest as typeof ch;
    });
    this.removePendingSpace(spaceId);
}
```

### Layer 5: chat-app — no changes

The workbench already handles space CRUD events (#34). The `channelTree` interface (`ChannelTree`, `SpaceNode`) is unchanged. No workbench modifications needed.

## Known Limitations

**Temporal sync window:** The spaces and channels push datasets arrive independently. During the brief window where channels reference a `spaceId` not yet in the spaces dataset, those channels appear ungrouped. This self-corrects on the next push cycle (~seconds). Acceptable at demo scale.

**Redundant spaceName on channels:** Channel rows continue carrying `spaceName` for backward compatibility. The channelTree getter ignores it (uses spaces dataset for names). A future cleanup can remove it from the wire format.

**No access control:** All spaces within a tenancy are visible to all users. This is a security-relevant choice, acceptable pre-release. Future ACL would filter at `buildSpaceSnapshot` level.

## Cross-Repo Impact

| Repo | Module | Changes | Lines |
|------|--------|---------|-------|
| **qhorus** | qhorus-api | New `ChannelMutationEvent` sealed variants: `SpaceCreated`, `SpaceRenamed`, `SpaceDeleted` | ~15 |
| **qhorus** | qhorus-runtime | `SpaceService` fires mutation events on create/rename/delete | ~9 |
| **qhorus** | qhorus-push | `SPACE_COLUMNS`, `buildSpaceSnapshot`, `ALL_TOPICS`, `broadcastSpace*` methods, `onMutation` cases | ~41 |
| **blocks-ui** | channel-activity | `_applySpaces` handler, `spaces` state, `_toSpace`, refactored `channelTree`, updated `applyRenameSpace`/`applyDeleteSpace`, `_applyChannels` cleanup | ~45 |
| **chat-app** | — | No changes | 0 |

Total: ~110 lines across 4 modules in 2 repos.

## Testing Strategy

### Backend (Java — JUnit)

- `SpaceService.create()` fires `SpaceCreated` event
- `SpaceService.rename()` fires `SpaceRenamed` event
- `SpaceService.delete()` fires `SpaceDeleted` event
- `SpaceService.deleteWithChannelReassignment()` fires `SpaceDeleted` event
- `buildSpaceSnapshot` returns all spaces in tenancy with correct columns
- `broadcastSpaceAppend/Replace/Remove` emit correct `DatasetOp`
- `onMutation` routes `SpaceCreated`/`SpaceRenamed`/`SpaceDeleted` to correct broadcast methods

### Frontend (TypeScript — vitest)

**channel-state-controller.test.ts:**
- `_applySpaces` snapshot populates `this.spaces`
- `_applySpaces` append adds new spaces
- `_applySpaces` replace updates existing space
- `_applySpaces` remove deletes space
- `_applySpaces` snapshot prunes matching pendingSpaces
- `_applySpaces` append prunes matching pendingSpaces
- `channelTree` builds from spaces dataset (empty spaces appear as SpaceNode with no channels)
- `channelTree` assigns channels to spaces by spaceId
- `channelTree` treats channels with unknown spaceId as ungrouped
- `channelTree` merges pendingSpaces that aren't in spaces dataset
- `channelTree` builds parent-child hierarchy from parentSpaceId
- `applyRenameSpace` updates `this.spaces` (not just channels)
- `applyDeleteSpace` removes from `this.spaces`

## References

- `ChannelStateController` (channel-state-controller.ts) — current channelTree getter, pendingSpaces overlay
- `SpaceStore` SPI (qhorus-api) — listRoots, findByIds
- `SpaceService` (qhorus-runtime) — create, rename, delete, deleteWithChannelReassignment
- `QhorusDatasetBuilder` (qhorus-push) — existing dataset builder pattern
- `QhorusWebSocketBroadcaster` (qhorus-push) — existing delta broadcast pattern, ChannelMutationEvent routing
- [GE-20260816-2058bc] — qhorus Space model is complete but undocumented
- Issue #34 spec — decisions D0 (pendingSpaces overlay), D4 (event wiring)
- Issue #35 — original requirement (revised: always-visible replaces configurable)
- Decision review — R1-02 (scope), R1-03 (temporal sync), R1-05 (dual source), R1-09 (augmented channel alternative)
