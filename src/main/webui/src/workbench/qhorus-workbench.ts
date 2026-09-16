import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import { SwipeController } from './swipe-controller.js';
import { createEventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import type { EventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import { MQ_TABLET, MQ_DESKTOP } from './responsive.js';
import {
  ChannelEventTopics,
  PushController, ALL_TOPICS,
  ChannelStateController,
  MessagingController,
  MembershipController,
  ReactionController,
  CommitmentController,
  ChannelFeedElement,
  ChannelNavElement,
  ChannelMemberPanelElement,
  ChannelInputElement,
  ChannelTopicBarElement,
  ChannelTaskPanelElement,
  ChannelCorrelationPanelElement,
  renderMarkdown,
} from '@casehubio/blocks-ui-channel-activity';
import type { SendMessagePayload, ArtefactRef, CreateSpacePayload, RenameSpacePayload, DeleteSpacePayload, MoveChannelToSpacePayload } from '@casehubio/blocks-ui-channel-activity';
import type { LayoutState } from '@casehubio/pages-component';
import { createLocalLayoutStore } from '@casehubio/pages-runtime/layout-store.js';
import { getToken, getIdentity, authenticatedFetch } from '../auth.js';
import { applyTheme } from '@casehubio/pages-ui-tokens';
import { stateCategoryStyles } from '@casehubio/pages-ui-components';
import { ARTEFACT_SELECTED } from '../types.js';
import { decorateCommitmentRanges } from '@casehubio/blocks-ui-commitment-viz/dist/range-decorator.js';
import type { RangeDecoration } from '@casehubio/blocks-ui-commitment-viz/dist/types.js';
import '../identity-widget.js';
import { QhorusArtifactPanelElement } from '../panels/qhorus-artifact-panel.js';

void ChannelFeedElement; void ChannelNavElement; void ChannelMemberPanelElement; void ChannelInputElement; void ChannelTopicBarElement;
void ChannelTaskPanelElement; void ChannelCorrelationPanelElement; void QhorusArtifactPanelElement;

type LayoutMode = 'desktop' | 'tablet' | 'phone';

const ICON_CHAT = html`<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`;
const ICON_PEOPLE = html`<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>`;
const ICON_CHECKLIST = html`<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>`;
const ICON_LINK = html`<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>`;
const ICON_PAPERCLIP = html`<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>`;
const ICON_GEAR = html`<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>`;

@customElement('qhorus-workbench')
export class QhorusWorkbenchElement extends LitElement {
  @property({ type: String }) endpoint = '';
  @property({ type: String }) restBase = '/api';
  @property({ type: String }) identities = '';

  private _push = new PushController(this);
  private _channels = new ChannelStateController(this, this._push);
  private _messaging = new MessagingController(this, this._channels, {
    restBase: '/api',
    messageRestBase: '/api/chat',
    fetch: authenticatedFetch,
  });
  private _members = new MembershipController(this, this._push, this._channels);
  private _reactions = new ReactionController(this, this._push, this._channels, {
    restBase: '/api',
    fetch: authenticatedFetch,
  });
  private _commitments = new CommitmentController(this, this._push, this._channels);

  private _layoutStore = createLocalLayoutStore('qhorus-workbench:');
  @state() private _layoutState: LayoutState = {
    splits: {},
    docks: Object.fromEntries(QhorusWorkbenchElement.DOCK_ITEMS.map(d => [d.panelId, d.defaultOpen ?? false])),
    panels: {},
  };
  @state() private _mode: LayoutMode = 'desktop';
  @state() private _tabletTab: string = 'nav';
  @state() private _drawerOpen: string | null = null;
  @state() private _selectedArtefactRef?: ArtefactRef;

  private static readonly DOCK_ITEMS = [
    { icon: ICON_CHAT, label: 'Channels', panelId: 'nav', defaultOpen: true },
    { icon: ICON_PEOPLE, label: 'Members', panelId: 'members', defaultOpen: true },
    { icon: ICON_CHECKLIST, label: 'Tasks', panelId: 'tasks', defaultOpen: false },
    { icon: ICON_LINK, label: 'Correlation', panelId: 'correlation', defaultOpen: false },
    { icon: ICON_PAPERCLIP, label: 'Artifacts', panelId: 'artifacts', defaultOpen: false },
  ];

  private _swipeController = new SwipeController(this, {
    drawerQuery: (side) => this.renderRoot?.querySelector(side === 'left' ? '.drawer.left' : '.drawer.right') as HTMLElement | null,
    backdropQuery: () => this.renderRoot?.querySelector('.backdrop') as HTMLElement | null,
    onOpen: (side) => { this._toggleDock(side === 'left' ? 'nav' : 'members'); },
    onClose: () => { this._drawerOpen = null; },
    isOpenQuery: (side) => side === 'left' ? this._drawerOpen === 'nav' : this._drawerOpen === 'members',
  });
  private _eventConn?: EventConnection;
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
      width: 40px;
      flex-shrink: 0;
      background: var(--pages-neutral-2, #f0f0f0);
      border-right: 1px solid var(--pages-neutral-4, #e0e0e0);
      padding: 6px 0;
      gap: 2px;
      align-items: center;
    }
    .dock-strip .spacer { flex: 1; }
    .dock-btn {
      width: 32px; height: 32px;
      display: flex; align-items: center; justify-content: center;
      background: none; border: none; border-radius: 6px;
      cursor: pointer;
      color: var(--pages-neutral-9, #888);
    }
    .dock-btn svg { width: 20px; height: 20px; }
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
    this.addEventListener('pages-event', this._onChatEvent as EventListener);
    this._setupMediaQueries();
    this._loadLayout();
  }

  private async _loadLayout() {
    const saved = await this._layoutStore.load('workbench');
    if (saved) this._layoutState = saved;
  }

  override firstUpdated() {
    const token = getToken();
    const identity = getIdentity();
    if (identity) this._channels.setCurrentUser(identity);
    if (token && this.endpoint) {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const url = `${proto}//${location.host}/ws/push?token=${token}`;
      const eventTarget = new EventTarget();

      this._eventConn = createEventConnection(url, {
        config: { eventTarget },
        onStatusChange: (status) => { this._push.setConnectionStatus(status as any); },
      });

      this._eventConn.listen(ALL_TOPICS);

      eventTarget.addEventListener('pages-event', (e: Event) => {
        const detail = (e as CustomEvent).detail;
        if (detail?.payload) {
          this._push.applyOp(detail.payload as any);
        }
      });
    }
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener('pages-event', this._onChatEvent as EventListener);
    this._eventConn?.close();
    this._mqTablet?.removeEventListener('change', this._onMediaChange);
    this._mqDesktop?.removeEventListener('change', this._onMediaChange);
  }

  private _setupMediaQueries() {
    this._mqTablet = window.matchMedia(MQ_TABLET);
    this._mqDesktop = window.matchMedia(MQ_DESKTOP);
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
      this._layoutState = {
        ...this._layoutState,
        docks: { ...this._layoutState.docks, [panelId]: !this._layoutState.docks[panelId] },
      };
      this._layoutStore.save('workbench', this._layoutState);
    }
  }

  private _isDockOpen(panelId: string): boolean {
    return !!this._layoutState.docks[panelId];
  }

  private _closeDrawer() { this._drawerOpen = null; }

  private _onChatEvent = (e: CustomEvent) => {
    const { topic, payload } = e.detail;

    // Message posting uses /api/chat (ChatResource), not /api/channels (qhorus)
    if (topic === ChannelEventTopics.SEND_MESSAGE) {
      this._sendMessage(payload as SendMessagePayload);
    } else {
      this._channels.handleEvent(topic, payload);
      this._messaging.handleEvent(topic, payload);
      this._reactions.handleEvent(topic, payload);
      this._commitments.handleEvent(topic, payload);
    }

    // App-specific: artifact panel, drawer close on channel select
    if (topic === ARTEFACT_SELECTED) {
      this._selectedArtefactRef = (payload as { artefactRef: ArtefactRef }).artefactRef;
      if (!this._isDockOpen('artifacts') && this._mode === 'desktop') {
        this._layoutState = {
          ...this._layoutState,
          docks: { ...this._layoutState.docks, artifacts: true },
        };
        this._layoutStore.save('workbench', this._layoutState);
      }
    }
    if (topic === ChannelEventTopics.SELECT_CHANNEL) {
      if (this._mode === 'phone') this._drawerOpen = null;
      const { channelId } = payload as { channelId: string };
      const latestId = this._channels.latestMessageId(channelId);
      if (latestId) {
        authenticatedFetch(`/api/chat/${channelId}/read`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ lastReadMessageId: latestId }),
        }).catch(() => {});
      }
    }
    if (topic === ChannelEventTopics.CREATE_SPACE) {
      const { name } = payload as CreateSpacePayload;
      authenticatedFetch('/api/spaces', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      }).catch(e => console.error('Create space failed:', e));
    }
    if (topic === ChannelEventTopics.RENAME_SPACE) {
      const { spaceId, newName } = payload as RenameSpacePayload;
      authenticatedFetch(`/api/spaces/${spaceId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName }),
      }).catch(e => console.error('Rename space failed:', e));
    }
    if (topic === ChannelEventTopics.DELETE_SPACE) {
      const { spaceId } = payload as DeleteSpacePayload;
      authenticatedFetch(`/api/spaces/${spaceId}`, {
        method: 'DELETE',
      }).catch(e => console.error('Delete space failed:', e));
    }
    if (topic === ChannelEventTopics.MOVE_CHANNEL_TO_SPACE) {
      const { channelId, spaceId, position } = payload as MoveChannelToSpacePayload;
      const spaceNode = this._channels.channelTree.spaces.find(s => s.space.id === spaceId);
      this._channels.applyMoveChannel(channelId, spaceId, spaceNode?.space.name ?? null, position);
      authenticatedFetch(`/api/chat/channels/${channelId}/space`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ spaceId, position }),
      }).catch(e => console.error('Move channel failed:', e));
    }
  };

  private async _sendMessage(payload: SendMessagePayload) {
    try {
      const url = payload.inReplyTo
        ? `/api/chat/${payload.channelId}/messages/${payload.inReplyTo}/replies`
        : `/api/chat/${payload.channelId}/messages`;
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
      this._messaging.replyTo = undefined;
      this.requestUpdate();
    } catch (e) {
      console.error('Failed to send message:', e);
    }
  }

  private _commitmentDecorations: RangeDecoration[] = [];

  override willUpdate() {
    if (!this._channels.selectedChannelId && this._channels.channels.length > 0) {
      this._channels.selectedChannelId = this._channels.channels[0]!.id;
    }
    this._commitmentDecorations = decorateCommitmentRanges(
      this._channels.filteredMessages(), this._commitments.commitments);
  }

  private _computeHighlights(): Record<string, string> {
    const highlights: Record<string, string> = {};
    for (const dec of this._commitmentDecorations) {
      const bg = stateCategoryStyles(dec.category).background;
      for (const id of dec.messageIds) {
        highlights[id] = bg;
      }
    }
    return highlights;
  }

  private _renderCommitmentBar = (msg: { id: string; content?: string }) => {
    const decoration = this._commitmentDecorations.find(d => d.startMessageId === msg.id);
    if (!decoration) return undefined;
    const record = this._commitments.commitments.get(decoration.correlationId);
    if (!record) return undefined;
    return html`${msg.content ? unsafeHTML(renderMarkdown(msg.content)) : nothing}<commitment-range-bar
      .state=${record.state}
      .createdAt=${record.createdAt}
      .resolvedAt=${(record as any).resolvedAt}
      .acknowledgedAt=${(record as any).acknowledgedAt}
      .deadline=${(record as any).deadline}
      mode="compact">
    </commitment-range-bar>`;
  };

  private _renderIdentity() {
    return html`<chat-demo-identity identities=${this.identities}></chat-demo-identity>`;
  }

  private _renderNav() {
    return html`
      ${this._renderIdentity()}
      <blocks-channel-nav
        .channelTree=${this._channels.channelTree}
        .selectedChannelId=${this._channels.selectedChannelId}>
      </blocks-channel-nav>
    `;
  }

  private _renderMembers() {
    return html`<blocks-channel-member-panel
      .members=${this._members.filteredMembers()}
      .presence=${this._members.presence}>
    </blocks-channel-member-panel>`;
  }

  private _renderConnectionBanner() {
    if (this._push.connectionStatus === 'connected') return nothing;
    if (this._push.connectionStatus === 'reconnecting') {
      return html`<div class="connection-banner reconnecting">
        <span class="connection-spinner"></span>
        Reconnecting...
      </div>`;
    }
    return html`<div class="connection-banner disconnected">
      Connection lost
    </div>`;
  }

  private _renderChat() {
    const channelTopics = this._channels.channelTopics();
    const showTopics = channelTopics.length > 1;
    const selectedTopic = channelTopics.find(t => t.id === this._channels.selectedTopicId);
    const defaultTopic = channelTopics.find(t => t.name === 'General');
    const currentTopic = selectedTopic ?? defaultTopic;
    return html`
      ${this._renderConnectionBanner()}
      ${showTopics ? html`
        <blocks-channel-topic-bar
          .topics=${channelTopics}
          .selectedTopicId=${this._channels.selectedTopicId}
          .viewMode=${this._channels.viewMode}>
        </blocks-channel-topic-bar>
      ` : nothing}
      <blocks-channel-feed
        .messages=${this._channels.filteredMessages()}
        .reactions=${this._reactions.filteredReactions()}
        .eventStyling=${false}
        .viewMode=${this._channels.viewMode}
        .topics=${channelTopics}
        .selectedMessageId=${this._commitments.selectedMessageId}
        .channelName=${this._channels.channels.find(c => c.id === this._channels.selectedChannelId)?.name}
        .renderContent=${this._renderCommitmentBar}
        .messageHighlights=${this._computeHighlights()}>
      </blocks-channel-feed>
      <blocks-channel-input
        .channelId=${this._channels.selectedChannelId}
        .replyTo=${this._messaging.replyTo}
        .showTopicSelector=${showTopics}
        .topic=${currentTopic?.name ?? 'General'}
        .topicId=${currentTopic?.id ?? ''}
        .topics=${channelTopics}>
      </blocks-channel-input>
    `;
  }

  private _renderDockStrip() {
    return html`
      <div class="dock-strip">
        ${QhorusWorkbenchElement.DOCK_ITEMS.map(item => html`
          <button class="dock-btn ${this._isDockOpen(item.panelId) ? 'active' : ''}"
            title=${item.label} aria-label=${item.label}
            @click=${() => this._toggleDock(item.panelId)}>${item.icon}</button>
        `)}
        <button class="dock-btn" title="Settings" aria-label="Settings"
          @click=${() => this._toggleDock('settings')}>${ICON_GEAR}</button>
        <span class="spacer"></span>
        <pages-theme-picker compact
          @theme-change=${(e: CustomEvent) => {
            const { family, mode } = e.detail;
            applyTheme(family ?? 'casehub', mode ?? 'light');
          }}>
        </pages-theme-picker>
      </div>
    `;
  }

  private _renderPanel(panelId: string) {
    switch (panelId) {
      case 'nav': return this._renderNav();
      case 'members': return this._renderMembers();
      case 'tasks': return html`<blocks-channel-task-panel
        .messages=${this._channels.filteredMessages()}
        .commitments=${this._commitments.commitments}
        .selectedMessageId=${this._commitments.selectedMessageId}></blocks-channel-task-panel>`;
      case 'correlation': return html`<blocks-channel-correlation-panel
        .messages=${this._channels.filteredMessages()}
        .commitments=${this._commitments.commitments}
        .selectedMessageId=${this._commitments.selectedMessageId}></blocks-channel-correlation-panel>`;
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
      case 'nav': return this._channels.channels.length;
      case 'members': return this._members.filteredMembers().length;
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
    const channelName = this._channels.channels.find(c => c.id === this._channels.selectedChannelId)?.name;
    return html`
      <div class="drawer left ${this._drawerOpen === 'nav' ? 'open' : ''}">
        ${this._renderNav()}
      </div>
      <div class="drawer right ${this._drawerOpen && this._drawerOpen !== 'nav' ? 'open' : ''}">
        ${this._drawerOpen && this._drawerOpen !== 'nav' ? this._renderPanel(this._drawerOpen) : nothing}
      </div>
      <div class="backdrop ${this._drawerOpen ? 'visible' : ''}" @click=${this._closeDrawer}></div>
      <div class="main-panel" ?inert=${!!this._drawerOpen}>
        <div class="phone-header">
          <button class="dock-btn" title="Channels" aria-label="Channels" @click=${() => this._toggleDock('nav')}>☰</button>
          ${channelName ? html`<span class="channel-name">#${channelName}</span>` : nothing}
          <span class="spacer"></span>
          <button class="dock-btn" title="Members" aria-label="Members" @click=${() => this._toggleDock('members')}>👥</button>
          <button class="dock-btn" title="More" aria-label="More" @click=${() => this._toggleDock('tasks')}>⋯</button>
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
