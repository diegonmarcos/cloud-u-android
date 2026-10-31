// The viewers: one per build.json::vault.file_types handler. Each gets the tab and the vault
// API; text-based ones edit the tab's buffer, the tab is saved by autosave or Save.
import { type MouseEvent as RMouseEvent, useEffect, useMemo, useRef, useState } from 'react';

import { CanvasView } from './canvas-view';
import type { VaultDecl } from './decl-types';
import { enhanceMarkdown, renderMermaidInto } from './enhance';
import { buildSandboxFrame } from './html-sandbox';
import { jsonRows, parseJson } from './json-tree';
import { parseFrontmatter, renderMarkdown } from './markdown';
import { resolveLink } from './vault-index';
import type { Tab, VaultApi } from './use-vault';

export const SourceEditor = ({ tab, api }: { tab: Tab; api: VaultApi }) => (
  <textarea
    className="cnv-editor"
    spellCheck={false}
    value={tab.text ?? ''}
    onChange={e => api.setText(tab.path, e.target.value)}
    data-testid="source-editor"
  />
);

export const MarkdownView = ({ tab, api, decl }: { tab: Tab; api: VaultApi; decl: VaultDecl }) => {
  const ref = useRef<HTMLDivElement>(null);
  const text = tab.text ?? '';
  const fm = useMemo(() => parseFrontmatter(text), [text]);
  const html = useMemo(() => renderMarkdown(text), [text]);
  const hidden = tab.path.startsWith(decl.config_dir + '/');

  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    let alive = true;
    const paths = api.allPaths;
    void enhanceMarkdown(root, {
      path: tab.path,
      alive: () => alive,
      resolve: (t, from) => resolveLink(t, from, paths),
      readText: async p => {
        const cached = api.noteTexts.current.get(p);
        if (cached !== undefined) return cached;
        return api.vault ? api.vault.fs.tryReadText(p) : null;
      },
      readDataUrl: api.readDataUrl,
    });
    return () => {
      alive = false;
    };
  }, [html, tab.path, api.allPaths, api.noteTexts, api.vault, api.readDataUrl]);

  const onClick = (e: RMouseEvent<HTMLDivElement>) => {
    const t = e.target as HTMLElement;
    const link = t.closest<HTMLElement>('a.internal-link');
    if (link) {
      e.preventDefault();
      if (link.dataset.target) void api.followLink(link.dataset.target, tab.path);
      return;
    }
    const ext = t.closest<HTMLAnchorElement>('a.external-link');
    if (ext) {
      e.preventDefault();
      window.open(ext.href, '_blank', 'noopener,noreferrer');
      return;
    }
    const tag = t.closest<HTMLElement>('a.tag');
    if (tag) {
      e.preventDefault();
      window.dispatchEvent(new CustomEvent('cloud-notes:vault-search', { detail: '#' + (tag.dataset.tag ?? '') }));
    }
  };

  const entries = Object.entries(fm.data);
  return (
    <div className="cnv-pane" data-testid="markdown-preview">
      {fm.present && entries.length > 0 && !hidden && (
        <dl className="cnv-props" data-testid="properties">
          {entries.map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{Array.isArray(v) ? v.join(', ') : String(v ?? '')}</dd>
            </div>
          ))}
        </dl>
      )}
      <div ref={ref} className="cnv-note" onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />
      <div style={{ height: 96 }} />
    </div>
  );
};

export const MermaidView = ({ tab }: { tab: Tab }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (ref.current) void renderMermaidInto(ref.current, tab.text ?? '');
  }, [tab.text]);
  return (
    <div className="cnv-pane">
      <div ref={ref} data-testid="mermaid-render" />
    </div>
  );
};

export const JsonView = ({ tab }: { tab: Tab }) => {
  const parsed = useMemo(() => parseJson(tab.text ?? ''), [tab.text]);
  const [open, setOpen] = useState<Set<string>>(new Set());
  if (!parsed.ok) {
    return (
      <div className="cnv-pane">
        <div className="cnv-banner" data-testid="json-error">Not valid JSON: {parsed.error}. Fix it in the Source view.</div>
      </div>
    );
  }
  const rows = jsonRows(parsed.value, open);
  return (
    <div className="cnv-pane" data-testid="json-tree">
      {rows.map(r => (
        <div
          key={r.pointer || '$'}
          className="cnv-tree-row"
          style={{ paddingLeft: r.depth * 16, cursor: r.expandable ? 'pointer' : 'default' }}
          onClick={() => {
            if (!r.expandable) return;
            setOpen(prev => {
              const next = new Set(prev);
              if (next.has(r.pointer)) next.delete(r.pointer);
              else next.add(r.pointer);
              return next;
            });
          }}
        >
          <span style={{ width: 12 }}>{r.expandable ? (r.expanded ? 'v' : '>') : ''}</span>
          <span style={{ opacity: 0.75 }}>{r.key}</span>
          <span style={{ color: r.kind === 'string' ? 'var(--color-green)' : r.kind === 'number' ? 'var(--color-orange)' : r.kind === 'boolean' || r.kind === 'null' ? 'var(--color-purple)' : undefined }}>
            {r.preview}
          </span>
        </div>
      ))}
      <div style={{ height: 96 }} />
    </div>
  );
};

export const HtmlView = ({ tab, api, decl }: { tab: Tab; api: VaultApi; decl: VaultDecl }) => {
  const frame = useMemo(() => buildSandboxFrame(tab.text ?? '', decl.html, tab.cdn), [tab.text, tab.cdn, decl.html]);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
      <div className="cnv-banner" data-testid="html-banner">
        <span>
          {tab.cdn ? 'CDN scripts and fonts are allowed for this page.' : 'Offline: scripts run, the network is blocked.'}
          {frame.external.length > 0 && ` ${frame.external.length} remote address(es) in the page.`}
        </span>
        <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={tab.cdn} onChange={e => api.patchTab(tab.path, { cdn: e.target.checked })} data-testid="cdn-toggle" />
          {decl.html.cdn.label}
        </label>
      </div>
      {/* opaque origin: no allow-same-origin, so the page cannot reach the app or its bridge */}
      <iframe key={String(tab.cdn)} className="cnv-iframe" title={tab.path} sandbox={frame.sandbox} srcDoc={frame.srcdoc} referrerPolicy="no-referrer" data-testid="html-frame" />
    </div>
  );
};

export const ImageView = ({ tab, api }: { tab: Tab; api: VaultApi }) => {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let alive = true;
    void api.readDataUrl(tab.path).then(u => alive && setUrl(u));
    return () => {
      alive = false;
    };
  }, [tab.path, api.readDataUrl]);
  return <div className="cnv-pane">{url ? <img src={url} alt={tab.path} style={{ maxWidth: '100%' }} /> : <span className="cnv-dim">Loading image</span>}</div>;
};

export const CanvasPane = ({ tab, api }: { tab: Tab; api: VaultApi }) => (
  <CanvasView
    text={tab.text ?? ''}
    onChange={t => api.setText(tab.path, t)}
    onOpenFile={target => {
      const hit = resolveLink(target, tab.path, api.allPaths);
      if (hit) void api.openFile(hit);
    }}
  />
);
