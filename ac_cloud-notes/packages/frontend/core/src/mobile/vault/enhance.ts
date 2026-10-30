// DOM post-processing of rendered markdown (the HTML string is escape-safe; this adds the
// parts that need a library or the file system): KaTeX, mermaid, vault images and embeds.
// katex and mermaid are loaded on first use, so a vault without them costs nothing.
import 'katex/dist/katex.min.css';

import { renderClassicMermaidSvg } from '../../modules/code-block-preview-renderer/classic-mermaid';
import { IMAGE_EXT, parseFrontmatter, renderMarkdown } from './markdown';

export interface EnhanceContext {
  /** vault path of the note being shown */
  path: string;
  alive: () => boolean;
  resolve: (target: string, from: string) => string | null;
  readText: (path: string) => Promise<string | null>;
  readDataUrl: (path: string) => Promise<string | null>;
  /** nesting depth of embeds; embeds stop at 2 so a cycle cannot run away */
  depth?: number;
}

const isDark = (el: HTMLElement) => {
  const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(getComputedStyle(el).color);
  return m ? (Number(m[1]) * 299 + Number(m[2]) * 587 + Number(m[3]) * 114) / 1000 > 128 : false;
};

export async function renderMermaidInto(el: HTMLElement, code: string): Promise<void> {
  try {
    const { svg } = await renderClassicMermaidSvg({ code, options: { theme: isDark(el) ? 'dark' : 'default' } });
    el.classList.add('cnv-mermaid');
    el.innerHTML = svg;
  } catch (e) {
    el.textContent = 'Mermaid error: ' + (e instanceof Error ? e.message : String(e));
  }
}

export async function enhanceMarkdown(root: HTMLElement, ctx: EnhanceContext): Promise<void> {
  const depth = ctx.depth ?? 0;

  const maths = [...root.querySelectorAll<HTMLElement>('.math[data-tex]')];
  if (maths.length) {
    const katex = (await import('katex')).default;
    if (!ctx.alive()) return;
    for (const el of maths) {
      try {
        el.innerHTML = katex.renderToString(el.dataset.tex ?? '', {
          throwOnError: false,
          displayMode: el.classList.contains('math-display'),
          output: 'htmlAndMathml',
        });
      } catch {
        /* leave the TeX source visible */
      }
    }
  }

  for (const pre of [...root.querySelectorAll<HTMLElement>('pre.mermaid')]) {
    if (!ctx.alive()) return;
    await renderMermaidInto(pre, pre.dataset.mermaid ?? '');
  }

  for (const img of [...root.querySelectorAll<HTMLImageElement>('img[data-src]')]) {
    const src = img.dataset.src ?? '';
    if (/^https?:/i.test(src)) {
      // offline by default: a remote image is a link, not a request
      const a = document.createElement('a');
      a.className = 'external-link';
      a.href = src;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.textContent = img.alt || src;
      img.replaceWith(a);
      continue;
    }
    const hit = ctx.resolve(decodeURIComponent(src), ctx.path);
    const url = hit ? await ctx.readDataUrl(hit) : null;
    if (!ctx.alive()) return;
    if (url) img.src = url;
    else img.replaceWith(document.createTextNode(`[missing image: ${src}]`));
  }

  for (const el of [...root.querySelectorAll<HTMLElement>('.embed[data-target]')]) {
    if (!ctx.alive()) return;
    const target = el.dataset.target ?? '';
    const hit = ctx.resolve(target, ctx.path);
    if (!hit) {
      el.textContent = `[unresolved embed: ${target}]`;
      continue;
    }
    if (IMAGE_EXT.test(hit)) {
      const url = await ctx.readDataUrl(hit);
      if (url) {
        const img = document.createElement('img');
        img.src = url;
        img.alt = target;
        el.replaceWith(img);
      }
      continue;
    }
    const text = await ctx.readText(hit);
    if (text === null || depth >= 2) continue;
    if (/\.(mmd|mermaid)$/i.test(hit)) {
      const box = document.createElement('div');
      el.replaceWith(box);
      await renderMermaidInto(box, text);
      continue;
    }
    if (/\.(md|markdown)$/i.test(hit)) {
      el.innerHTML = renderMarkdown(parseFrontmatter(text).body);
      await enhanceMarkdown(el, { ...ctx, path: hit, depth: depth + 1 });
    }
  }
}
