import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { ChatDemoAdapter } from './chat-demo-adapter.js';
import { SwipeController } from './swipe-controller.js';
import { ConnectionController } from './connection-controller.js';
import {
  ChannelEventTopics,
  ChannelFeedElement,
  ChannelNavElement,
  ChannelMemberPanelElement,
  ChannelInputElement,
} from '@casehubio/blocks-ui-channel-activity';
import type { SendMessagePayload, ReactPayload, CreateChannelPayload } from '@casehubio/blocks-ui-channel-activity';
import type { QhorusMessage, QhorusChannel, Reaction, ChannelMember, PresenceState } from '@casehubio/blocks-ui-channel-activity';
import { getToken, getIdentity, authenticatedFetch } from '../auth.js';
import { injectTheme, applyThemeMode, DEFAULT_THEME } from '@casehubio/pages-ui-tokens';
import '../identity-widget.js';

void ChannelFeedElement; void ChannelNavElement; void ChannelMemberPanelElement; void ChannelInputElement;

type LayoutMode = 'desktop' | 'tablet' | 'phone';

@customElement('qhorus-workbench')
export class QhorusWorkbenchElement extends LitElement {
  @property({ type: String }) endpoint = '';
  @property({ type: String }) restBase = '/api';
  @property({ type: String }) identities = '';

  @state() private _channels: QhorusChannel[] = [];
  @state() private _messages: QhorusMessage[] = [];
  @state() private _reactions: Reaction[] = [];
  @state() private _members: ChannelMember[] = [];
  @state() private _presence: PresenceState[] = [];
  @state() private _selectedChannelId = '';
  @state() private _replyTo?: { messageId: string; senderName: string };

  @state() private _navVisible = true;
  @state() private _memberVisible = true;
  @state() private _mode: LayoutMode = 'desktop';
  @state() private _tabletTab: 'channels' | 'members' = 'channels';
  @state() private _drawerOpen: 'channel' | 'member' | null = null;
  @state() private _darkMode = false;

  private _adapter = new ChatDemoAdapter();
  private _swipeController = new SwipeController(this, {
    drawerQuery: (side) => this.renderRoot?.querySelector(side === 'left' ? '.drawer.left' : '.drawer.right') as HTMLElement | null,
    backdropQuery: () => this.renderRoot?.querySelector('.backdrop') as HTMLElement | null,
    onOpen: (side) => { if (side === 'left') this._toggleNav(); else this._toggleMember(); },
    onClose: () => { this._drawerOpen = null; },
    isOpenQuery: (side) => side === 'left' ? this._drawerOpen === 'channel' : this._drawerOpen === 'member',
  });
  private _connection = new ConnectionController(this, {
    onMessage: (op) => this._adapter.applyOp(op as any),
    onStateChange: () => this.requestUpdate(),
  });
  private _mqTablet?: MediaQueryList;
  private _mqDesktop?: MediaQueryList;

  static override readonly styles = css`
    :host {
      display: flex;
      height: 100%;
      overflow: hidden;
      font-family: var(--pages-font-family, 'Inter', system-ui, sans-serif);
      background: var(--pages-neutral-1, #fff);
      color: var(--pages-neutral-12, #111);
    }
    /* --- panels --- */
    .nav-panel {
      width: 240px;
      flex-shrink: 0;
      border-right: 1px solid var(--pages-neutral-4, #e5e5e5);
      overflow-y: auto;
    }
    .main-panel {
      flex: 1;
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .member-panel {
      width: 220px;
      flex-shrink: 0;
      border-left: 1px solid var(--pages-neutral-4, #e5e5e5);
      overflow-y: auto;
    }
    /* --- child component flex rules --- */
    channel-feed {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
    }
    channel-input {
      flex-shrink: 0;
    }
    /* --- vertical dock strip --- */
    .dock-strip {
      display: flex;
      flex-direction: column;
      width: 48px;
      flex-shrink: 0;
      background: var(--pages-neutral-2, #f0f0f0);
      border-right: 1px solid var(--pages-neutral-4, #e0e0e0);
      padding: 8px 0;
      gap: 4px;
      align-items: center;
    }
    .dock-strip .spacer { flex: 1; }
    .dock-btn {
      width: 36px; height: 36px;
      display: flex; align-items: center; justify-content: center;
      background: none; border: none; border-radius: 6px;
      cursor: pointer; font-size: 18px;
      color: var(--pages-neutral-9, #888);
    }
    .dock-btn:hover { background: var(--pages-neutral-3, #e8e8e8); color: var(--pages-neutral-11, #333); }
    .dock-btn.active { color: var(--pages-accent-9, #007bff); background: var(--pages-neutral-3, #e8e8e8); }
    /* --- phone header bar --- */
    .phone-header {
      display: flex;
      align-items: center;
      height: 40px;
      padding: 0 4px;
      border-bottom: 1px solid var(--pages-neutral-4, #e5e5e5);
      flex-shrink: 0;
      gap: 2px;
    }
    .phone-header .spacer { flex: 1; }
    .phone-header .channel-name {
      font-size: 14px; font-weight: 600;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    /* --- tablet sidebar --- */
    .sidebar-with-tabs {
      display: flex;
      flex-direction: column;
      width: 280px;
      flex-shrink: 0;
      border-right: 1px solid var(--pages-neutral-4, #e5e5e5);
      overflow-y: auto;
    }
    .tab-switcher {
      display: flex; gap: 4px; padding: 8px;
      flex-shrink: 0;
    }
    .tab-switcher button {
      flex: 1; padding: 6px 12px;
      font-size: 12px; font-weight: 600;
      background: var(--pages-neutral-2, #f0f0f0);
      color: var(--pages-neutral-11, #555);
      border: 1px solid var(--pages-neutral-4, #ddd);
      border-radius: 16px; cursor: pointer;
    }
    .tab-switcher button:hover { background: var(--pages-neutral-3, #e8e8e8); }
    .tab-switcher button.active {
      background: var(--pages-accent-9, #007bff);
      color: #fff; border-color: var(--pages-accent-9, #007bff);
    }
    .sidebar-content { flex: 1; min-height: 0; overflow-y: auto; }
    /* --- phone drawers --- */
    .drawer {
      position: fixed; top: 0; bottom: 0;
      width: 280px;
      background: var(--pages-neutral-1, white);
      z-index: 50;
      overflow-y: auto;
      transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
      box-shadow: var(--pages-shadow-2, 2px 0 8px rgba(0,0,0,0.15));
    }
    .drawer.left { left: 0; transform: translateX(-100%); }
    .drawer.left.open { transform: translateX(0); }
    .drawer.right { right: 0; transform: translateX(100%); }
    .drawer.right.open { transform: translateX(0); }
    .backdrop {
      position: fixed; inset: 0;
      background: rgba(0,0,0,0.5);
      z-index: 40;
      opacity: 0; pointer-events: none;
      transition: opacity 0.3s;
    }
    .backdrop.visible { opacity: 1; pointer-events: auto; }
    @media (prefers-reduced-motion: reduce) {
      .drawer, .backdrop { transition-duration: 0ms !important; }
    }
    /* --- connection banner --- */
    .connection-banner {
      display: flex; align-items: center; gap: 8px;
      padding: 6px 12px;
      font-size: 12px; font-weight: 500;
      flex-shrink: 0;
    }
    .connection-banner.reconnecting {
      background: var(--pages-warning-3, #fef3c7);
      color: var(--pages-warning-11, #92400e);
    }
    .connection-banner.disconnected {
      background: var(--pages-danger-3, #fee2e2);
      color: var(--pages-danger-11, #991b1b);
    }
    .connection-spinner {
      width: 12px; height: 12px;
      border: 2px solid currentColor; border-top-color: transparent;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
  `;

  configure(props: Record<string, unknown>) {
    if (typeof props.endpoint === 'string') this.endpoint = props.endpoint;
    if (typeof props.restBase === 'string') this.restBase = props.restBase;
    if (typeof props.identities === 'string') this.identities = props.identities;
  }

  override connectedCallback() {
    super.connectedCallback();
    this._adapter.onChange(this._onDataChange);
    this.addEventListener('pages-event', this._onChatEvent as EventListener);
    const token = getToken();
    if (token && this.endpoint) {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      this._connection.connect(`${proto}//${location.host}${this.endpoint}`, token);
    }
    this._setupMediaQueries();
    this._initTheme();
  }

  private _initTheme() {
    this.updateComplete.then(() => {
      const root = this.renderRoot as ShadowRoot;
      injectTheme(DEFAULT_THEME, root.host as HTMLElement);
      this._applyTheme();
    });
  }

  private _applyTheme() {
    applyThemeMode(this, this._darkMode ? 'dark' : 'light');
  }

  private _toggleTheme() {
    this._darkMode = !this._darkMode;
    this._applyTheme();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._adapter.offChange(this._onDataChange);
    this.removeEventListener('pages-event', this._onChatEvent as EventListener);
    this._connection.disconnect();
    this._mqTablet?.removeEventListener('change', this._onMediaChange);
    this._mqDesktop?.removeEventListener('change', this._onMediaChange);
  }

  private _setupMediaQueries() {
    this._mqTablet = window.matchMedia('(min-width: 768px) and (max-width: 1279px)');
    this._mqDesktop = window.matchMedia('(min-width: 1280px)');
    this._mqTablet.addEventListener('change', this._onMediaChange);
    this._mqDesktop.addEventListener('change', this._onMediaChange);
    this._updateMode();
  }

  private _onMediaChange = () => this._updateMode();

  private _updateMode() {
    const prev = this._mode;
    if (this._mqDesktop?.matches) this._mode = 'desktop';
    else if (this._mqTablet?.matches) this._mode = 'tablet';
    else this._mode = 'phone';
    if (prev !== this._mode) this._drawerOpen = null;
  }

  private _toggleNav() {
    if (this._mode === 'phone') {
      this._drawerOpen = this._drawerOpen === 'channel' ? null : 'channel';
    } else {
      this._navVisible = !this._navVisible;
    }
  }

  private _toggleMember() {
    if (this._mode === 'phone') {
      this._drawerOpen = this._drawerOpen === 'member' ? null : 'member';
    } else {
      this._memberVisible = !this._memberVisible;
    }
  }

  private _closeDrawer() { this._drawerOpen = null; }

  private _onDataChange = (dataset: string) => {
    this._channels = this._adapter.channels;
    this._messages = this._adapter.messages;
    this._reactions = this._adapter.reactions;
    this._members = this._adapter.members;
    this._presence = this._adapter.presence;
  };

  private _onChatEvent = (e: CustomEvent) => {
    const { topic, payload } = e.detail;

    switch (topic) {
      case ChannelEventTopics.SELECT_CHANNEL:
        this._selectedChannelId = (payload as { channelId: string }).channelId;
        if (this._mode === 'phone') this._drawerOpen = null;
        break;
      case ChannelEventTopics.SEND_MESSAGE:
        this._sendMessage(payload as SendMessagePayload);
        break;
      case ChannelEventTopics.CREATE_CHANNEL:
        this._createChannel(payload as CreateChannelPayload);
        break;
      case ChannelEventTopics.DELETE_CHANNEL:
        this._deleteChannel((payload as { channelId: string }).channelId);
        break;
      case ChannelEventTopics.REACT:
        this._addReaction(payload as ReactPayload);
        break;
      case ChannelEventTopics.UNREACT:
        this._removeReaction(payload as ReactPayload);
        break;
      case ChannelEventTopics.MESSAGE_SELECTED:
        this._replyTo = {
          messageId: (payload as { message: QhorusMessage }).message.id,
          senderName: (payload as { message: QhorusMessage }).message.sender,
        };
        break;
    }
  };

  private async _sendMessage(payload: SendMessagePayload) {
    try {
      const url = payload.inReplyTo
        ? `${this.restBase}/channels/${payload.channelId}/messages/${payload.inReplyTo}/replies`
        : `${this.restBase}/channels/${payload.channelId}/messages`;
      await authenticatedFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: payload.content }),
      });
      this._replyTo = undefined;
    } catch (e) {
      console.error('Failed to send message:', e);
    }
  }

  private async _createChannel(payload: CreateChannelPayload) {
    try {
      await authenticatedFetch(`${this.restBase}/channels`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: payload.name }),
      });
    } catch (e) {
      console.error('Failed to create channel:', e);
    }
  }

  private async _deleteChannel(channelId: string) {
    try {
      await authenticatedFetch(`${this.restBase}/channels/${channelId}`, { method: 'DELETE' });
    } catch (e) {
      console.error('Failed to delete channel:', e);
    }
  }

  private async _addReaction(payload: ReactPayload) {
    try {
      const msg = this._messages.find(m => m.id === payload.messageId);
      if (!msg) return;
      await authenticatedFetch(
        `${this.restBase}/channels/${msg.channelId}/messages/${payload.messageId}/reactions`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ emoji: payload.emoji }) },
      );
    } catch (e) {
      console.error('Failed to add reaction:', e);
    }
  }

  private async _removeReaction(payload: ReactPayload) {
    try {
      const msg = this._messages.find(m => m.id === payload.messageId);
      if (!msg) return;
      await authenticatedFetch(
        `${this.restBase}/channels/${msg.channelId}/messages/${payload.messageId}/reactions/${payload.emoji}`,
        { method: 'DELETE' },
      );
    } catch (e) {
      console.error('Failed to remove reaction:', e);
    }
  }

  private _filteredMessages(): QhorusMessage[] {
    if (!this._selectedChannelId) return [];
    return this._messages.filter(m => m.channelId === this._selectedChannelId);
  }

  private _filteredReactions(): Reaction[] {
    if (!this._selectedChannelId) return [];
    const channelMessageIds = new Set(
      this._messages
        .filter(m => m.channelId === this._selectedChannelId)
        .map(m => m.id)
    );
    return this._reactions.filter(r => channelMessageIds.has(r.messageId));
  }

  private _filteredMembers(): ChannelMember[] {
    if (!this._selectedChannelId) return [];
    return this._members.filter(m => m.channelId === this._selectedChannelId);
  }

  private _renderIdentity() {
    return html`<chat-demo-identity identities=${this.identities}></chat-demo-identity>`;
  }

  private _renderNav() {
    return html`
      ${this._renderIdentity()}
      <channel-nav
        .channels=${this._channels}
        .selectedChannelId=${this._selectedChannelId}>
      </channel-nav>
    `;
  }

  private _renderMembers() {
    return html`<channel-member-panel
      .members=${this._filteredMembers()}
      .presence=${this._presence}>
    </channel-member-panel>`;
  }

  private _renderConnectionBanner() {
    const state = this._connection.state;
    if (state === 'connected' || state === 'connecting') return nothing;
    if (state === 'reconnecting') {
      return html`<div class="connection-banner reconnecting">
        <span class="connection-spinner"></span>
        Reconnecting (attempt ${this._connection.attempt})...
      </div>`;
    }
    return html`<div class="connection-banner disconnected">
      Connection lost
    </div>`;
  }

  private _renderChat() {
    return html`
      ${this._renderConnectionBanner()}
      <channel-feed
        .messages=${this._filteredMessages()}
        .reactions=${this._filteredReactions()}
        .channelName=${this._channels.find(c => c.id === this._selectedChannelId)?.name}>
      </channel-feed>
      <channel-input
        .channelId=${this._selectedChannelId}
        .replyTo=${this._replyTo}>
      </channel-input>
    `;
  }

  private _renderDockStrip() {
    return html`
      <div class="dock-strip">
        <button class="dock-btn ${this._navVisible ? 'active' : ''}"
          title="Channels" @click=${this._toggleNav}>💬</button>
        <button class="dock-btn ${this._memberVisible ? 'active' : ''}"
          title="Members" @click=${this._toggleMember}>👥</button>
        <span class="spacer"></span>
        <button class="dock-btn"
          title="${this._darkMode ? 'Light mode' : 'Dark mode'}"
          @click=${this._toggleTheme}>${this._darkMode ? '☀️' : '🌙'}</button>
      </div>
    `;
  }

  override render() {
    if (this._mode === 'phone') return this._renderPhone();
    if (this._mode === 'tablet') return this._renderTablet();
    return this._renderDesktop();
  }

  private _renderDesktop() {
    return html`
      ${this._renderDockStrip()}
      ${this._navVisible ? html`<div class="nav-panel">${this._renderNav()}</div>` : nothing}
      <div class="main-panel">
        ${this._renderChat()}
      </div>
      ${this._memberVisible ? html`<div class="member-panel">${this._renderMembers()}</div>` : nothing}
    `;
  }

  private _renderTablet() {
    return html`
      ${this._renderDockStrip()}
      <div class="sidebar-with-tabs">
        <div class="tab-switcher">
          <button class=${this._tabletTab === 'channels' ? 'active' : ''}
            @click=${() => { this._tabletTab = 'channels'; }}>Channels</button>
          <button class=${this._tabletTab === 'members' ? 'active' : ''}
            @click=${() => { this._tabletTab = 'members'; }}>Members</button>
        </div>
        <div class="sidebar-content">
          ${this._tabletTab === 'channels' ? this._renderNav() : this._renderMembers()}
        </div>
      </div>
      <div class="main-panel">
        ${this._renderChat()}
      </div>
    `;
  }

  private _renderPhone() {
    const channelName = this._channels.find(c => c.id === this._selectedChannelId)?.name;
    return html`
      <div class="drawer left ${this._drawerOpen === 'channel' ? 'open' : ''}">
        ${this._renderNav()}
      </div>
      <div class="drawer right ${this._drawerOpen === 'member' ? 'open' : ''}">
        ${this._renderMembers()}
      </div>
      <div class="backdrop ${this._drawerOpen ? 'visible' : ''}" @click=${this._closeDrawer}></div>
      <div class="main-panel">
        <div class="phone-header">
          <button class="dock-btn" title="Channels" @click=${this._toggleNav}>☰</button>
          ${channelName ? html`<span class="channel-name">#${channelName}</span>` : nothing}
          <span class="spacer"></span>
          <button class="dock-btn" title="Members" @click=${this._toggleMember}>👥</button>
        </div>
        ${this._renderChat()}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'qhorus-workbench': QhorusWorkbenchElement;
  }
}
