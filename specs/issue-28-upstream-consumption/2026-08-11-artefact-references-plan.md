# Rich Artefact References Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #5 — rich artefact references with selection scope (Phase 3)
**Issue group:** #28, #29, #30, #31, #5, #10

**Goal:** Make artefact chips in channel-message clickable with type icons,
wiring clicks to the artifact panel via the ARTEFACT_SELECTED event.

**Architecture:** Add ARTEFACT_SELECTED to blocks-ui ChannelEventTopics,
add click handlers and type icons to artefact chips in channel-message.
Chat-app's workbench already catches the event and updates the artifact
panel — no chat-app changes needed.

**Tech Stack:** Lit 3/TypeScript, blocks-ui-channel-activity

## Global Constraints

- blocks-ui changes only — chat-app artifact panel and workbench wiring already complete
- Use `emitPagesEvent` from `@casehubio/agency-ui-core` for event dispatch
- ArtefactRef type from `./types.js` (uri, type, label, scope)
- All styling via `--pages-*` design tokens

---

### Task 1: Add ARTEFACT_SELECTED event and clickable artefact chips

**Files:**
- Modify: `blocks-ui/components/channel-activity/src/events.ts`
- Modify: `blocks-ui/components/channel-activity/src/channel-message.ts`
- Test: `blocks-ui/components/channel-activity/src/channel-message.test.ts`

**Interfaces:**
- Consumes: `emitPagesEvent` from `@casehubio/agency-ui-core`, `ArtefactRef` from `./types.js`
- Produces: `ChannelEventTopics.ARTEFACT_SELECTED: 'channel:artefact-selected'`, click event on `.artefact-chip` dispatching `{ artefactRef: ArtefactRef }`

- [ ] **Step 1: Write the failing test for artefact chip click**

In `channel-message.test.ts`, add:

```typescript
it('clicking artefact chip dispatches artefact-selected event', async () => {
  const el = document.createElement('blocks-channel-message') as any;
  el.message = {
    id: 'msg-1', channelId: 'ch-1', sender: 'alice',
    messageType: 'EVENT', actorType: 'HUMAN',
    content: 'Check this doc', topic: 'General',
    replyCount: 0, createdAt: '2026-01-01T00:00:00Z',
    artefactRefs: [{ uri: 'doc://spec', type: 'DOCUMENT', label: 'Spec' }],
  };
  document.body.appendChild(el);
  await el.updateComplete;

  let receivedRef: any = null;
  el.addEventListener('pages-event', (e: CustomEvent) => {
    if (e.detail.topic === 'channel:artefact-selected') {
      receivedRef = e.detail.payload.artefactRef;
    }
  });

  const chip = el.shadowRoot!.querySelector('.artefact-chip') as HTMLElement;
  chip.click();
  expect(receivedRef).toBeTruthy();
  expect(receivedRef.uri).toBe('doc://spec');
  expect(receivedRef.type).toBe('DOCUMENT');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run --root components/channel-activity` from blocks-ui
Expected: FAIL — chip click dispatches no event

- [ ] **Step 3: Add ARTEFACT_SELECTED to ChannelEventTopics**

In `events.ts`, add to the `ChannelEventTopics` object:

```typescript
ARTEFACT_SELECTED: 'channel:artefact-selected',
```

- [ ] **Step 4: Add click handler and type icon to artefact chips**

In `channel-message.ts`:

Add a type icon helper method:

```typescript
private _artefactIcon(type: string): string {
  switch (type) {
    case 'DOCUMENT': return '📄';
    case 'CODE': return '🔧';
    case 'CASE': return '📋';
    case 'WORK_ITEM': return '✅';
    case 'CHANNEL': case 'MESSAGE': return '💬';
    case 'DEBATE': return '🗣️';
    case 'EXTERNAL': return '🔗';
    default: return '📎';
  }
}
```

Replace the artefact chip rendering (line ~295):

```typescript
// Before:
<span class="artefact-chip" data-type=${ref.type}>${ref.label}</span>

// After:
<span class="artefact-chip" data-type=${ref.type}
  @click=${(e: Event) => { e.stopPropagation(); emitPagesEvent(this, ChannelEventTopics.ARTEFACT_SELECTED, { artefactRef: ref }); }}
>${this._artefactIcon(ref.type)} ${ref.label}</span>
```

Add `ChannelEventTopics` import if not already present (it's in `./events.js`).

- [ ] **Step 5: Write test for type icon rendering**

```typescript
it('artefact chip shows type icon', async () => {
  const el = document.createElement('blocks-channel-message') as any;
  el.message = {
    id: 'msg-1', channelId: 'ch-1', sender: 'alice',
    messageType: 'EVENT', actorType: 'HUMAN',
    content: 'Code ref', topic: 'General',
    replyCount: 0, createdAt: '2026-01-01T00:00:00Z',
    artefactRefs: [{ uri: 'code://file.ts', type: 'CODE', label: 'file.ts' }],
  };
  document.body.appendChild(el);
  await el.updateComplete;

  const chip = el.shadowRoot!.querySelector('.artefact-chip');
  expect(chip?.textContent).toContain('🔧');
  expect(chip?.textContent).toContain('file.ts');
});
```

- [ ] **Step 6: Run all tests**

Run: `npx vitest run --root components/channel-activity`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git -C blocks-ui add components/channel-activity/src/events.ts \
  components/channel-activity/src/channel-message.ts \
  components/channel-activity/src/channel-message.test.ts
git -C blocks-ui commit -m "feat(#5): clickable artefact chips with type icons

Add ARTEFACT_SELECTED to ChannelEventTopics. Artefact chips in
channel-message now dispatch channel:artefact-selected on click with
the ArtefactRef payload. Type icons (📄/🔧/📋/etc.) prefix each chip
label.

Refs casehubio/chat-app#5"
```

- [ ] **Step 8: Verify chat-app frontend tests still pass**

Run: `npx vitest run` from chat-app `src/main/webui`
Expected: ALL PASS (chat-app already handles the event)
