import type { ReactiveController, ReactiveControllerHost } from 'lit';

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

export interface ConnectionOptions {
  onMessage?: (op: unknown) => void;
  onStateChange?: (state: ConnectionState) => void;
  initialDelayMs?: number;
  maxDelayMs?: number;
}

const INITIAL_DELAY = 1000;
const MAX_DELAY = 30_000;
const NON_RETRYABLE_CODES = new Set([1000, 1001, 4401, 4403]);

export class ConnectionController implements ReactiveController {
  private _host: ReactiveControllerHost;
  private _options: ConnectionOptions;
  private _ws?: WebSocket;
  private _timeout?: ReturnType<typeof setTimeout>;
  private _endpoint = '';
  private _token = '';
  private _delay: number;
  private _maxDelay: number;
  private _attempt = 0;
  private _state: ConnectionState = 'disconnected';

  get state(): ConnectionState { return this._state; }
  get attempt(): number { return this._attempt; }

  constructor(host: ReactiveControllerHost, options: ConnectionOptions = {}) {
    this._host = host;
    this._options = options;
    this._delay = options.initialDelayMs ?? INITIAL_DELAY;
    this._maxDelay = options.maxDelayMs ?? MAX_DELAY;
    host.addController(this);
  }

  hostConnected() {}
  hostDisconnected() { this.disconnect(); }

  connect(endpoint: string, token: string) {
    if (!endpoint || !token) return;

    this.disconnect();
    this._endpoint = endpoint;
    this._token = token;
    this._attempt = 0;
    this._delay = this._options.initialDelayMs ?? INITIAL_DELAY;
    this._open();
  }

  disconnect() {
    if (this._timeout) {
      clearTimeout(this._timeout);
      this._timeout = undefined;
    }
    if (this._ws) {
      this._ws.onopen = null;
      this._ws.onmessage = null;
      this._ws.onclose = null;
      this._ws.onerror = null;
      this._ws.close();
      this._ws = undefined;
    }
    this._setState('disconnected');
  }

  private _open() {
    const url = `${this._endpoint}?token=${this._token}`;
    this._ws = new WebSocket(url);
    this._setState(this._attempt === 0 ? 'connecting' : 'reconnecting');

    this._ws.onopen = () => {
      this._attempt = 0;
      this._delay = this._options.initialDelayMs ?? INITIAL_DELAY;
      this._setState('connected');
    };

    this._ws.onmessage = (e) => {
      try {
        const parsed = JSON.parse(e.data);
        const ops = Array.isArray(parsed) ? parsed : [parsed];
        for (const op of ops) this._options.onMessage?.(op);
      } catch { /* malformed message */ }
    };

    this._ws.onclose = (e) => {
      this._ws = undefined;

      if (NON_RETRYABLE_CODES.has(e.code)) {
        this._setState('disconnected');
        return;
      }

      this._attempt++;
      this._setState('reconnecting');
      this._scheduleReconnect();
    };
  }

  private _scheduleReconnect() {
    const delay = Math.min(this._delay, this._maxDelay);
    this._timeout = setTimeout(() => {
      this._timeout = undefined;
      this._open();
    }, delay);
    this._delay = Math.min(this._delay * 2, this._maxDelay);
  }

  private _setState(state: ConnectionState) {
    if (this._state === state) return;
    this._state = state;
    this._options.onStateChange?.(state);
    this._host.requestUpdate();
  }
}
