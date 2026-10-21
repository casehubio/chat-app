import { describe, it, expect, vi, afterEach } from 'vitest';
import './qhorus-artifact-panel.js';
import type { ArtefactRef } from '@casehubio/mosaic-channel-activity';

afterEach(() => { document.body.innerHTML = ''; });

async function render(ref?: ArtefactRef) {
  const el = document.createElement('qhorus-artifact-panel') as any;
  if (ref) el.selectedArtefactRef = ref;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('QhorusArtifactPanelElement', () => {
  it('shows empty state when no artifact selected', async () => {
    const el = await render(undefined);
    expect(el.shadowRoot!.textContent).toContain('Select a message with attachments');
  });

  it('renders artifact metadata', async () => {
    const ref: ArtefactRef = { uri: 'docs/spec.md', type: 'DOCUMENT', label: 'Design Spec' };
    const el = await render(ref);
    expect(el.shadowRoot!.textContent).toContain('Design Spec');
    expect(el.shadowRoot!.textContent).toContain('DOCUMENT');
    expect(el.shadowRoot!.textContent).toContain('docs/spec.md');
  });

  it('renders card layout for CASE type', async () => {
    const ref: ArtefactRef = { uri: 'case://123', type: 'CASE', label: 'Case 123' };
    const el = await render(ref);
    const card = el.shadowRoot!.querySelector('.artifact-card');
    expect(card).not.toBeNull();
  });

  it('renders scope highlight when selectedText present', async () => {
    const ref: ArtefactRef = {
      uri: 'src/main.ts', type: 'CODE', label: 'Main',
      scope: { startLine: 10, endLine: 20, selectedText: 'function foo()' },
    };
    const el = await render(ref);
    const highlight = el.shadowRoot!.querySelector('.scope-highlight');
    expect(highlight).not.toBeNull();
    expect(highlight!.textContent).toContain('function foo()');
    expect(highlight!.textContent).toContain('10');
  });

  it('maintains navigation history', async () => {
    const ref1: ArtefactRef = { uri: 'a.md', type: 'DOCUMENT', label: 'A' };
    const el = await render(ref1);
    expect(el.shadowRoot!.querySelector('.nav-back')!.disabled).toBe(true);

    const ref2: ArtefactRef = { uri: 'b.md', type: 'CODE', label: 'B' };
    el.selectedArtefactRef = ref2;
    await el.updateComplete;
    expect(el.shadowRoot!.textContent).toContain('B');
    expect(el.shadowRoot!.querySelector('.nav-back')!.disabled).toBe(false);

    el.shadowRoot!.querySelector('.nav-back')!.click();
    await el.updateComplete;
    expect(el.shadowRoot!.textContent).toContain('A');
    expect(el.shadowRoot!.querySelector('.nav-forward')!.disabled).toBe(false);
  });

  it('uses resolveArtifact callback when provided', async () => {
    const ref: ArtefactRef = { uri: 'test.md', type: 'DOCUMENT', label: 'Test' };
    const el = await render(ref);
    el.resolveArtifact = vi.fn().mockResolvedValue({ content: 'Resolved content', language: 'markdown' });
    el.selectedArtefactRef = { ...ref };
    await el.updateComplete;
    await new Promise(r => setTimeout(r, 10));
    await el.updateComplete;
    expect(el.resolveArtifact).toHaveBeenCalled();
  });
});
