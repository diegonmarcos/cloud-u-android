// Builds the vault index OFF the UI thread: files are posted to a Web Worker in chunks as
// they are read; where Workers are unavailable (tests, old WebView) the same IndexBuilder
// runs on the main thread, yielding to the event loop between chunks so taps stay live.
import { IndexBuilder, type VaultIndex } from './vault-index';
import type { IndexRequest, IndexResponse } from './vault-index.worker';

const tick = () => new Promise<void>(r => setTimeout(r, 0));

export interface RunOptions {
  chunk: number;
  onProgress?: (parsed: number, total: number) => void;
}

export async function runIndex(
  texts: ReadonlyMap<string, string>,
  allPaths: readonly string[],
  opts: RunOptions
): Promise<VaultIndex> {
  const entries = [...texts.entries()];
  let worker: Worker | null = null;
  try {
    if (typeof Worker !== 'undefined') {
      worker = new Worker(new URL('./vault-index.worker.ts', import.meta.url), { type: 'module' });
    }
  } catch {
    worker = null;
  }
  if (worker) {
    const w = worker;
    try {
      return await new Promise<VaultIndex>((resolve, reject) => {
        w.onerror = e => reject(new Error(e.message || 'index worker failed'));
        w.onmessage = (e: MessageEvent<IndexResponse>) => {
          if (e.data.type === 'progress') opts.onProgress?.(e.data.parsed, entries.length);
          else resolve(e.data.index);
        };
        const post = (m: IndexRequest) => w.postMessage(m);
        post({ type: 'reset' });
        for (let i = 0; i < entries.length; i += opts.chunk) {
          post({ type: 'add', files: entries.slice(i, i + opts.chunk).map(([path, text]) => ({ path, text })) });
        }
        post({ type: 'finish', allPaths: [...allPaths] });
      });
    } catch {
      /* fall through to the main-thread builder */
    } finally {
      w.terminate();
    }
  }
  const b = new IndexBuilder();
  for (let i = 0; i < entries.length; i += opts.chunk) {
    for (const [path, text] of entries.slice(i, i + opts.chunk)) b.add(path, text);
    opts.onProgress?.(b.size, entries.length);
    await tick();
  }
  return b.finish(allPaths);
}
