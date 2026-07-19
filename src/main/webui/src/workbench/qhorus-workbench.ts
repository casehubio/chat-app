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
  ChannelTopicBarElement,
} from '@casehubio/blocks-ui-channel-activity';
import type { SendMessagePayload, ReactPayload, CreateChannelPayload, ArtefactRef, SelectTopicPayload, ViewModePayload, TopicActionPayload, RenameTopicPayload, MergeTopicPayload, CreateTopicPayload } from '@casehubio/blocks-ui-channel-activity';
import type { QhorusMessage, QhorusChannel, QhorusTopic, Reaction, ChannelMember, PresenceState } from '@casehubio/blocks-ui-channel-activity';
import type { DockItem, LayoutState } from '@casehubio/pages-component';
import { createLocalLayoutStore } from '@casehubio/pages-runtime';
import { getToken, getIdentity, authenticatedFetch } from '../auth.js';
import { injectTheme, applyThemeMode, DEFAULT_THEME } from '@casehubio/pages-ui-tokens';
import type { CommitmentRecord } from '../types.js';
import { ARTEFACT_SELECTED } from '../types.js';
import '../identity-widget.js';
import { QhorusTaskPanelElement } from '../panels/qhorus-task-panel.js';
import { QhorusCorrelationPanelElement } from '../panels/qhorus-correlation-panel.js';
import { QhorusArtifactPanelElement } from '../panels/qhorus-artifact-panel.js';

void ChannelFeedElement; void ChannelNavElement; void ChannelMemberPanelElement; void ChannelInputElement; void ChannelTopicBarElement;
void QhorusTaskPanelElement; void QhorusCorrelationPanelElement; void QhorusArtifactPanelElement;

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

  @state() private _dockState: Record<string, boolean> = { nav: true, members: true, tasks: false, correlation: false, artifacts: false };
  @state() private _mode: LayoutMode = 'desktop';
  @state() private _tabletTab: string = 'nav';
  @state() private _drawerOpen: string | null = null;
  @state() private _darkMode = false;
  @state() private _commitments: Map<string, CommitmentRecord> = new Map();
  @state() private _selectedMessageId?: string;
  @state() private _selectedArtefactRef?: ArtefactRef;
  @state() private _topics: QhorusTopic[] = [];
  @state() private _selectedTopicId: string | null = null;
  @state() private _viewMode: 'flat' | 'threaded' | 'topics' = 'flat';

  private static readonly DOCK_ITEMS: DockItem[] = [
    { icon: '💬', label: 'Channels', panelId: 'nav', defaultOpen: true },
    { icon: '👥', label: 'Members', panelId: 'members', defaultOpen: true },
    { icon: '📋', label: 'Tasks', panelId: 'tasks', defaultOpen: false },
    { icon: '🔗', label: 'Correlation', panelId: 'correlation', defaultOpen: false },
    { icon: '📎', label: 'Artifacts', panelId: 'artifacts', defaultOpen: false },
  ];

  private _adapter = new ChatDemoAdapter();
  private _swipeController = new SwipeController(this, {
    drawerQuery: (side) => this.renderRoot?.querySelector(side === 'left' ? '.drawer.left' : '.drawer.right') as HTMLElement | null,
    backdropQuery: () => this.renderRoot?.querySelector('.backdrop') as HTMLElement | null,
    onOpen: (side) => { this._toggleDock(side === 'left' ? 'nav' : 'members'); },
    onClose: () => { this._drawerOpen = null; },
    isOpenQuery: (side) => side === 'left' ? this._drawerOpen === 'nav' : this._drawerOpen === 'members',
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
      min-height: 0;
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
      display: flex; flex-wrap: wrap; gap: var(--pages-space-1, 4px); padding: var(--pages-space-2, 8px);
      flex-shrink: 0;
    }
    .tab-switcher button {
      display: inline-flex; align-items: center; gap: var(--pages-space-1, 4px);
      padding: var(--pages-space-1, 4px) var(--pages-space-2, 8px);
      font-size: var(--pages-font-size-xs, 11px); font-weight: 600;
      background: var(--pages-neutral-1, #fafafa);
      color: var(--pages-neutral-11, #333);
      border: 1px solid var(--pages-neutral-5, #d4d4d4);
      border-radius: var(--pages-radius-full, 9999px);
      cursor: pointer; white-space: nowrap;
      transition: background 0.15s, border-color 0.15s;
    }
    .tab-switcher button:hover { background: var(--pages-neutral-3, #e5e5e5); }
    .tab-switcher button.active {
      background: var(--pages-accent-3, #e0e7ff);
      border-color: var(--pages-accent-7, #818cf8);
      color: var(--pages-accent-11, #3730a3);
    }
    .tab-count {
      background: var(--pages-neutral-4, #e5e5e5);
      border-radius: var(--pages-radius-full, 9999px);
      padding: 0 var(--pages-space-1, 4px);
      font-size: var(--pages-font-size-xs, 11px);
      min-width: 16px;
      text-align: center;
    }
    .tab-switcher button.active .tab-count {
      background: var(--pages-accent-5, #c7d2fe);
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
    this._setupMediaQueries();
    this._initTheme();
  }

  override firstUpdated() {
    const token = getToken();
    if (token && this.endpoint) {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      this._connection.connect(`${proto}//${location.host}${this.endpoint}`, token);
    }
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

  private _toggleDock(panelId: string) {
    if (this._mode === 'phone') {
      this._drawerOpen = this._drawerOpen === panelId ? null : panelId;
    } else if (this._mode === 'tablet') {
      if (this._tabletTab === panelId) {
        this._tabletTab = '';
      } else {
        this._tabletTab = panelId;
      }
    } else {
      this._dockState = { ...this._dockState, [panelId]: !this._dockState[panelId] };
    }
  }

  private _isDockOpen(panelId: string): boolean {
    return !!this._dockState[panelId];
  }

  private _closeDrawer() { this._drawerOpen = null; }

  private _onDataChange = (dataset: string) => {
    this._channels = this._adapter.channels;
    this._topics = this._adapter.topics;
    this._messages = this._adapter.messages;
    this._reactions = this._adapter.reactions;
    this._members = this._adapter.members;
    this._presence = this._adapter.presence;
    this._commitments = new Map(this._adapter.commitments);
    if (!this._selectedChannelId && this._channels.length > 0) {
      this._selectedChannelId = this._channels[0]!.id;
    }
  };

  private _onChatEvent = (e: CustomEvent) => {
    const { topic, payload } = e.detail;

    switch (topic) {
      case ChannelEventTopics.SELECT_CHANNEL:
        this._selectedChannelId = (payload as { channelId: string }).channelId;
        this._selectedTopicId = null;
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
      case ChannelEventTopics.MESSAGE_SELECTED: {
        const selected = (payload as { message: QhorusMessage }).message;
        this._replyTo = {
          messageId: selected.inReplyTo ?? selected.id,
          senderName: selected.sender,
        };
        this._selectedMessageId = selected.id;
        break;
      }
      case ARTEFACT_SELECTED: {
        this._selectedArtefactRef = (payload as { artefactRef: ArtefactRef }).artefactRef;
        if (!this._isDockOpen('artifacts') && this._mode === 'desktop') {
          this._dockState = { ...this._dockState, artifacts: true };
        }
        break;
      }
      case ChannelEventTopics.SELECT_TOPIC: {
        const tp = payload as SelectTopicPayload;
        this._selectedTopicId = tp.topicId;
        break;
      }
      case ChannelEventTopics.VIEW_MODE:
        this._viewMode = (payload as ViewModePayload).mode;
        break;
      case ChannelEventTopics.RESOLVE_TOPIC:
        this._updateTopicState((payload as TopicActionPayload).channelId, (payload as TopicActionPayload).topicId, 'RESOLVED');
        break;
      case ChannelEventTopics.REOPEN_TOPIC:
        this._updateTopicState((payload as TopicActionPayload).channelId, (payload as TopicActionPayload).topicId, 'ACTIVE');
        break;
      case ChannelEventTopics.ARCHIVE_TOPIC:
        this._updateTopicState((payload as TopicActionPayload).channelId, (payload as TopicActionPayload).topicId, 'ARCHIVED');
        break;
      case ChannelEventTopics.RENAME_TOPIC: {
        const rp = payload as RenameTopicPayload;
        this._renameTopic(rp.channelId, rp.topicId, rp.newName);
        break;
      }
      case ChannelEventTopics.MERGE_TOPIC: {
        const mp = payload as MergeTopicPayload;
        this._mergeTopic(mp.channelId, mp.sourceTopicId, mp.targetTopicId);
        break;
      }
      case ChannelEventTopics.CREATE_TOPIC: {
        const cp = payload as CreateTopicPayload;
        this._createTopic(cp.channelId, cp.name);
        break;
      }
    }
  };

  private async _sendMessage(payload: SendMessagePayload) {
    try {
      const url = payload.inReplyTo
        ? `${this.restBase}/channels/${payload.channelId}/messages/${payload.inReplyTo}/replies`
        : `${this.restBase}/channels/${payload.channelId}/messages`;
      const body: Record<string, unknown> = { text: payload.content };
      if (payload.speechAct) body.messageType = payload.speechAct;
      if (payload.artefactRefs?.length) body.artefactRefs = payload.artefactRefs;
      if (payload.topicId) body.topicId = payload.topicId;
      else if (payload.topic) body.topic = payload.topic;
      await authenticatedFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
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
    let msgs = this._messages.filter(m => m.channelId === this._selectedChannelId);
    if (this._selectedTopicId) {
      msgs = msgs.filter(m => m.topicId === this._selectedTopicId);
    }
    return msgs;
  }

  private _channelTopics(): QhorusTopic[] {
    if (!this._selectedChannelId) return [];
    return this._topics.filter(t => t.channelId === this._selectedChannelId && t.state !== 'MERGED');
  }

  private async _updateTopicState(channelId: string, topicId: string, state: string) {
    try {
      await authenticatedFetch(`${this.restBase}/channels/${channelId}/topics/${topicId}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ state }),
      });
    } catch (e) { console.error('Failed to update topic state:', e); }
  }

  private async _renameTopic(channelId: string, topicId: string, newName: string) {
    try {
      await authenticatedFetch(`${this.restBase}/channels/${channelId}/topics/${topicId}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: newName }),
      });
    } catch (e) { console.error('Failed to rename topic:', e); }
  }

  private async _mergeTopic(channelId: string, sourceTopicId: string, targetTopicId: string) {
    try {
      await authenticatedFetch(`${this.restBase}/channels/${channelId}/topics/${sourceTopicId}/merge`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ targetTopicId }),
      });
    } catch (e) { console.error('Failed to merge topic:', e); }
  }

  private async _createTopic(channelId: string, name: string) {
    try {
      await authenticatedFetch(`${this.restBase}/channels/${channelId}/topics`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }),
      });
    } catch (e) { console.error('Failed to create topic:', e); }
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
    const channelTopics = this._channelTopics();
    const showTopics = channelTopics.length > 1;
    const selectedTopic = channelTopics.find(t => t.id === this._selectedTopicId);
    const defaultTopic = channelTopics.find(t => t.name === 'General');
    const currentTopic = selectedTopic ?? defaultTopic;
    return html`
      ${this._renderConnectionBanner()}
      ${showTopics ? html`
        <channel-topic-bar
          .topics=${channelTopics}
          .selectedTopicId=${this._selectedTopicId}
          .viewMode=${this._viewMode}>
        </channel-topic-bar>
      ` : nothing}
      <channel-feed
        .messages=${this._filteredMessages()}
        .reactions=${this._filteredReactions()}
        .eventStyling=${false}
        .viewMode=${this._viewMode}
        .topics=${channelTopics}
        .selectedMessageId=${this._selectedMessageId}
        .channelName=${this._channels.find(c => c.id === this._selectedChannelId)?.name}>
      </channel-feed>
      <channel-input
        .channelId=${this._selectedChannelId}
        .replyTo=${this._replyTo}
        .showTopicSelector=${showTopics}
        .topic=${currentTopic?.name ?? 'General'}
        .topicId=${currentTopic?.id ?? ''}
        .topics=${channelTopics}>
      </channel-input>
    `;
  }

  private _renderDockStrip() {
    return html`
      <div class="dock-strip">
        ${QhorusWorkbenchElement.DOCK_ITEMS.map(item => html`
          <button class="dock-btn ${this._isDockOpen(item.panelId) ? 'active' : ''}"
            title=${item.label} @click=${() => this._toggleDock(item.panelId)}>${item.icon}</button>
        `)}
        <span class="spacer"></span>
        <button class="dock-btn"
          title="${this._darkMode ? 'Light mode' : 'Dark mode'}"
          @click=${this._toggleTheme}>${this._darkMode ? '☀️' : '🌙'}</button>
      </div>
    `;
  }

  private _renderPanel(panelId: string) {
    switch (panelId) {
      case 'nav': return this._renderNav();
      case 'members': return this._renderMembers();
      case 'tasks': return html`<qhorus-task-panel
        .messages=${this._filteredMessages()}
        .commitments=${this._commitments}
        .selectedMessageId=${this._selectedMessageId}></qhorus-task-panel>`;
      case 'correlation': return html`<qhorus-correlation-panel
        .messages=${this._filteredMessages()}
        .commitments=${this._commitments}
        .selectedMessageId=${this._selectedMessageId}></qhorus-correlation-panel>`;
      case 'artifacts': return html`<qhorus-artifact-panel
        .selectedArtefactRef=${this._selectedArtefactRef}></qhorus-artifact-panel>`;
      default: return nothing;
    }
  }

  override render() {
    if (this._mode === 'phone') return this._renderPhone();
    if (this._mode === 'tablet') return this._renderTablet();
    return this._renderDesktop();
  }

  private _renderDesktop() {
    const leftPanels = ['nav', 'tasks'].filter(p => this._isDockOpen(p));
    const rightPanels = ['members', 'correlation', 'artifacts'].filter(p => this._isDockOpen(p));
    return html`
      ${this._renderDockStrip()}
      ${leftPanels.map(p => html`<div class="nav-panel">${this._renderPanel(p)}</div>`)}
      <div class="main-panel">
        ${this._renderChat()}
      </div>
      ${rightPanels.map(p => html`<div class="member-panel">${this._renderPanel(p)}</div>`)}
    `;
  }

  private _tabletCount(panelId: string): number {
    switch (panelId) {
      case 'nav': return this._channels.length;
      case 'members': return this._filteredMembers().length;
      default: return 0;
    }
  }

  private _renderTablet() {
    const tabItems: { id: string; label: string }[] = [
      { id: 'nav', label: '💬 Chans' },
      { id: 'members', label: '👥 Mbrs' },
      { id: 'tasks', label: '📋 Tasks' },
      { id: 'correlation', label: '🔗 Corr' },
      { id: 'artifacts', label: '📎 Arts' },
    ];
    return html`
      ${this._renderDockStrip()}
      <div class="sidebar-with-tabs">
        <div class="tab-switcher">
          ${tabItems.map(t => { const count = this._tabletCount(t.id); return html`
            <button class=${this._tabletTab === t.id ? 'active' : ''}
              @click=${() => { this._tabletTab = t.id; }}>${t.label}${count > 0 ? html`<span class="tab-count">${count}</span>` : nothing}</button>
          `; })}
        </div>
        <div class="sidebar-content">
          ${this._tabletTab ? this._renderPanel(this._tabletTab) : nothing}
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
      <div class="drawer left ${this._drawerOpen === 'nav' ? 'open' : ''}">
        ${this._renderNav()}
      </div>
      <div class="drawer right ${this._drawerOpen && this._drawerOpen !== 'nav' ? 'open' : ''}">
        ${this._drawerOpen && this._drawerOpen !== 'nav' ? this._renderPanel(this._drawerOpen) : nothing}
      </div>
      <div class="backdrop ${this._drawerOpen ? 'visible' : ''}" @click=${this._closeDrawer}></div>
      <div class="main-panel">
        <div class="phone-header">
          <button class="dock-btn" title="Channels" @click=${() => this._toggleDock('nav')}>☰</button>
          ${channelName ? html`<span class="channel-name">#${channelName}</span>` : nothing}
          <span class="spacer"></span>
          <button class="dock-btn" title="Members" @click=${() => this._toggleDock('members')}>👥</button>
          <button class="dock-btn" title="More" @click=${() => this._toggleDock('tasks')}>⋯</button>
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
