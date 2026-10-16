import { describe, expect, it } from 'vitest';

import type { ExternalDirListing } from './plugins/external-file';
import {
  collectVault,
  toVaultFiles,
  type VaultFS,
  vaultNameOf,
} from './vault-import';

// A tiny vault the way the phone shows it: folders first, dot entries present
// here on purpose (the real plugin hides them; the walk must not rely on that).
const tree: Record<string, string | null> = {
  '/v/a-Inbox/2026-03-15-03.md': '---\ntags: [inbox]\n---\nSee [[Plan]]',
  '/v/c-Projects/Plan.md': '# Plan\n> [!note]\n> callout',
  '/v/c-Projects/deep/Note with spaces.md': 'text ![[img.png]]',
  '/v/c-Projects/data.json': '{}',
  '/v/.trash/Old.md': 'old',
  '/v/.obsidian/app.json': '{}',
  '/v/Root.md': 'root',
  '/v/broken.md': null, // unreadable
};
const dirs = new Set(['/v/', '/v/a-Inbox/', '/v/c-Projects/', '/v/c-Projects/deep/', '/v/.trash/', '/v/.obsidian/']);

const fs: VaultFS = {
  async listDir({ path }): Promise<ExternalDirListing> {
    if (!dirs.has(path)) throw new Error(`no dir ${path}`);
    const names = new Map<string, boolean>();
    for (const p of [...Object.keys(tree), ...dirs]) {
      if (p !== path && p.startsWith(path)) {
        const rest = p.slice(path.length);
        const first = rest.split('/')[0];
        names.set(first, rest.includes('/') ? true : names.get(first) ?? false);
      }
    }
    return {
      path,
      entries: [...names].map(([name, isDir]) => ({ name, isDir, size: 0 })),
    };
  },
  async readFile({ path }) {
    const c = tree[path];
    if (c === null || c === undefined) throw new Error(`cannot read ${path}`);
    return { content: c, name: path.split('/').pop()!, size: c.length, path };
  },
};

describe('vault folder walk', () => {
  it('collects every note, nested, sorted, with vault-relative paths', async () => {
    const r = await collectVault(fs, '/v');
    expect(r.notes.map(n => n.path)).toEqual([
      'Root.md',
      'a-Inbox/2026-03-15-03.md',
      'c-Projects/Plan.md',
      'c-Projects/deep/Note with spaces.md',
    ]);
    expect(r.truncated).toBe(false);
  });

  it('A1.1: note count equals the readable .md count, nothing skipped silently', async () => {
    const r = await collectVault(fs, '/v/');
    const skipped = Object.fromEntries(r.skipped.map(s => [s.path, s.reason]));
    expect(skipped['.trash']).toBe('hidden');
    expect(skipped['.obsidian']).toBe('hidden');
    expect(skipped['c-Projects/data.json']).toMatch(/not a note/);
    expect(skipped['broken.md']).toMatch(/cannot read/);
    expect(r.notes.length + 1).toBe(
      Object.keys(tree).filter(p => /\.md$/.test(p) && !p.includes('/.trash/')).length
    );
  });

  it('limits stop the walk and say so', async () => {
    const few = await collectVault(fs, '/v', { maxNotes: 2, maxDepth: 12 });
    expect(few.notes).toHaveLength(2);
    expect(few.truncated).toBe(true);
    expect(few.skipped.some(s => s.reason === 'over the note limit')).toBe(true);
    const shallow = await collectVault(fs, '/v', { maxNotes: 100, maxDepth: 1 });
    expect(shallow.notes.map(n => n.path)).not.toContain('c-Projects/deep/Note with spaces.md');
    expect(shallow.truncated).toBe(true);
  });

  it('turns notes into the Files the importer reads, keyed by vault-relative path', async () => {
    const r = await collectVault(fs, '/v');
    const files = toVaultFiles(r.notes, vaultNameOf('/v/'));
    const f = files.find(x => x.name === 'Note with spaces.md')!;
    expect(f.webkitRelativePath).toBe('v/c-Projects/deep/Note with spaces.md');
    expect(await f.text()).toBe('text ![[img.png]]');
    expect(vaultNameOf('/storage/emulated/0/Documents/cloud-notes/')).toBe('cloud-notes');
  });
});
