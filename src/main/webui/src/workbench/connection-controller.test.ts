import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ConnectionController } from './connection-controller.js';
import type { ReactiveControllerHost } from 'lit';

let mockWsInstances: any[] = [];
let OriginalWebSocket: typeof WebSocket;

function mockHost(): ReactiveControllerHost & { updateComplete: Promise<boolean> } {
  return {
    updateComplete: Promise.resolve(true),
    addController: vi.fn(),
    removeController() {},
    requestUpdate: vi.fn(),
  };
}

beforeEach(() => {
  OriginalWebSocket = globalThis.WebSocket;
  mockWsInstances = [];
  (globalThis as any).WebSocket = class MockWebSocket {
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    static readonly CLOSING = 2;
    static readonly CLOSED = 3;
    url: string;
    readyState = 1;
    onopen: ((e: any) => void) | null = null;
    onmessage: ((e: any) => void) | null = null;
    onclose: ((e: any) => void) | null = null;
    onerror: ((e: any) => void) | null = null;
    close = vi.fn(() => { this.readyState = 3; });
    send = vi.fn();
    constructor(url: string) {
      this.url = url;
      mockWsInstances.push(this);
    }
  };
});

afterEach(() => {
  globalThis.WebSocket = OriginalWebSocket;
  vi.useRealTimers();
});

describe('ConnectionController', () => {
  it('registers as a Lit reactive controller', () => {
    const host = mockHost();
    const ctrl = new ConnectionController(host);
    expect(host.addController).toHaveBeenCalledWith(ctrl);
  });

  it('starts in disconnected state', () => {
    const ctrl = new ConnectionController(mockHost());
    expect(ctrl.state).toBe('disconnected');
  });

  it('transitions to connecting then connected on open', () => {
    const host = mockHost();
    const ctrl = new ConnectionController(host);
    ctrl.connect('ws://localhost/ws', 'token-123');

    expect(ctrl.state).toBe('connecting');
    expect(mockWsInstances.length).toBe(1);
    expect(mockWsInstances[0].url).toContain('ws://localhost/ws');
    expect(mockWsInstances[0].url).toContain('token=token-123');

    mockWsInstances[0].onopen?.({});
    expect(ctrl.state).toBe('connected');
    expect(host.requestUpdate).toHaveBeenCalled();
  });

  it('calls onMessage callback for each parsed op', () => {
    const onMessage = vi.fn();
    const ctrl = new ConnectionController(mockHost(), { onMessage });
    ctrl.connect('ws://localhost/ws', 'tok');

    const ws = mockWsInstances[0];
    ws.onopen?.({});
    ws.onmessage?.({ data: JSON.stringify({ op: 'snapshot', dataset: 'channels', rows: [] }) });

    expect(onMessage).toHaveBeenCalledWith({ op: 'snapshot', dataset: 'channels', rows: [] });
  });

  it('handles array of ops in single message', () => {
    const onMessage = vi.fn();
    const ctrl = new ConnectionController(mockHost(), { onMessage });
    ctrl.connect('ws://localhost/ws', 'tok');

    const ws = mockWsInstances[0];
    ws.onopen?.({});
    ws.onmessage?.({ data: JSON.stringify([
      { op: 'snapshot', dataset: 'channels', rows: [] },
      { op: 'append', dataset: 'messages', rows: [] },
    ]) });

    expect(onMessage).toHaveBeenCalledTimes(2);
  });

  it('ignores malformed messages without crashing', () => {
    const onMessage = vi.fn();
    const ctrl = new ConnectionController(mockHost(), { onMessage });
    ctrl.connect('ws://localhost/ws', 'tok');

    const ws = mockWsInstances[0];
    ws.onopen?.({});
    expect(() => ws.onmessage?.({ data: 'not-json{{{' })).not.toThrow();
    expect(onMessage).not.toHaveBeenCalled();
  });

  describe('exponential backoff', () => {
    it('reconnects with initial delay after first close', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      const ws = mockWsInstances[0];
      ws.onopen?.({});
      ws.onclose?.({ code: 1006 });

      expect(ctrl.state).toBe('reconnecting');
      expect(mockWsInstances.length).toBe(1);

      vi.advanceTimersByTime(1000);
      expect(mockWsInstances.length).toBe(2);
    });

    it('doubles delay on each consecutive failure', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      // First connection succeeds then closes
      mockWsInstances[0].onopen?.({});
      mockWsInstances[0].onclose?.({ code: 1006 });

      // Reconnect 1 at 1s
      vi.advanceTimersByTime(1000);
      expect(mockWsInstances.length).toBe(2);
      mockWsInstances[1].onclose?.({ code: 1006 });

      // Reconnect 2 at 2s
      vi.advanceTimersByTime(1999);
      expect(mockWsInstances.length).toBe(2);
      vi.advanceTimersByTime(1);
      expect(mockWsInstances.length).toBe(3);
      mockWsInstances[2].onclose?.({ code: 1006 });

      // Reconnect 3 at 4s
      vi.advanceTimersByTime(3999);
      expect(mockWsInstances.length).toBe(3);
      vi.advanceTimersByTime(1);
      expect(mockWsInstances.length).toBe(4);
    });

    it('caps delay at 30 seconds', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      mockWsInstances[0].onopen?.({});

      // Simulate many failures to exceed cap: 1, 2, 4, 8, 16, 32->30
      for (let i = 0; i < 6; i++) {
        mockWsInstances[mockWsInstances.length - 1].onclose?.({ code: 1006 });
        vi.advanceTimersByTime(30_000);
      }

      const count = mockWsInstances.length;
      mockWsInstances[mockWsInstances.length - 1].onclose?.({ code: 1006 });

      // Should reconnect at 30s, not 64s
      vi.advanceTimersByTime(30_000);
      expect(mockWsInstances.length).toBe(count + 1);
    });

    it('resets backoff after successful reconnection', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      mockWsInstances[0].onopen?.({});
      mockWsInstances[0].onclose?.({ code: 1006 });

      // Reconnect at 1s, then close again
      vi.advanceTimersByTime(1000);
      mockWsInstances[1].onclose?.({ code: 1006 });

      // Reconnect at 2s — succeeds this time
      vi.advanceTimersByTime(2000);
      mockWsInstances[2].onopen?.({});
      expect(ctrl.state).toBe('connected');

      // Close again — should retry at 1s (reset), not 4s
      mockWsInstances[2].onclose?.({ code: 1006 });
      const countBefore = mockWsInstances.length;
      vi.advanceTimersByTime(1000);
      expect(mockWsInstances.length).toBe(countBefore + 1);
    });
  });

  describe('error classification', () => {
    it('does not retry on auth failure (close code 4401)', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      mockWsInstances[0].onopen?.({});
      mockWsInstances[0].onclose?.({ code: 4401 });

      expect(ctrl.state).toBe('disconnected');
      vi.advanceTimersByTime(60_000);
      expect(mockWsInstances.length).toBe(1);
    });

    it('does not retry on normal closure (1000)', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      mockWsInstances[0].onopen?.({});
      mockWsInstances[0].onclose?.({ code: 1000 });

      expect(ctrl.state).toBe('disconnected');
      vi.advanceTimersByTime(60_000);
      expect(mockWsInstances.length).toBe(1);
    });

    it('retries on abnormal closure (1006)', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      mockWsInstances[0].onopen?.({});
      mockWsInstances[0].onclose?.({ code: 1006 });

      expect(ctrl.state).toBe('reconnecting');
      vi.advanceTimersByTime(1000);
      expect(mockWsInstances.length).toBe(2);
    });
  });

  describe('lifecycle', () => {
    it('disconnect cancels pending reconnect and sets state to disconnected', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      mockWsInstances[0].onopen?.({});
      mockWsInstances[0].onclose?.({ code: 1006 });
      expect(ctrl.state).toBe('reconnecting');

      ctrl.disconnect();
      expect(ctrl.state).toBe('disconnected');

      vi.advanceTimersByTime(60_000);
      expect(mockWsInstances.length).toBe(1);
    });

    it('disconnect closes active WebSocket', () => {
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');
      mockWsInstances[0].onopen?.({});

      ctrl.disconnect();
      expect(mockWsInstances[0].close).toHaveBeenCalled();
      expect(ctrl.state).toBe('disconnected');
    });

    it('connect while already connected closes previous WebSocket', () => {
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok1');
      mockWsInstances[0].onopen?.({});

      ctrl.connect('ws://localhost/ws', 'tok2');
      expect(mockWsInstances[0].close).toHaveBeenCalled();
      expect(mockWsInstances.length).toBe(2);
    });

    it('does not connect without token', () => {
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', '');
      expect(mockWsInstances.length).toBe(0);
      expect(ctrl.state).toBe('disconnected');
    });

    it('does not connect without endpoint', () => {
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('', 'tok');
      expect(mockWsInstances.length).toBe(0);
      expect(ctrl.state).toBe('disconnected');
    });

    it('calls onStateChange callback on transitions', () => {
      const onStateChange = vi.fn();
      const ctrl = new ConnectionController(mockHost(), { onStateChange });
      ctrl.connect('ws://localhost/ws', 'tok');

      expect(onStateChange).toHaveBeenCalledWith('connecting');

      mockWsInstances[0].onopen?.({});
      expect(onStateChange).toHaveBeenCalledWith('connected');
    });
  });

  describe('attempt counter', () => {
    it('tracks reconnect attempt number', () => {
      vi.useFakeTimers();
      const ctrl = new ConnectionController(mockHost());
      ctrl.connect('ws://localhost/ws', 'tok');

      expect(ctrl.attempt).toBe(0);

      mockWsInstances[0].onopen?.({});
      mockWsInstances[0].onclose?.({ code: 1006 });
      expect(ctrl.attempt).toBe(1);

      vi.advanceTimersByTime(1000);
      mockWsInstances[1].onclose?.({ code: 1006 });
      expect(ctrl.attempt).toBe(2);

      vi.advanceTimersByTime(2000);
      mockWsInstances[2].onopen?.({});
      expect(ctrl.attempt).toBe(0);
    });
  });
});
