import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { ArtefactRef } from '@casehubio/mosaic-channel-activity';

@customElement('qhorus-artifact-panel')
export class QhorusArtifactPanelElement extends LitElement {
  @property({ type: Object }) selectedArtefactRef?: ArtefactRef;
  @property({ attribute: false }) resolveArtifact?: (ref: ArtefactRef) => Promise<{ content: string; language?: string }>;

  @state() private _history: ArtefactRef[] = [];
  @state() private _historyIndex = -1;
  @state() private _content?: string;
  @state() private _language?: string;

  static override readonly styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      font-family: var(--pages-font-family, 'Inter', system-ui, sans-serif);
    }
    .panel-title {
      font-size: var(--pages-font-size-sm, 13px);
      font-weight: var(--pages-font-weight-semibold, 600);
      padding: var(--pages-space-3, 12px) var(--pages-space-4, 16px);
      border-bottom: 1px solid var(--pages-neutral-4, #e5e5e5);
      color: var(--pages-neutral-12, #111);
    }
    .header-bar {
      display: flex;
      align-items: center;
      gap: var(--pages-space-2, 8px);
      padding: var(--pages-space-2, 8px) var(--pages-space-4, 16px);
      border-bottom: 1px solid var(--pages-neutral-4, #e5e5e5);
      flex-shrink: 0;
    }
    .artifact-label {
      font-size: var(--pages-font-size-sm, 13px);
      font-weight: var(--pages-font-weight-semibold, 600);
      color: var(--pages-neutral-12, #111);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      flex: 1;
    }
    .type-badge {
      font-size: 10px;
      font-weight: var(--pages-font-weight-medium, 500);
      padding: 1px 6px;
      border-radius: var(--pages-radius-sm, 4px);
      background: var(--pages-neutral-3, #e5e5e5);
      color: var(--pages-neutral-9, #737373);
      text-transform: uppercase;
      flex-shrink: 0;
    }
    .artifact-uri {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-neutral-8, #888);
      padding: 0 var(--pages-space-4, 16px);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .nav-buttons {
      display: flex;
      gap: var(--pages-space-1, 4px);
      flex-shrink: 0;
    }
    .nav-back, .nav-forward, .copy-btn {
      width: 24px; height: 24px;
      display: flex; align-items: center; justify-content: center;
      background: none; border: 1px solid var(--pages-neutral-4, #e5e5e5);
      border-radius: var(--pages-radius-sm, 4px);
      cursor: pointer; font-size: 12px;
      color: var(--pages-neutral-9, #888);
    }
    .nav-back:hover, .nav-forward:hover, .copy-btn:hover {
      background: var(--pages-neutral-3, #e5e5e5);
      color: var(--pages-neutral-11, #333);
    }
    .nav-back:disabled, .nav-forward:disabled {
      opacity: 0.3; cursor: default;
    }
    .content-area {
      flex: 1;
      overflow-y: auto;
      padding: var(--pages-space-3, 12px) var(--pages-space-4, 16px);
    }
    .content-text {
      font-size: var(--pages-font-size-sm, 13px);
      line-height: var(--pages-line-height-base, 20px);
      color: var(--pages-neutral-11, #333);
      white-space: pre-wrap;
      word-break: break-word;
    }
    .artifact-card {
      display: flex;
      align-items: center;
      gap: var(--pages-space-3, 12px);
      padding: var(--pages-space-3, 12px);
      border: 1px solid var(--pages-neutral-4, #e5e5e5);
      border-radius: var(--pages-radius-sm, 4px);
    }
    .card-icon { font-size: 24px; }
    .card-info { flex: 1; }
    .card-label {
      font-size: var(--pages-font-size-sm, 13px);
      font-weight: var(--pages-font-weight-semibold, 600);
      color: var(--pages-neutral-12, #111);
    }
    .card-uri {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-accent-9, #6366f1);
    }
    .scope-highlight {
      background: var(--pages-warning-3, #fef3c7);
      padding: var(--pages-space-2, 8px);
      border-radius: var(--pages-radius-sm, 4px);
      margin-bottom: var(--pages-space-2, 8px);
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-warning-11, #92400e);
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

  private _typeIcon(type: string): string {
    switch (type) {
      case 'DOCUMENT': return '📄';
      case 'CODE': return '💻';
      case 'CASE': return '📁';
      case 'WORK_ITEM': return '📋';
      case 'CHANNEL': return '💬';
      case 'MESSAGE': return '✉️';
      case 'DEBATE': return '⚖️';
      case 'EXTERNAL': return '🔗';
      default: return '📎';
    }
  }

  override willUpdate(changed: Map<string, unknown>) {
    if (changed.has('selectedArtefactRef') && this.selectedArtefactRef) {
      const ref = this.selectedArtefactRef;
      const lastInHistory = this._history[this._historyIndex];
      if (!lastInHistory || lastInHistory.uri !== ref.uri) {
        this._history = [...this._history.slice(0, this._historyIndex + 1), ref];
        this._historyIndex = this._history.length - 1;
      }
      this._loadContent(ref);
    }
  }

  private async _loadContent(ref: ArtefactRef) {
    if (this.resolveArtifact) {
      const result = await this.resolveArtifact(ref);
      this._content = result.content;
      this._language = result.language;
    } else {
      this._content = ref.label;
      this._language = undefined;
    }
  }

  private _navigateBack() {
    if (this._historyIndex > 0) {
      this._historyIndex--;
      const ref = this._history[this._historyIndex]!;
      this.selectedArtefactRef = ref;
    }
  }

  private _navigateForward() {
    if (this._historyIndex < this._history.length - 1) {
      this._historyIndex++;
      const ref = this._history[this._historyIndex]!;
      this.selectedArtefactRef = ref;
    }
  }

  private _copyUri() {
    if (this.selectedArtefactRef) {
      navigator.clipboard?.writeText(this.selectedArtefactRef.uri);
    }
  }

  override render() {
    if (!this.selectedArtefactRef) {
      return html`
        <div class="panel-title">Artifacts</div>
        <div class="empty">Select a message with attachments</div>
      `;
    }

    const ref = this.selectedArtefactRef;
    return html`
      <div class="panel-title">Artifacts</div>
      <div class="header-bar">
        <div class="nav-buttons">
          <button class="nav-back" ?disabled=${this._historyIndex <= 0}
            @click=${this._navigateBack}>←</button>
          <button class="nav-forward" ?disabled=${this._historyIndex >= this._history.length - 1}
            @click=${this._navigateForward}>→</button>
        </div>
        <span class="artifact-label">${ref.label}</span>
        <span class="type-badge">${ref.type}</span>
        <button class="copy-btn" title="Copy URI" @click=${this._copyUri}>📋</button>
      </div>
      <div class="artifact-uri">${ref.uri}</div>
      <div class="content-area">
        ${this._renderContent(ref)}
      </div>
    `;
  }

  private _renderContent(ref: ArtefactRef) {
    if (ref.scope?.selectedText) {
      return html`
        <div class="scope-highlight">
          ${ref.scope.startLine != null ? html`Lines ${ref.scope.startLine}–${ref.scope.endLine ?? ref.scope.startLine}: ` : nothing}
          ${ref.scope.selectedText}
        </div>
        <div class="content-text">${this._content ?? ref.label}</div>
      `;
    }

    switch (ref.type) {
      case 'CASE':
      case 'WORK_ITEM':
      case 'CHANNEL':
      case 'MESSAGE':
      case 'EXTERNAL':
        return html`
          <div class="artifact-card">
            <span class="card-icon">${this._typeIcon(ref.type)}</span>
            <div class="card-info">
              <div class="card-label">${ref.label}</div>
              <div class="card-uri">${ref.uri}</div>
            </div>
          </div>
        `;
      default:
        return html`<div class="content-text">${this._content ?? ref.label}</div>`;
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'qhorus-artifact-panel': QhorusArtifactPanelElement;
  }
}
