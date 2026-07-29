import { describe, it, expect, vi, afterEach } from 'vitest';
import './qhorus-correlation-panel.js';
import type { QhorusMessage, CommitmentState } from '@casehubio/blocks-ui-channel-activity';
import type { CommitmentRecord } from '../types.js';

function makeMsg(id: string, sender: string, content: string, type: string, corr?: string, time?: string, target?: string): QhorusMessage {
  return {
    id, channelId: 'ch1', sender, content, messageType: type as any,
    actorType: 'AGENT', topic: 'General', correlationId: corr, replyCount: 0,
    artefactRefs: [], createdAt: time ?? '2026-01-01T10:00:00Z', target,
  };
}

afterEach(() => { document.body.innerHTML = ''; });

async function render(messages: QhorusMessage[], commitments: Map<string, CommitmentRecord>, selectedId?: string) {
  const el = document.createElement('qhorus-correlation-panel') as any;
  el.messages = messages;
  el.commitments = commitments;
  if (selectedId) el.selectedMessageId = selectedId;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('QhorusCorrelationPanelElement', () => {
  it('renders correlation chain for selected message', async () => {
    const msgs = [
      makeMsg('cmd1', 'alice', 'Investigate', 'COMMAND', 'cmd1', '2026-01-01T10:00:00Z'),
      makeMsg('st1', 'alice', 'Working...', 'STATUS', 'cmd1', '2026-01-01T10:02:00Z'),
      makeMsg('done1', 'alice', 'Found it', 'DONE', 'cmd1', '2026-01-01T10:10:00Z'),
    ];
    const el = await render(msgs, new Map(), 'cmd1');
    const nodes = el.shadowRoot!.querySelectorAll('.flow-node');
    expect(nodes.length).toBe(3);
  });

  it('shows duration between nodes', async () => {
    const msgs = [
      makeMsg('cmd1', 'alice', 'Investigate', 'COMMAND', 'cmd1', '2026-01-01T10:00:00Z'),
      makeMsg('st1', 'alice', 'Working', 'STATUS', 'cmd1', '2026-01-01T10:02:14Z'),
    ];
    const el = await render(msgs, new Map(), 'cmd1');
    const durations = el.shadowRoot!.querySelectorAll('.flow-duration');
    expect(durations.length).toBe(1);
    expect(durations[0]!.textContent).toContain('2m');
  });

  it('shows empty state for non-correlated message', async () => {
    const msgs = [makeMsg('m1', 'alice', 'Hello', 'EVENT')];
    const el = await render(msgs, new Map(), 'm1');
    expect(el.shadowRoot!.textContent).toContain('Select a message');
  });

  it('renders commitment-state-pill on obligation-creating message', async () => {
    const msgs = [makeMsg('cmd1', 'alice', 'Do it', 'COMMAND', 'cmd1')];
    const commitments = new Map<string, CommitmentRecord>([
      ['cmd1', { state: 'OPEN' as CommitmentState, createdAt: '', updatedAt: '' }],
    ]);
    const el = await render(msgs, commitments, 'cmd1');
    const pill = el.shadowRoot!.querySelector('commitment-state-pill');
    expect(pill).not.toBeNull();
    expect(pill!.state).toBe('OPEN');
  });

  it('looks up commitment by correlationId, not message id', async () => {
    const msgs = [makeMsg('msg-99', 'alice', 'Do it', 'COMMAND', 'corr-7')];
    const commitments = new Map<string, CommitmentRecord>([
      ['corr-7', { state: 'FULFILLED' as CommitmentState, createdAt: '', updatedAt: '', resolvedAt: '2026-01-01T10:05:00Z' }],
    ]);
    const el = await render(msgs, commitments, 'msg-99');
    const pill = el.shadowRoot!.querySelector('commitment-state-pill');
    expect(pill).not.toBeNull();
    expect(pill!.state).toBe('FULFILLED');
  });

  it('shows delegation indicator on HANDOFF messages', async () => {
    const msgs = [
      makeMsg('cmd1', 'alice', 'Task', 'COMMAND', 'cmd1', '2026-01-01T10:00:00Z'),
      makeMsg('h1', 'alice', 'Handing off', 'HANDOFF', 'cmd1', '2026-01-01T10:05:00Z', 'bob'),
    ];
    const el = await render(msgs, new Map(), 'cmd1');
    const delegation = el.shadowRoot!.querySelector('.delegation-indicator');
    expect(delegation).not.toBeNull();
    expect(delegation!.textContent).toContain('bob');
  });

  it('dispatches message-selected on node click', async () => {
    const msgs = [makeMsg('cmd1', 'alice', 'Do it', 'COMMAND', 'cmd1')];
    const el = await render(msgs, new Map(), 'cmd1');
    const handler = vi.fn();
    el.addEventListener('pages-event', handler);
    const node = el.shadowRoot!.querySelector('.flow-node') as HTMLElement;
    node.click();
    expect(handler).toHaveBeenCalled();
    expect(handler.mock.calls[0][0].detail.topic).toBe('channel:message-selected');
  });

  it('selects chain from any node, not just root', async () => {
    const msgs = [
      makeMsg('cmd1', 'alice', 'Investigate', 'COMMAND', 'cmd1', '2026-01-01T10:00:00Z'),
      makeMsg('st1', 'alice', 'Working', 'STATUS', 'cmd1', '2026-01-01T10:02:00Z'),
      makeMsg('done1', 'alice', 'Done', 'DONE', 'cmd1', '2026-01-01T10:10:00Z'),
    ];
    const el = await render(msgs, new Map(), 'st1');
    const nodes = el.shadowRoot!.querySelectorAll('.flow-node');
    expect(nodes.length).toBe(3);
  });
});
