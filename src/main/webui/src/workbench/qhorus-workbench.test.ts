import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

if (!window.matchMedia) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: query.includes('min-width: 1280'),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    onchange: null,
    dispatchEvent: vi.fn(),
  }));
}

import './qhorus-workbench.js';
import type { QhorusChannel, QhorusMessage, ChannelMember, PresenceState, Reaction } from '@casehubio/blocks-ui-channel-activity';
import { ChannelEventTopics } from '@casehubio/blocks-ui-channel-activity';

vi.mock('../auth.js', () => ({
  getToken: () => 'mock-token',
  getValidToken: () => 'mock-token',
  getIdentity: () => 'test-user',
  authenticatedFetch: vi.fn((url: string, init?: RequestInit) =>
    Promise.resolve(new Response('{}', { status: 200 }))
  ),
}));

async function renderWorkbench(props: Record<string, unknown> = {}): Promise<HTMLElement> {
  const el = document.createElement('qhorus-workbench') as any;
  if (props.endpoint) el.endpoint = props.endpoint;
  if (props.restBase) el.restBase = props.restBase;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('QhorusWorkbenchElement', () => {
  let element: any;

  beforeEach(async () => {
    element = await renderWorkbench();
  });

  it('renders all three panels', () => {
    const shadow = element.shadowRoot!;
    expect(shadow.querySelector('.nav-panel')).toBeTruthy();
    expect(shadow.querySelector('.main-panel')).toBeTruthy();
    expect(shadow.querySelector('.member-panel')).toBeTruthy();
  });

  it('renders channel nav component', () => {
    const shadow = element.shadowRoot!;
    const nav = shadow.querySelector('channel-nav');
    expect(nav).toBeTruthy();
  });

  it('renders channel feed component', () => {
    const shadow = element.shadowRoot!;
    const feed = shadow.querySelector('channel-feed');
    expect(feed).toBeTruthy();
  });

  it('renders message input component', () => {
    const shadow = element.shadowRoot!;
    const input = shadow.querySelector('channel-input');
    expect(input).toBeTruthy();
  });

  it('renders member panel component', () => {
    const shadow = element.shadowRoot!;
    const panel = shadow.querySelector('channel-member-panel');
    expect(panel).toBeTruthy();
  });

  it('updates selected channel on SELECT_CHANNEL event', async () => {
    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.SELECT_CHANNEL,
        payload: { channelId: 'ch-1' },
      },
      bubbles: true,
      composed: true,
    });

    element.dispatchEvent(event);
    await element.updateComplete;

    expect(element._selectedChannelId).toBe('ch-1');
  });

  it('filters messages by selected channel', async () => {
    element._adapter.messages = [
      { id: 'msg-1', channelId: 'ch-1', sender: 'alice', messageType: 'EVENT', actorType: 'HUMAN', content: 'Hello', topic: 'General', replyCount: 0, artefactRefs: [], createdAt: '2026-07-07T12:00:00Z' },
      { id: 'msg-2', channelId: 'ch-2', sender: 'bob', messageType: 'EVENT', actorType: 'HUMAN', content: 'Hi', topic: 'General', replyCount: 0, artefactRefs: [], createdAt: '2026-07-07T12:01:00Z' },
    ];
    element._selectedChannelId = 'ch-1';
    element._onDataChange('messages');
    await element.updateComplete;

    const feed = element.shadowRoot!.querySelector('channel-feed');
    const messages = feed.messages as QhorusMessage[];
    expect(messages.length).toBe(1);
    expect(messages[0].channelId).toBe('ch-1');
  });

  it('filters members by selected channel', async () => {
    element._adapter.members = [
      { channelId: 'ch-1', memberId: 'alice', displayName: 'Alice', role: 'PARTICIPANT', actorType: 'HUMAN' },
      { channelId: 'ch-2', memberId: 'bob', displayName: 'Bob', role: 'PARTICIPANT', actorType: 'HUMAN' },
    ];
    element._selectedChannelId = 'ch-1';
    element._onDataChange('members');
    await element.updateComplete;

    const panel = element.shadowRoot!.querySelector('channel-member-panel');
    const members = panel.members as ChannelMember[];
    expect(members.length).toBe(1);
    expect(members[0].channelId).toBe('ch-1');
  });

  it('sets replyTo on MESSAGE_SELECTED event', async () => {
    const message: QhorusMessage = {
      id: 'msg-1',
      channelId: 'ch-1',
      sender: 'alice',
      messageType: 'EVENT',
      actorType: 'HUMAN',
      content: 'Hello',
      topic: 'General',
      replyCount: 0,
      artefactRefs: [],
      createdAt: '2026-07-07T12:00:00Z',
    };

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.MESSAGE_SELECTED,
        payload: { message },
      },
      bubbles: true,
      composed: true,
    });

    element.dispatchEvent(event);
    await element.updateComplete;

    expect(element._replyTo).toEqual({
      messageId: 'msg-1',
      senderName: 'alice',
    });
  });

  it('calls authenticatedFetch on SEND_MESSAGE event', async () => {
    const { authenticatedFetch } = await import('../auth.js');
    const fetchMock = vi.mocked(authenticatedFetch);
    fetchMock.mockClear();

    element._selectedChannelId = 'ch-1';

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.SEND_MESSAGE,
        payload: { channelId: 'ch-1', content: 'Test message' },
      },
      bubbles: true,
      composed: true,
    });

    element.dispatchEvent(event);
    await element.updateComplete;

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/channels/ch-1/messages',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ text: 'Test message' }),
      })
    );
  });

  it('calls authenticatedFetch on CREATE_CHANNEL event', async () => {
    const { authenticatedFetch } = await import('../auth.js');
    const fetchMock = vi.mocked(authenticatedFetch);
    fetchMock.mockClear();

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.CREATE_CHANNEL,
        payload: { name: 'new-channel' },
      },
      bubbles: true,
      composed: true,
    });

    element.dispatchEvent(event);
    await element.updateComplete;

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/channels',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ name: 'new-channel' }),
      })
    );
  });

  it('calls authenticatedFetch on DELETE_CHANNEL event', async () => {
    const { authenticatedFetch } = await import('../auth.js');
    const fetchMock = vi.mocked(authenticatedFetch);
    fetchMock.mockClear();

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.DELETE_CHANNEL,
        payload: { channelId: 'ch-1' },
      },
      bubbles: true,
      composed: true,
    });

    element.dispatchEvent(event);
    await element.updateComplete;

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/channels/ch-1',
      expect.objectContaining({ method: 'DELETE' })
    );
  });

  describe('WebSocket integration', () => {
    let OriginalWebSocket: typeof WebSocket;
    let mockWsInstances: any[];

    beforeEach(() => {
      OriginalWebSocket = globalThis.WebSocket;
      mockWsInstances = [];
      (globalThis as any).WebSocket = class MockWebSocket {
        url: string;
        onopen: ((e: any) => void) | null = null;
        onmessage: ((e: any) => void) | null = null;
        onclose: ((e: any) => void) | null = null;
        onerror: ((e: any) => void) | null = null;
        readyState = 1;
        close = vi.fn();
        constructor(url: string) {
          this.url = url;
          mockWsInstances.push(this);
        }
      };
    });

    afterEach(() => {
      globalThis.WebSocket = OriginalWebSocket;
    });

    it('creates WebSocket on connectedCallback when endpoint and token are set', async () => {
      const el = document.createElement('qhorus-workbench') as any;
      el.endpoint = '/ws/chat';
      document.body.appendChild(el);
      await el.updateComplete;

      expect(mockWsInstances.length).toBeGreaterThanOrEqual(1);
      const ws = mockWsInstances[mockWsInstances.length - 1];
      expect(ws.url).toContain('/ws/chat');
      expect(ws.url).toContain('token=mock-token');
    });

    it('applies adapter op on WebSocket message', async () => {
      const el = document.createElement('qhorus-workbench') as any;
      el.endpoint = '/ws/chat';
      document.body.appendChild(el);
      await el.updateComplete;

      const ws = mockWsInstances[mockWsInstances.length - 1];
      ws.onopen?.({});
      const applyOpSpy = vi.spyOn(el._adapter, 'applyOp');

      const opData = { op: 'snapshot', dataset: 'channels', rows: [['ch-1', 'general', '']] };
      ws.onmessage?.({ data: JSON.stringify(opData) });

      expect(applyOpSpy).toHaveBeenCalledWith(opData);
    });

    it('reconnects after WebSocket close', async () => {
      vi.useFakeTimers();
      const el = document.createElement('qhorus-workbench') as any;
      el.endpoint = '/ws/chat';
      document.body.appendChild(el);
      await el.updateComplete;

      const initialCount = mockWsInstances.length;
      const ws = mockWsInstances[mockWsInstances.length - 1];
      ws.onopen?.({});
      ws.onclose?.({ code: 1006 });

      vi.advanceTimersByTime(1000);
      expect(mockWsInstances.length).toBe(initialCount + 1);
      vi.useRealTimers();
    });

    it('handles malformed WebSocket message without crashing', async () => {
      const el = document.createElement('qhorus-workbench') as any;
      el.endpoint = '/ws/chat';
      document.body.appendChild(el);
      await el.updateComplete;

      const ws = mockWsInstances[mockWsInstances.length - 1];
      ws.onopen?.({});
      expect(() => ws.onmessage?.({ data: 'not-json{{{' })).not.toThrow();
    });
  });

  describe('REST error handling', () => {
    it('catches _sendMessage network error without unhandled rejection', async () => {
      const { authenticatedFetch } = await import('../auth.js');
      const fetchMock = vi.mocked(authenticatedFetch);
      fetchMock.mockRejectedValueOnce(new Error('Network error'));
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      element._selectedChannelId = 'ch-1';
      const event = new CustomEvent('pages-event', {
        detail: {
          topic: ChannelEventTopics.SEND_MESSAGE,
          payload: { channelId: 'ch-1', content: 'Test' },
        },
        bubbles: true, composed: true,
      });
      element.dispatchEvent(event);

      await new Promise(r => setTimeout(r, 0));
      expect(errorSpy).toHaveBeenCalledWith('Failed to send message:', expect.any(Error));
      errorSpy.mockRestore();
    });

    it('catches _createChannel error without unhandled rejection', async () => {
      const { authenticatedFetch } = await import('../auth.js');
      const fetchMock = vi.mocked(authenticatedFetch);
      fetchMock.mockRejectedValueOnce(new Error('Network error'));
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      const event = new CustomEvent('pages-event', {
        detail: {
          topic: ChannelEventTopics.CREATE_CHANNEL,
          payload: { name: 'fail-channel' },
        },
        bubbles: true, composed: true,
      });
      element.dispatchEvent(event);

      await new Promise(r => setTimeout(r, 0));
      expect(errorSpy).toHaveBeenCalledWith('Failed to create channel:', expect.any(Error));
      errorSpy.mockRestore();
    });

    it('catches _deleteChannel error without unhandled rejection', async () => {
      const { authenticatedFetch } = await import('../auth.js');
      const fetchMock = vi.mocked(authenticatedFetch);
      fetchMock.mockRejectedValueOnce(new Error('Network error'));
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      const event = new CustomEvent('pages-event', {
        detail: {
          topic: ChannelEventTopics.DELETE_CHANNEL,
          payload: { channelId: 'ch-1' },
        },
        bubbles: true, composed: true,
      });
      element.dispatchEvent(event);

      await new Promise(r => setTimeout(r, 0));
      expect(errorSpy).toHaveBeenCalledWith('Failed to delete channel:', expect.any(Error));
      errorSpy.mockRestore();
    });
  });

  it('routes REACT event to authenticatedFetch with correct URL', async () => {
    const { authenticatedFetch } = await import('../auth.js');
    const fetchMock = vi.mocked(authenticatedFetch);
    fetchMock.mockClear();

    element._adapter.messages = [
      { id: 'msg-1', channelId: 'ch-1', sender: 'alice', messageType: 'EVENT', actorType: 'HUMAN', content: 'Hello', topic: 'General', replyCount: 0, artefactRefs: [], createdAt: '2026-07-07T12:00:00Z' },
    ];
    element._onDataChange('messages');

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.REACT,
        payload: { messageId: 'msg-1', emoji: '👍' },
      },
      bubbles: true, composed: true,
    });
    element.dispatchEvent(event);

    await new Promise(r => setTimeout(r, 0));
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/channels/ch-1/messages/msg-1/reactions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ emoji: '👍' }),
      })
    );
  });

  it('routes UNREACT event to authenticatedFetch with DELETE and correct URL', async () => {
    const { authenticatedFetch } = await import('../auth.js');
    const fetchMock = vi.mocked(authenticatedFetch);
    fetchMock.mockClear();

    element._adapter.messages = [
      { id: 'msg-1', channelId: 'ch-1', sender: 'alice', messageType: 'EVENT', actorType: 'HUMAN', content: 'Hello', topic: 'General', replyCount: 0, artefactRefs: [], createdAt: '2026-07-07T12:00:00Z' },
    ];
    element._onDataChange('messages');

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.UNREACT,
        payload: { messageId: 'msg-1', emoji: '👍' },
      },
      bubbles: true, composed: true,
    });
    element.dispatchEvent(event);

    await new Promise(r => setTimeout(r, 0));
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/channels/ch-1/messages/msg-1/reactions/👍',
      expect.objectContaining({ method: 'DELETE' })
    );
  });

  it('constructs reply URL with inReplyTo path segment', async () => {
    const { authenticatedFetch } = await import('../auth.js');
    const fetchMock = vi.mocked(authenticatedFetch);
    fetchMock.mockClear();

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.SEND_MESSAGE,
        payload: { channelId: 'ch-1', content: 'Reply text', inReplyTo: 'msg-parent' },
      },
      bubbles: true, composed: true,
    });
    element.dispatchEvent(event);

    await new Promise(r => setTimeout(r, 0));
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/channels/ch-1/messages/msg-parent/replies',
      expect.objectContaining({ method: 'POST' })
    );
  });

  it('filters reactions to only those belonging to selected channel messages', async () => {
    element._adapter.messages = [
      { id: 'msg-1', channelId: 'ch-1', sender: 'alice', messageType: 'EVENT', actorType: 'HUMAN', content: 'Hello', topic: 'General', replyCount: 0, artefactRefs: [], createdAt: '2026-07-07T12:00:00Z' },
      { id: 'msg-2', channelId: 'ch-2', sender: 'bob', messageType: 'EVENT', actorType: 'HUMAN', content: 'Hi', topic: 'General', replyCount: 0, artefactRefs: [], createdAt: '2026-07-07T12:01:00Z' },
    ];
    element._adapter.reactions = [
      { messageId: 'msg-1', emoji: '👍', actorId: '', createdAt: '' },
      { messageId: 'msg-2', emoji: '❤️', actorId: '', createdAt: '' },
    ];
    element._selectedChannelId = 'ch-1';
    element._onDataChange('messages');
    await element.updateComplete;

    const feed = element.shadowRoot!.querySelector('channel-feed');
    const reactions = feed.reactions as Reaction[];
    expect(reactions.length).toBe(1);
    expect(reactions[0].messageId).toBe('msg-1');
    expect(reactions[0].emoji).toBe('👍');
  });

  it('clears _replyTo after successful send', async () => {
    const { authenticatedFetch } = await import('../auth.js');
    const fetchMock = vi.mocked(authenticatedFetch);
    fetchMock.mockClear();
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 200 }));

    element._replyTo = { messageId: 'msg-1', senderName: 'alice' };

    const event = new CustomEvent('pages-event', {
      detail: {
        topic: ChannelEventTopics.SEND_MESSAGE,
        payload: { channelId: 'ch-1', content: 'Reply' },
      },
      bubbles: true, composed: true,
    });
    element.dispatchEvent(event);

    await new Promise(r => setTimeout(r, 0));
    expect(element._replyTo).toBeUndefined();
  });

  it('cleans up WebSocket and timeout on disconnectedCallback', async () => {
    vi.useFakeTimers();

    let OriginalWebSocket = globalThis.WebSocket;
    let mockWsInstances: any[] = [];
    (globalThis as any).WebSocket = class MockWebSocket {
      url: string;
      onopen: ((e: any) => void) | null = null;
      onmessage: ((e: any) => void) | null = null;
      onclose: ((e: any) => void) | null = null;
      onerror: ((e: any) => void) | null = null;
      readyState = 1;
      close = vi.fn();
      constructor(url: string) {
        this.url = url;
        mockWsInstances.push(this);
      }
    };

    const el = document.createElement('qhorus-workbench') as any;
    el.endpoint = '/ws/chat';
    document.body.appendChild(el);
    await el.updateComplete;

    const ws = mockWsInstances[mockWsInstances.length - 1];
    ws.onopen?.({});
    ws.onclose?.({ code: 1006 });

    el.remove();

    const wsCountBefore = mockWsInstances.length;
    vi.advanceTimersByTime(5000);
    expect(mockWsInstances.length).toBe(wsCountBefore);

    globalThis.WebSocket = OriginalWebSocket;
    vi.useRealTimers();
  });

  describe('theme toggle', () => {
    it('starts in light mode with pages-theme-light class', async () => {
      const el = await renderWorkbench();
      await el.updateComplete;
      expect(el.classList.contains('pages-theme-light')).toBe(true);
      expect(el.classList.contains('pages-theme-dark')).toBe(false);
    });

    it('toggles to dark mode', async () => {
      const el = await renderWorkbench() as any;
      el._toggleTheme();
      await el.updateComplete;
      expect(el.classList.contains('pages-theme-dark')).toBe(true);
      expect(el.classList.contains('pages-theme-light')).toBe(false);
      expect(el._darkMode).toBe(true);
    });

    it('toggles back to light mode', async () => {
      const el = await renderWorkbench() as any;
      el._toggleTheme();
      el._toggleTheme();
      await el.updateComplete;
      expect(el.classList.contains('pages-theme-light')).toBe(true);
      expect(el._darkMode).toBe(false);
    });

    it('renders theme toggle button in dock strip', async () => {
      const el = await renderWorkbench();
      const toggle = el.shadowRoot!.querySelector('.dock-strip .dock-btn:last-child');
      expect(toggle).toBeTruthy();
      expect(toggle!.getAttribute('title')).toBe('Dark mode');
    });
  });

  describe('layout structure', () => {
    it('channel-feed fills available space in main panel', async () => {
      const el = await renderWorkbench();
      const styles = (el.constructor as any).styles;
      const cssText = Array.isArray(styles) ? styles.map((s: any) => s.cssText).join('\n') : styles.cssText;
      expect(cssText).toContain('channel-feed');
      expect(cssText).toMatch(/channel-feed[^}]*flex:\s*1/);
      expect(cssText).toMatch(/channel-feed[^}]*min-height:\s*0/);
    });

    it('message-input is pinned to bottom', async () => {
      const el = await renderWorkbench();
      const styles = (el.constructor as any).styles;
      const cssText = Array.isArray(styles) ? styles.map((s: any) => s.cssText).join('\n') : styles.cssText;
      expect(cssText).toContain('channel-input');
      expect(cssText).toMatch(/channel-input[^}]*flex-shrink:\s*0/);
    });
  });

  describe('swipe gestures', () => {
    it('has SwipeController attached', async () => {
      const el = await renderWorkbench() as any;
      expect(el._swipeController).toBeDefined();
    });
  });
});
