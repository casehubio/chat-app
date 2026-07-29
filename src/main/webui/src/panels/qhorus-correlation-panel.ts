import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { QhorusMessage } from '@casehubio/blocks-ui-channel-activity';
import { messageTypeCategory, isObligationCreating } from '@casehubio/blocks-ui-channel-activity';
import { emitPagesEvent } from '@casehubio/blocks-ui-core';
import { ChannelEventTopics } from '@casehubio/blocks-ui-channel-activity';
import type { CommitmentRecord } from '../types.js';
import '@casehubio/blocks-ui-core';

@customElement('qhorus-correlation-panel')
export class QhorusCorrelationPanelElement extends LitElement {
  @property({ type: Array }) messages: QhorusMessage[] = [];
  @property({ type: Object }) commitments: Map<string, CommitmentRecord> = new Map();
  @property({ type: String }) selectedMessageId?: string;

  static override readonly styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow-y: auto;
      font-family: var(--pages-font-family, 'Inter', system-ui, sans-serif);
    }
    .panel-title {
      font-size: var(--pages-font-size-sm, 13px);
      font-weight: var(--pages-font-weight-semibold, 600);
      padding: var(--pages-space-3, 12px) var(--pages-space-4, 16px);
      border-bottom: 1px solid var(--pages-neutral-4, #e5e5e5);
      color: var(--pages-neutral-12, #111);
    }
    .flow-container {
      padding: var(--pages-space-3, 12px) var(--pages-space-4, 16px);
    }
    .flow-node {
      display: flex;
      flex-direction: column;
      gap: var(--pages-space-1, 4px);
      padding: var(--pages-space-2, 8px) var(--pages-space-3, 12px);
      border: 1px solid var(--pages-neutral-4, #e5e5e5);
      border-radius: var(--pages-radius-sm, 4px);
      cursor: pointer;
      background: var(--pages-neutral-1, #fff);
    }
    .flow-node:hover { background: var(--pages-neutral-2, #f5f5f5); }
    .flow-node.selected { border-color: var(--pages-accent-9, #6366f1); border-width: 2px; }
    .flow-node-header {
      display: flex;
      align-items: center;
      gap: var(--pages-space-2, 8px);
      font-size: var(--pages-font-size-xs, 11px);
    }
    .actor-icon { font-size: var(--pages-font-size-sm, 13px); }
    .sender { font-weight: var(--pages-font-weight-semibold, 600); color: var(--pages-neutral-12, #111); }
    .speech-act-badge {
      font-size: 10px;
      font-weight: var(--pages-font-weight-medium, 500);
      padding: 1px 6px;
      border-radius: 9999px;
      text-transform: uppercase;
    }
    .badge-info { background: var(--pages-info-3, #dbeafe); color: var(--pages-info-11, #1e40af); }
    .badge-obligation { background: var(--pages-accent-3, #e0e7ff); color: var(--pages-accent-11, #3730a3); }
    .badge-success { background: var(--pages-success-3, #d1fae5); color: var(--pages-success-11, #065f46); }
    .badge-danger { background: var(--pages-danger-3, #fee2e2); color: var(--pages-danger-11, #991b1b); }
    .badge-warning { background: var(--pages-warning-3, #fef3c7); color: var(--pages-warning-11, #92400e); }
    .badge-transfer { background: var(--pages-info-3, #dbeafe); color: var(--pages-info-11, #1e40af); }
    .badge-telemetry { background: var(--pages-neutral-3, #e5e5e5); color: var(--pages-neutral-9, #737373); }

    .node-content {
      font-size: var(--pages-font-size-sm, 13px);
      color: var(--pages-neutral-11, #333);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .node-time {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-neutral-8, #888);
    }
    .delegation-indicator {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-info-9, #2563eb);
    }
    .flow-connector {
      display: flex;
      align-items: center;
      gap: var(--pages-space-2, 8px);
      padding: var(--pages-space-1, 4px) 0 var(--pages-space-1, 4px) 20px;
    }
    .connector-line {
      width: 2px;
      height: 16px;
      background: var(--pages-neutral-5, #d4d4d4);
    }
    .flow-duration {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-neutral-8, #888);
    }
    .empty {
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100%;
      color: var(--pages-neutral-8, #888);
      font-size: var(--pages-font-size-sm, 13px);
    }
  `;

  private _actorIcon(type: string): string {
    switch (type) {
      case 'HUMAN': return '\u{1F464}';
      case 'AGENT': return '\u{1F916}';
      case 'SYSTEM': return '⚙';
      default: return '?';
    }
  }

  private _formatDuration(ms: number): string {
    const seconds = Math.floor(ms / 1000);
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${minutes % 60}m`;
  }

  private _formatTime(iso: string): string {
    const date = new Date(iso);
    return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }

  private _getChain(): QhorusMessage[] {
    if (!this.selectedMessageId) return [];
    const selected = this.messages.find(m => m.id === this.selectedMessageId);
    if (!selected) return [];

    if (selected.correlationId) {
      return this.messages
        .filter(m => m.correlationId === selected.correlationId)
        .sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());
    }

    if (selected.inReplyTo) {
      const chain: QhorusMessage[] = [];
      const visited = new Set<string>();
      let current: QhorusMessage | undefined = selected;
      while (current && !visited.has(current.id)) {
        visited.add(current.id);
        chain.unshift(current);
        current = current.inReplyTo ? this.messages.find(m => m.id === current!.inReplyTo) : undefined;
      }
      const replies = this.messages.filter(m => m.inReplyTo && visited.has(m.inReplyTo) && !visited.has(m.id));
      chain.push(...replies);
      return chain.sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());
    }

    return [];
  }

  private _onNodeClick(msg: QhorusMessage) {
    emitPagesEvent(this, ChannelEventTopics.MESSAGE_SELECTED, { message: msg });
  }

  override render() {
    const chain = this._getChain();

    if (chain.length === 0) {
      return html`
        <div class="panel-title">Correlation</div>
        <div class="empty">Select a message to view its correlation chain</div>
      `;
    }

    return html`
      <div class="panel-title">Correlation</div>
      <div class="flow-container">
        ${chain.map((msg, i) => html`
          ${i > 0 ? this._renderConnector(chain[i - 1]!, msg) : nothing}
          ${this._renderNode(msg)}
        `)}
      </div>
    `;
  }

  private _renderConnector(prev: QhorusMessage, curr: QhorusMessage) {
    const duration = new Date(curr.createdAt).getTime() - new Date(prev.createdAt).getTime();
    return html`
      <div class="flow-connector">
        <div class="connector-line"></div>
        <span class="flow-duration">${this._formatDuration(duration)}</span>
      </div>
    `;
  }

  private _renderNode(msg: QhorusMessage) {
    const category = messageTypeCategory(msg.messageType);
    const isSelected = this.selectedMessageId === msg.id;
    const record = msg.correlationId ? this.commitments.get(msg.correlationId) : undefined;

    return html`
      <div class="flow-node ${isSelected ? 'selected' : ''}" @click=${() => this._onNodeClick(msg)}>
        <div class="flow-node-header">
          <span class="actor-icon">${this._actorIcon(msg.actorType)}</span>
          <span class="sender">${msg.sender}</span>
          <span class="speech-act-badge badge-${category}">${msg.messageType}</span>
          ${isObligationCreating(msg.messageType) && record ? html`
            <commitment-state-pill .state=${record.state} size="sm"></commitment-state-pill>
          ` : nothing}
          <span class="node-time">${this._formatTime(msg.createdAt)}</span>
        </div>
        <div class="node-content">${msg.content.split('\n')[0]}</div>
        ${msg.messageType === 'HANDOFF' && msg.target ? html`
          <div class="delegation-indicator">↳ Delegated to ${msg.target}</div>
        ` : nothing}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'qhorus-correlation-panel': QhorusCorrelationPanelElement;
  }
}
