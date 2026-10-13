# Decisions — issue-35-empty-space-visibility

## D0: Data architecture — spaces as first-class push dataset

**Choice:** Add a `spaces` push dataset in qhorus alongside `channels`, `messages`, `topics`, etc. The frontend receives space data independently of channel data. The `channelTree` getter builds from spaces-first, then assigns channels to them.
**Alternatives:**
- REST supplementary call — frontend fetches `GET /api/spaces` on load and periodically. Creates two data sources with no synchronization guarantee, rename/delete races, and reimplements what the push system already solves.
- Enhanced pendingSpaces overlay — seed from REST on startup, reconcile with snapshots. Same two-data-source problems as REST. The `pendingSpaces` name becomes misleading — it's "all spaces from REST," not "pending."
**Rationale:** Spaces are first-class server entities but second-class on the frontend. The push system already handles every other domain entity (channels, messages, topics, members, reactions, commitments). Spaces are the missing dataset. Adding them follows the established pattern, gives multi-client consistency for free, and eliminates the reconciliation complexity of a supplementary data source.
**Trade-offs:** Cross-repo change across 4 modules in 2 repos (~110 lines total): qhorus-api (new `ChannelMutationEvent` sealed variants: `SpaceCreated`, `SpaceUpdated`, `SpaceDeleted`), qhorus-runtime (fire events from `SpaceService`), qhorus-push (snapshot builder + `broadcastSpace*` delta methods + `onMutation` cases), blocks-ui (new `_applySpaces` handler, spaces state, updated getter). Follows established patterns — every other domain entity has this same infrastructure.
**Sources:** `ChannelStateController.channelTree` getter (channel-state-controller.ts:85-129), `SpaceStore` SPI (qhorus-api), `QhorusDatasetBuilder` pattern, `QhorusWebSocketBroadcaster` delta methods, #34 spec D0 (deferred to #35)
**Exploration:** quick (first-principles analysis — clear architectural direction)
**Status:** revised (R1-02 scope correction, R1-07 multi-client gain acknowledged)

## D1: Visibility rules — always show all spaces

**Choice:** All server-side spaces are always visible in the channel nav. No configurability, no filtering, no admin-vs-user distinction.
**Alternatives:**
- Configurable visibility (show/hide empty spaces per user role) — adds a configuration surface, visibility predicate, and role-awareness for a problem that doesn't exist at demo scale (3-10 spaces)
- Show recently-emptied spaces temporarily (time-based fade) — adds timer-based state management for marginal UX benefit
**Rationale:** At demo scale, hiding empty spaces has no use case. The space filter dropdown already handles visual clutter. "Configurable visibility" was written in the issue before #34 landed — before the actual scope was understood. If a production deployment needs per-role visibility, that's a permissions concern in a different architectural layer, not a display filter.
**Trade-offs:** No hide mechanism if space count grows large. The existing space filter dropdown mitigates this. A future visibility predicate in channelTree would be ~5 lines if ever needed.
**Depends on:** D0 (spaces must be available as data to decide visibility)
**Sources:** Issue #35 description, #34 spec D0 ("Issue #35 addresses the full empty-space-visibility story")
**Exploration:** quick (first-principles analysis — YAGNI)
**Status:** captured

## D2: channelTree construction — spaces-first

**Choice:** Refactor the `channelTree` getter to build from the spaces array (push dataset) first, then assign channels to their spaces. Spaces exist independently of channels — the tree structure comes from space data, channel assignment fills it in.
**Alternatives:**
- Merge approach — keep current channel-derived construction, merge in spaces from push dataset that aren't already represented. Smaller diff but preserves the "spaces as derived" mental model that D0 is fixing.
**Rationale:** The whole point of D0 is to make spaces first-class. Building the tree from spaces-first is the natural consequence. The getter becomes a simple join: spaces provide structure, channels provide content. The code matches the domain model.
**Trade-offs:** Larger diff in the channelTree getter than the merge approach. Worth it for correctness — the merge approach would leave the architectural confusion in place.
**Depends on:** D0 (spaces push dataset provides the data)
**Sources:** `ChannelStateController.channelTree` getter (channel-state-controller.ts:85-129)
**Exploration:** quick
**Status:** captured

## D3: pendingSpaces — optimistic-UI only

**Choice:** Keep the `pendingSpaces` overlay strictly as an optimistic-UI mechanism. On space creation, add to pendingSpaces for instant feedback. Discard when the next push snapshot arrives with the real space data. No REST seeding, no lifecycle management.
**Alternatives:**
- Remove entirely — user waits for push snapshot (~1-2s) before seeing newly created space. Simpler code but worse UX.
- Keep with REST seeding — seed from GET /api/spaces on startup. Overcomplicates what D0 already solves via push.
**Rationale:** Instant feedback on create is essential UX. The push snapshot corrects within one cycle. This is the same pattern used for optimistic updates elsewhere (messages, reactions). No change to the existing addPendingSpace/removePendingSpace API — just simplified usage.
**Trade-offs:** Brief window where pending space and push space might both appear. The spaces-first channelTree getter deduplicates: pending spaces whose ID appears in the spaces dataset are excluded.
**Depends on:** D0 (push dataset replaces REST as the authoritative source), D2 (spaces-first construction defines how pending spaces merge)
**Sources:** `ChannelStateController._pendingSpaces` (channel-state-controller.ts:36), `addPendingSpace()` (line 46)
**Exploration:** quick
**Status:** revised (R1-06 dedup description corrected for new architecture)

## D4: Source of truth — spaces dataset authoritative, channel spaceName backward-compat

**Choice:** The spaces push dataset is the single authoritative source for space metadata (name, parentSpaceId). Channel rows continue carrying `spaceId` for assignment and `spaceName` for backward compatibility, but the `channelTree` getter uses space names exclusively from the spaces dataset. If channels reference a `spaceId` not yet in the spaces dataset, those channels are treated as ungrouped until the spaces snapshot arrives.
**Alternatives:**
- Remove spaceName from channel rows — breaking wire format change, all consumers must update simultaneously. Clean but high coordination cost for no functional gain at this stage.
- Augmented channel snapshot with space-only rows — include empty spaces as sentinel rows in the channel dataset. Preserves atomic consistency but conflates two entity types, violating the architectural fix D0 is making.
**Rationale:** The temporal sync issue between independent datasets is real (R1-03) but bounded — both datasets arrive in the same push cycle. The ungrouped-fallback handles the brief window gracefully. Keeping `spaceName` on channels avoids a breaking protocol change while D4 makes clear which source is authoritative. The augmented-channel alternative (R1-09) was considered and rejected: it preserves the "spaces as derived from channels" model that D0 explicitly fixes.
**Trade-offs:** Channel rows carry redundant `spaceName` until a future cleanup. The getter ignores it, so no divergence risk in practice.
**Depends on:** D0 (spaces dataset exists), D2 (spaces-first getter uses spaces dataset for names)
**Sources:** `_toChannel` (channel-state-controller.ts:182-195), `QhorusDatasetBuilder.CHANNEL_COLUMNS`, decision review R1-03, R1-05, R1-09
**Exploration:** quick (surfaced by decision review)
**Status:** captured
