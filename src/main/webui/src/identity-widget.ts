import { getIdentity } from './auth.js';

const SESSION_KEY = "pages-dev-auth-token";

export class ChatDemoIdentity extends HTMLElement {
  private _open = false;
  private _filter = '';
  private _onDocClick = (e: MouseEvent) => {
    if (!this.shadowRoot?.contains(e.target as Node)) this._close();
  };
  private _onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Escape') this._close();
  };

  connectedCallback() {
    this.attachShadow({ mode: 'open' });
    this._render();
  }

  disconnectedCallback() {
    document.removeEventListener('click', this._onDocClick, true);
    document.removeEventListener('keydown', this._onKeyDown);
  }

  configure(props: Record<string, unknown>) {
    if (typeof props.identities === 'string') this.setAttribute('identities', props.identities);
  }

  private _identities(): string[] {
    return (this.getAttribute('identities') ?? '').split(',').map(s => s.trim()).filter(Boolean);
  }

  private _close() {
    this._open = false;
    this._filter = '';
    document.removeEventListener('click', this._onDocClick, true);
    document.removeEventListener('keydown', this._onKeyDown);
    this._render();
  }

  private _openPicker() {
    this._open = true;
    this._filter = '';
    document.addEventListener('click', this._onDocClick, true);
    document.addEventListener('keydown', this._onKeyDown);
    this._render();
    const input = this.shadowRoot?.querySelector('#filter') as HTMLInputElement | null;
    input?.focus();
  }

  private _render() {
    if (!this.shadowRoot) return;
    const user = getIdentity() ?? 'Guest';
    const identities = this._identities();
    const filtered = this._filter
      ? identities.filter(n => n.toLowerCase().includes(this._filter.toLowerCase()))
      : identities;

    this.shadowRoot.innerHTML = `
      <style>
        :host { position: relative; display: block; }
        :host([compact]) { display: flex; align-items: center; justify-content: center; }
        .trigger {
          display: flex; align-items: center; gap: 8px;
          padding: 10px 16px;
          cursor: pointer; user-select: none;
          font-family: system-ui, sans-serif;
          border-bottom: 1px solid var(--pages-neutral-4, #e5e5e5);
        }
        :host([compact]) .trigger {
          padding: 4px;
          border-bottom: none;
          justify-content: center;
        }
        :host([compact]) .user-name,
        :host([compact]) .chevron { display: none; }
        .trigger:hover { background: var(--pages-neutral-3, #f0f0f0); }
        :host([compact]) .trigger:hover { background: transparent; }
        .avatar {
          width: 28px; height: 28px; border-radius: 50%;
          background: var(--pages-accent-9, #007bff);
          color: #fff; font-size: 13px; font-weight: 600;
          display: flex; align-items: center; justify-content: center;
          flex-shrink: 0; text-transform: uppercase;
        }
        .user-name {
          flex: 1; font-size: 14px; font-weight: 600;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
          color: inherit;
        }
        .chevron {
          font-size: 10px; color: var(--pages-neutral-8, #999);
          flex-shrink: 0; transition: transform 0.15s;
        }
        :host([open]) .chevron { transform: rotate(180deg); }
        .dropdown {
          position: absolute; top: 100%; left: 0; right: 0;
          background: var(--pages-neutral-1, white);
          border: 1px solid var(--pages-neutral-4, #ddd); border-top: none;
          border-radius: 0 0 6px 6px;
          box-shadow: var(--pages-shadow-2, 0 4px 12px rgba(0,0,0,0.15));
          z-index: 1000; overflow: hidden;
        }
        .dropdown input {
          width: 100%; padding: 8px 12px; border: none;
          border-bottom: 1px solid var(--pages-neutral-3, #eee);
          box-sizing: border-box; font-size: 13px; outline: none;
          font-family: system-ui, sans-serif;
          background: var(--pages-neutral-1, white);
          color: inherit;
        }
        .list { max-height: 200px; overflow-y: auto; }
        .item {
          padding: 7px 12px; cursor: pointer;
          font-family: system-ui, sans-serif; font-size: 13px;
          display: flex; align-items: center; gap: 8px;
        }
        .item:hover { background: var(--pages-neutral-3, #f0f0f0); }
        .item.current { font-weight: 600; color: var(--pages-accent-9, #007bff); }
        .item-dot {
          width: 6px; height: 6px; border-radius: 50%;
          background: var(--pages-neutral-6, #bbb); flex-shrink: 0;
        }
        .item.current .item-dot { background: var(--pages-accent-9, #007bff); }
        .empty { padding: 8px 12px; color: #999; font-style: italic; font-size: 13px; }
      </style>
      <div class="trigger" id="trigger">
        <span class="avatar">${user.charAt(0)}</span>
        <span class="user-name">${user}</span>
        <span class="chevron">▼</span>
      </div>
      ${this._open ? `
        <div class="dropdown">
          <input id="filter" type="text" placeholder="Type to filter…" value="${this._filter}" />
          <div class="list" id="list">
            ${filtered.length
              ? filtered.map(n =>
                  `<div class="item${n === user ? ' current' : ''}" data-name="${n}"><span class="item-dot"></span>${n}</div>`
                ).join('')
              : '<div class="empty">No matches</div>'}
          </div>
        </div>
      ` : ''}
    `;

    this.shadowRoot.querySelector('#trigger')?.addEventListener('click', (e) => {
      e.stopPropagation();
      if (this._open) this._close(); else this._openPicker();
    });

    if (this._open) {
      const input = this.shadowRoot.querySelector('#filter') as HTMLInputElement | null;
      input?.addEventListener('input', () => {
        this._filter = input.value;
        this._render();
        const restored = this.shadowRoot?.querySelector('#filter') as HTMLInputElement | null;
        restored?.focus();
        restored?.setSelectionRange(this._filter.length, this._filter.length);
      });

      this.shadowRoot.querySelectorAll('.item').forEach(el => {
        el.addEventListener('click', () => {
          const name = (el as HTMLElement).dataset.name;
          if (name) this._switch(name);
        });
      });
    }
  }

  private async _switch(name: string) {
    try {
      const resp = await fetch('/dev/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      });
      if (!resp.ok) return;
      const data = (await resp.json()) as { token: string };
      sessionStorage.setItem(SESSION_KEY, data.token);
      this._close();
      window.location.reload();
    } catch { /* network error */ }
  }
}

customElements.define('chat-demo-identity', ChatDemoIdentity);
