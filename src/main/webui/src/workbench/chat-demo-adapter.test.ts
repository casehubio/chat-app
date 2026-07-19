import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ChatDemoAdapter } from './chat-demo-adapter.js';

describe('ChatDemoAdapter', () => {
  it('maps channel snapshot to QhorusChannel array', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'channels',
      rows: [['ch-1', 'general', 'General chat'], ['ch-2', 'random', 'Random']],
    });
    expect(adapter.channels.length).toBe(2);
    expect(adapter.channels[0].name).toBe('general');
    expect(adapter.channels[0].semantic).toBe('APPEND');
  });

  it('maps message snapshot with default messageType and actorType', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch-1', 'msg-1', null, 'alice', 'Hello', '2026-07-07T12:00:00Z']],
    });
    expect(adapter.messages.length).toBe(1);
    expect(adapter.messages[0].messageType).toBe('EVENT');
    expect(adapter.messages[0].actorType).toBe('HUMAN');
    expect(adapter.messages[0].content).toBe('Hello');
  });

  it('handles append op by adding to existing array', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch-1', 'msg-1', null, 'alice', 'First', '2026-07-07T12:00:00Z']],
    });
    adapter.applyOp({
      op: 'append', dataset: 'messages',
      rows: [['ch-1', 'msg-2', null, 'bob', 'Second', '2026-07-07T12:01:00Z']],
    });
    expect(adapter.messages.length).toBe(2);
  });

  it('handles remove op', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'channels',
      rows: [['ch-1', 'general', ''], ['ch-2', 'random', '']],
    });
    adapter.applyOp({
      op: 'remove', dataset: 'channels', key: 'ch-2',
    });
    expect(adapter.channels.length).toBe(1);
    expect(adapter.channels[0].id).toBe('ch-1');
  });

  it('maps presence snapshot', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'presence',
      rows: [['alice', 'ONLINE'], ['bob', 'AWAY']],
    });
    expect(adapter.presence.length).toBe(2);
    expect(adapter.presence[0].status).toBe('ONLINE');
    expect(adapter.presence[1].status).toBe('AWAY');
  });

  it('maps member snapshot', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'members',
      rows: [['m-1', 'ch-1', 'alice', 'Alice']],
    });
    expect(adapter.members.length).toBe(1);
    expect(adapter.members[0].displayName).toBe('Alice');
    expect(adapter.members[0].role).toBe('PARTICIPANT');
  });

  it('notifies listeners on data change', () => {
    const adapter = new ChatDemoAdapter();
    const listener = vi.fn();
    adapter.onChange(listener);

    adapter.applyOp({
      op: 'snapshot', dataset: 'channels',
      rows: [['ch-1', 'general', '']],
    });

    expect(listener).toHaveBeenCalledWith('channels');
  });

  it('handles reactions snapshot', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'reactions',
      rows: [['msg-1', '👍'], ['msg-1', '❤️'], ['msg-2', '😊']],
    });
    expect(adapter.reactions.length).toBe(3);
    expect(adapter.reactions[0].messageId).toBe('msg-1');
    expect(adapter.reactions[0].emoji).toBe('👍');
  });

  it('handles reactions append', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'reactions',
      rows: [['msg-1', '👍']],
    });
    adapter.applyOp({
      op: 'append', dataset: 'reactions',
      rows: [['msg-1', '❤️']],
    });
    expect(adapter.reactions.length).toBe(2);
  });

  it('handles presence replace op', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'presence',
      rows: [['alice', 'ONLINE'], ['bob', 'AWAY']],
    });
    adapter.applyOp({
      op: 'replace', dataset: 'presence',
      row: ['alice', 'OFFLINE'],
    });
    const alice = adapter.presence.find(p => p.memberId === 'alice');
    expect(alice?.status).toBe('OFFLINE');
  });

  it('offChange removes listener', () => {
    const adapter = new ChatDemoAdapter();
    const listener = vi.fn();
    adapter.onChange(listener);
    adapter.offChange(listener);

    adapter.applyOp({
      op: 'snapshot', dataset: 'channels',
      rows: [['ch-1', 'general', '']],
    });

    expect(listener).not.toHaveBeenCalled();
  });

  it('removes a message by key', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [
        ['ch-1', 'msg-1', null, 'alice', 'First', '2026-07-07T12:00:00Z'],
        ['ch-1', 'msg-2', null, 'bob', 'Second', '2026-07-07T12:01:00Z'],
        ['ch-1', 'msg-3', null, 'carol', 'Third', '2026-07-07T12:02:00Z'],
      ],
    });
    adapter.applyOp({ op: 'remove', dataset: 'messages', key: 'msg-2' });
    expect(adapter.messages.length).toBe(2);
    expect(adapter.messages.map(m => m.id)).toEqual(['msg-1', 'msg-3']);
  });

  it('removes a reaction by messageId:emoji key', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'reactions',
      rows: [['msg-1', '👍'], ['msg-1', '❤️'], ['msg-2', '😊']],
    });
    adapter.applyOp({ op: 'remove', dataset: 'reactions', key: 'msg-1:❤️' });
    expect(adapter.reactions.length).toBe(2);
    expect(adapter.reactions.map(r => `${r.messageId}:${r.emoji}`)).toEqual(['msg-1:👍', 'msg-2:😊']);
  });

  it('removes a member by channelId:memberId key', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'members',
      rows: [['m-1', 'ch-1', 'alice', 'Alice'], ['m-2', 'ch-1', 'bob', 'Bob']],
    });
    adapter.applyOp({ op: 'remove', dataset: 'members', key: 'ch-1:bob' });
    expect(adapter.members.length).toBe(1);
    expect(adapter.members[0].memberId).toBe('alice');
  });

  it('appends a member to existing list', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'members',
      rows: [['m-1', 'ch-1', 'alice', 'Alice']],
    });
    adapter.applyOp({
      op: 'append', dataset: 'members',
      rows: [['m-2', 'ch-1', 'bob', 'Bob']],
    });
    expect(adapter.members.length).toBe(2);
    expect(adapter.members[1].memberId).toBe('bob');
    expect(adapter.members[1].displayName).toBe('Bob');
  });

  it('appends a channel to existing list', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'channels',
      rows: [['ch-1', 'general', 'General chat']],
    });
    adapter.applyOp({
      op: 'append', dataset: 'channels',
      rows: [['ch-2', 'random', 'Random talk']],
    });
    expect(adapter.channels.length).toBe(2);
    expect(adapter.channels[1].id).toBe('ch-2');
    expect(adapter.channels[1].name).toBe('random');
  });

  it('onChange fires with "messages" dataset name', () => {
    const adapter = new ChatDemoAdapter();
    const listener = vi.fn();
    adapter.onChange(listener);
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch-1', 'msg-1', null, 'alice', 'Hello', '2026-07-07T12:00:00Z']],
    });
    expect(listener).toHaveBeenCalledWith('messages');
  });

  it('onChange fires with "reactions" dataset name', () => {
    const adapter = new ChatDemoAdapter();
    const listener = vi.fn();
    adapter.onChange(listener);
    adapter.applyOp({
      op: 'snapshot', dataset: 'reactions',
      rows: [['msg-1', '👍']],
    });
    expect(listener).toHaveBeenCalledWith('reactions');
  });

  it('onChange fires with "members" dataset name', () => {
    const adapter = new ChatDemoAdapter();
    const listener = vi.fn();
    adapter.onChange(listener);
    adapter.applyOp({
      op: 'snapshot', dataset: 'members',
      rows: [['m-1', 'ch-1', 'alice', 'Alice']],
    });
    expect(listener).toHaveBeenCalledWith('members');
  });

  it('onChange fires with "presence" dataset name', () => {
    const adapter = new ChatDemoAdapter();
    const listener = vi.fn();
    adapter.onChange(listener);
    adapter.applyOp({
      op: 'snapshot', dataset: 'presence',
      rows: [['alice', 'ONLINE']],
    });
    expect(listener).toHaveBeenCalledWith('presence');
  });

  it('snapshot with empty rows clears collection', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'channels',
      rows: [['ch-1', 'general', ''], ['ch-2', 'random', '']],
    });
    expect(adapter.channels.length).toBe(2);
    adapter.applyOp({ op: 'snapshot', dataset: 'channels', rows: [] });
    expect(adapter.channels).toEqual([]);
  });

  it('duplicate append produces duplicates (Phase 1 behavior)', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch-1', 'msg-1', null, 'alice', 'Hello', '2026-07-07T12:00:00Z']],
    });
    adapter.applyOp({
      op: 'append', dataset: 'messages',
      rows: [['ch-1', 'msg-1', null, 'alice', 'Hello', '2026-07-07T12:00:00Z']],
    });
    expect(adapter.messages.length).toBe(2);
    expect(adapter.messages[0].id).toBe('msg-1');
    expect(adapter.messages[1].id).toBe('msg-1');
  });

  it('presence replace for non-existent member leaves array unchanged', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'presence',
      rows: [['alice', 'ONLINE'], ['bob', 'AWAY']],
    });
    const before = [...adapter.presence];
    adapter.applyOp({
      op: 'replace', dataset: 'presence',
      row: ['unknown-member', 'OFFLINE'],
    });
    expect(adapter.presence.length).toBe(2);
    expect(adapter.presence).toEqual(before);
  });

  it('computes replyCount from inReplyTo references', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [
        ['ch-1', 'msg-1', null, 'alice', 'Root message', '2026-07-07T12:00:00Z'],
        ['ch-1', 'msg-2', 'msg-1', 'bob', 'Reply 1', '2026-07-07T12:01:00Z'],
        ['ch-1', 'msg-3', 'msg-1', 'carol', 'Reply 2', '2026-07-07T12:02:00Z'],
        ['ch-1', 'msg-4', null, 'dave', 'Standalone', '2026-07-07T12:03:00Z'],
      ],
    });
    expect(adapter.messages.find(m => m.id === 'msg-1')!.replyCount).toBe(2);
    expect(adapter.messages.find(m => m.id === 'msg-4')!.replyCount).toBe(0);
    expect(adapter.messages.find(m => m.id === 'msg-2')!.replyCount).toBe(0);
  });

  it('updates replyCount after append adds a reply', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch-1', 'msg-1', null, 'alice', 'Root', '2026-07-07T12:00:00Z']],
    });
    expect(adapter.messages[0].replyCount).toBe(0);
    adapter.applyOp({
      op: 'append', dataset: 'messages',
      rows: [['ch-1', 'msg-2', 'msg-1', 'bob', 'Reply', '2026-07-07T12:01:00Z']],
    });
    expect(adapter.messages.find(m => m.id === 'msg-1')!.replyCount).toBe(1);
  });

  it('maps correlationId from row index 9', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch1', 'msg1', null, 'alice', 'hello', '2026-01-01T00:00:00Z',
              'COMMAND', 'AGENT', 'General', 'msg1', '[]', 'bob']],
    });
    expect(adapter.messages[0]!.correlationId).toBe('msg1');
  });

  it('parses artefactRefs from JSON at row index 10', () => {
    const adapter = new ChatDemoAdapter();
    const refs = JSON.stringify([{uri: 'doc.md', type: 'DOCUMENT', label: 'Doc'}]);
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch1', 'msg1', null, 'alice', 'hello', '2026-01-01T00:00:00Z',
              'EVENT', 'HUMAN', 'General', null, refs, null]],
    });
    expect(adapter.messages[0]!.artefactRefs).toHaveLength(1);
    expect(adapter.messages[0]!.artefactRefs[0]!.uri).toBe('doc.md');
  });

  it('maps target from row index 11', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch1', 'msg1', null, 'alice', 'hello', '2026-01-01T00:00:00Z',
              'COMMAND', 'AGENT', 'General', 'msg1', '[]', 'bot-b']],
    });
    expect(adapter.messages[0]!.target).toBe('bot-b');
  });

  it('processes commitments dataset snapshot', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'commitments',
      rows: [['c1', 'ch1', 'OPEN', '', '', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']],
    });
    expect(adapter.commitments.get('c1')?.state).toBe('OPEN');
  });

  it('processes commitments dataset replace', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'commitments',
      rows: [['c1', 'ch1', 'OPEN', '', '', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']],
    });
    adapter.applyOp({
      op: 'replace', dataset: 'commitments',
      key: 'c1',
      row: ['c1', 'ch1', 'FULFILLED', '', '', '2026-01-01T00:00:00Z', '2026-01-01T00:01:00Z'],
    });
    expect(adapter.commitments.get('c1')?.state).toBe('FULFILLED');
  });

  it('processes commitments dataset append', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'append', dataset: 'commitments',
      rows: [['c1', 'ch1', 'OPEN', '', '', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']],
    });
    expect(adapter.commitments.get('c1')?.state).toBe('OPEN');
  });

  // --- Topics dataset ---

  it('processes topics dataset snapshot', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'topics',
      rows: [['t1', 'ch1', 'General', 'ACTIVE', '5', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']],
    });
    expect(adapter.topics.length).toBe(1);
    expect(adapter.topics[0].name).toBe('General');
    expect(adapter.topics[0].state).toBe('ACTIVE');
  });

  it('processes topics dataset append', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'topics',
      rows: [['t1', 'ch1', 'General', 'ACTIVE', '5', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']],
    });
    adapter.applyOp({
      op: 'append', dataset: 'topics',
      rows: [['t2', 'ch1', 'deployment', 'ACTIVE', '0', '', '2026-01-01T00:01:00Z']],
    });
    expect(adapter.topics.length).toBe(2);
  });

  it('processes topics dataset replace', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'topics',
      rows: [['t1', 'ch1', 'General', 'ACTIVE', '5', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']],
    });
    adapter.applyOp({
      op: 'replace', dataset: 'topics',
      key: 't1',
      row: ['t1', 'ch1', 'General', 'RESOLVED', '5', '2026-01-01T00:00:00Z', '2026-01-01T00:01:00Z'],
    });
    expect(adapter.topics[0].state).toBe('RESOLVED');
  });

  it('processes topics dataset remove', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'topics',
      rows: [
        ['t1', 'ch1', 'General', 'ACTIVE', '5', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'],
        ['t2', 'ch1', 'old', 'MERGED', '0', '', '2026-01-01T00:00:00Z'],
      ],
    });
    adapter.applyOp({ op: 'remove', dataset: 'topics', key: 't2' });
    expect(adapter.topics.length).toBe(1);
  });

  it('_toMessage maps topicId from row[8] and resolves name', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'topics',
      rows: [['t1', 'ch1', 'General', 'ACTIVE', '5', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z']],
    });
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch1', 'msg1', null, 'alice', 'hello', '2026-01-01T00:00:00Z',
              'EVENT', 'HUMAN', 't1', null, '[]', null]],
    });
    expect(adapter.messages[0].topicId).toBe('t1');
    expect(adapter.messages[0].topic).toBe('General');
  });

  it('_toMessage with unknown topicId falls back to empty string', () => {
    const adapter = new ChatDemoAdapter();
    adapter.applyOp({
      op: 'snapshot', dataset: 'messages',
      rows: [['ch1', 'msg1', null, 'alice', 'hello', '2026-01-01T00:00:00Z',
              'EVENT', 'HUMAN', 'unknown-id', null, '[]', null]],
    });
    expect(adapter.messages[0].topicId).toBe('unknown-id');
    expect(adapter.messages[0].topic).toBe('');
  });

  it('onChange fires with "topics" dataset name', () => {
    const adapter = new ChatDemoAdapter();
    const listener = vi.fn();
    adapter.onChange(listener);
    adapter.applyOp({
      op: 'snapshot', dataset: 'topics',
      rows: [['t1', 'ch1', 'General', 'ACTIVE', '5', '', '2026-01-01T00:00:00Z']],
    });
    expect(listener).toHaveBeenCalledWith('topics');
  });
});
