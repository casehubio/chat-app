import { describe, it, expect, vi, afterEach } from 'vitest';
import './qhorus-task-panel.js';
import type { QhorusMessage } from '@casehubio/blocks-ui-channel-activity';
import type { CommitmentRecord } from '../types.js';

function makeCommand(id: string, sender: string, content: string, opts?: { target?: string; correlationId?: string }): QhorusMessage {
  return {
    id, channelId: 'ch1', sender, content, messageType: 'COMMAND',
    actorType: 'AGENT', topic: 'General', replyCount: 0, artefactRefs: [],
    createdAt: '2026-01-01T00:00:00Z', target: opts?.target,
    correlationId: opts?.correlationId ?? id,
  };
}

function makeEvent(id: string, sender: string, content: string): QhorusMessage {
  return {
    id, channelId: 'ch1', sender, content, messageType: 'EVENT',
    actorType: 'HUMAN', topic: 'General', replyCount: 0, artefactRefs: [],
    createdAt: '2026-01-01T00:00:00Z',
  };
}

afterEach(() => { document.body.innerHTML = ''; });

async function render(messages: QhorusMessage[], commitments: Map<string, CommitmentRecord>, selectedId?: string) {
  const el = document.createElement('qhorus-task-panel') as any;
  el.messages = messages;
  el.commitments = commitments;
  if (selectedId) el.selectedMessageId = selectedId;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('QhorusTaskPanelElement', () => {
  it('renders COMMAND messages as task rows', async () => {
    const commitments = new Map<string, CommitmentRecord>([
      ['cmd1', { state: 'OPEN', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }],
    ]);
    const el = await render([makeCommand('cmd1', 'alice', 'Do this')], commitments);
    const rows = el.shadowRoot!.querySelectorAll('.task-row');
    expect(rows.length).toBe(1);
  });

  it('filters out non-COMMAND messages', async () => {
    const el = await render(
      [makeCommand('cmd1', 'alice', 'Do this'), makeEvent('ev1', 'bob', 'Hello')],
      new Map([['cmd1', { state: 'OPEN', createdAt: '', updatedAt: '' }]]),
    );
    const rows = el.shadowRoot!.querySelectorAll('.task-row');
    expect(rows.length).toBe(1);
  });

  it('groups active before terminal', async () => {
    const msgs = [makeCommand('c1', 'a', 'Open task'), makeCommand('c2', 'a', 'Done task')];
    const commitments = new Map<string, CommitmentRecord>([
      ['c1', { state: 'OPEN', createdAt: '', updatedAt: '' }],
      ['c2', { state: 'FULFILLED', createdAt: '', updatedAt: '' }],
    ]);
    const el = await render(msgs, commitments);
    const pills = el.shadowRoot!.querySelectorAll('commitment-state-pill');
    expect(pills[0]!.state).toBe('OPEN');
    expect(pills[1]!.state).toBe('FULFILLED');
  });

  it('shows sender and target', async () => {
    const commitments = new Map<string, CommitmentRecord>([
      ['cmd1', { state: 'OPEN', createdAt: '', updatedAt: '' }],
    ]);
    const el = await render([makeCommand('cmd1', 'alice', 'Do this', { target: 'bob' })], commitments);
    const senderTarget = el.shadowRoot!.querySelector('.sender-target');
    expect(senderTarget!.textContent).toContain('alice');
    expect(senderTarget!.textContent).toContain('bob');
  });

  it('shows empty state when no COMMANDs', async () => {
    const el = await render([], new Map());
    expect(el.shadowRoot!.textContent).toContain('No commitments');
  });

  it('highlights selected row', async () => {
    const commitments = new Map<string, CommitmentRecord>([
      ['cmd1', { state: 'OPEN', createdAt: '', updatedAt: '' }],
    ]);
    const el = await render([makeCommand('cmd1', 'alice', 'Do this')], commitments, 'cmd1');
    const row = el.shadowRoot!.querySelector('.task-row');
    expect(row!.classList.contains('selected')).toBe(true);
  });

  it('looks up commitment by correlationId, not message id', async () => {
    const commitments = new Map<string, CommitmentRecord>([
      ['corr-1', { state: 'FULFILLED', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', resolvedAt: '2026-01-01T00:05:00Z' }],
    ]);
    const el = await render(
      [makeCommand('msg-42', 'alice', 'Do this', { correlationId: 'corr-1' })],
      commitments,
    );
    const pill = el.shadowRoot!.querySelector('commitment-state-pill');
    expect(pill).not.toBeNull();
    expect(pill!.state).toBe('FULFILLED');
  });

  it('renders commitment-range-bar in compact mode', async () => {
    const commitments = new Map<string, CommitmentRecord>([
      ['cmd1', { state: 'OPEN', deadline: '2026-02-01T00:00:00Z', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }],
    ]);
    const el = await render([makeCommand('cmd1', 'alice', 'Do this')], commitments);
    const rangeBar = el.shadowRoot!.querySelector('commitment-range-bar');
    expect(rangeBar).not.toBeNull();
    expect(rangeBar!.mode).toBe('compact');
  });

  it('dispatches message-selected on row click', async () => {
    const commitments = new Map<string, CommitmentRecord>([
      ['cmd1', { state: 'OPEN', createdAt: '', updatedAt: '' }],
    ]);
    const el = await render([makeCommand('cmd1', 'alice', 'Do this')], commitments);
    const handler = vi.fn();
    el.addEventListener('pages-event', handler);
    const row = el.shadowRoot!.querySelector('.task-row') as HTMLElement;
    row.click();
    expect(handler).toHaveBeenCalled();
    expect(handler.mock.calls[0][0].detail.topic).toBe('channel:message-selected');
  });
});
