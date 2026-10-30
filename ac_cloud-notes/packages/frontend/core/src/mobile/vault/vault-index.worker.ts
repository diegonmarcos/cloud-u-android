// Web Worker around IndexBuilder: the main thread posts files as it reads them, a chunk at a
// time, and the parsing (frontmatter, links, tags, headings) happens here, off the UI thread.
import { IndexBuilder, type VaultIndex } from './vault-index';

export type IndexRequest =
  | { type: 'reset' }
  | { type: 'add'; files: { path: string; text: string }[] }
  | { type: 'finish'; allPaths: string[] };

export type IndexResponse = { type: 'progress'; parsed: number } | { type: 'done'; index: VaultIndex };

let builder = new IndexBuilder();
const ctx = self as unknown as { postMessage(m: IndexResponse): void; onmessage: ((e: MessageEvent<IndexRequest>) => void) | null };

ctx.onmessage = e => {
  const m = e.data;
  if (m.type === 'reset') builder = new IndexBuilder();
  else if (m.type === 'add') {
    for (const f of m.files) builder.add(f.path, f.text);
    ctx.postMessage({ type: 'progress', parsed: builder.size });
  } else if (m.type === 'finish') {
    ctx.postMessage({ type: 'done', index: builder.finish(m.allPaths) });
  }
};
