import type { HtmlDecl } from './decl-types';

export interface SandboxFrame {
  /** the document for iframe srcdoc: our CSP meta first, then the page */
  srcdoc: string;
  /** value for the iframe sandbox attribute */
  sandbox: string;
  /** the CSP that was injected */
  csp: string;
  /** remote addresses the page asks for (src / href / @import), for the toggle banner */
  external: string[];
}

const REMOTE = /\b(?:src|href)\s*=\s*["'](https?:\/\/[^"'\s>]+)["']|@import\s+(?:url\()?["']?(https?:\/\/[^"')\s;]+)/gi;

export function externalRefs(html: string): string[] {
  const out = new Set<string>();
  for (const m of html.matchAll(REMOTE)) out.add((m[1] ?? m[2])!);
  return [...out];
}

/** The Content-Security-Policy for a page: closed by default, the declared CDN hosts when allowed. */
export function buildCsp(html: HtmlDecl, allowCdn: boolean): string {
  const d: Record<string, string[]> = {};
  for (const [k, v] of Object.entries(html.csp_default)) d[k] = [...v];
  if (allowCdn) {
    d['script-src'] = [...d['script-src']!, ...html.cdn.script, ...html.cdn.script_extra];
    d['style-src'] = [...d['style-src']!, ...html.cdn.style];
    d['font-src'] = [...d['font-src']!, ...html.cdn.font];
  }
  return Object.entries(d)
    .map(([k, v]) => `${k} ${v.join(' ')}`)
    .join('; ');
}

const escAttr = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;');

/**
 * Wrap a page for a sandboxed iframe. The policy meta is placed before everything the page
 * has, so no page script runs ahead of it. The iframe gets NO allow-same-origin: the page
 * cannot read the app's storage or call its Capacitor bridge.
 */
export function buildSandboxFrame(page: string, html: HtmlDecl, allowCdn: boolean): SandboxFrame {
  const csp = buildCsp(html, allowCdn);
  const meta = `<meta http-equiv="Content-Security-Policy" content="${escAttr(csp)}">`;
  let srcdoc: string;
  const head = /<head(\s[^>]*)?>/i.exec(page);
  if (head) {
    srcdoc = page.slice(0, head.index + head[0].length) + meta + page.slice(head.index + head[0].length);
  } else {
    const root = /<html(\s[^>]*)?>/i.exec(page);
    srcdoc = root
      ? page.slice(0, root.index + root[0].length) + `<head>${meta}</head>` + page.slice(root.index + root[0].length)
      : `<!doctype html><html><head>${meta}</head><body>${page}</body></html>`;
  }
  return { srcdoc, sandbox: html.sandbox, csp, external: externalRefs(page) };
}
