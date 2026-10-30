import test from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { FIX, fixture, src, walk } from './helpers.mjs';

const vi = await src('vault-index.ts');
const root = join(FIX, 'sample');
const all = walk(root);
const files = all.filter(p => vi.INDEXED_EXT.includes(p.split('.').pop())).map(p => ({ path: p, text: fixture('sample', p) }));
const idx = vi.buildIndex(files, all);

test('links resolve by shortest path, across folders and to attachments', () => {
  const home = idx.notes['Home.md'];
  const by = t => home.links.find(l => l.target === t);
  assert.equal(by('Ideas').resolved, 'notes/Ideas.md');
  assert.equal(by('board.canvas').resolved, 'board.canvas');
  assert.equal(by('diagram.mmd').resolved, 'diagram.mmd');
  assert.equal(by('Does Not Exist').resolved, null);
});

test('backlinks: notes and canvases that point at a note', () => {
  assert.deepEqual(idx.backlinks['Home.md'].sort(), ['board.canvas', 'notes/Ideas.md']);
  assert.deepEqual(idx.backlinks['notes/Ideas.md'].sort(), ['Home.md', 'board.canvas']);
  assert.ok(!('Does Not Exist.md' in idx.backlinks));
});

test('unresolved links are listed with their sources', () => {
  assert.deepEqual(idx.unresolved['Does Not Exist'], ['Home.md']);
});

test('tags: per tag note lists and nested counts roll up', () => {
  assert.deepEqual(idx.tags['project/alpha'].sort(), ['Home.md', 'notes/Ideas.md']);
  const counts = Object.fromEntries(vi.tagCounts(idx).map(t => [t.tag, t.count]));
  assert.equal(counts.project, 2);
  assert.equal(counts['project/alpha'], 2);
  assert.equal(counts.inbox, 1);
});

test('outline and tasks per note', () => {
  assert.deepEqual(idx.notes['notes/Ideas.md'].headings.map(h => h.text), ['Ideas', 'Backlog']);
  assert.deepEqual(idx.notes['Home.md'].tasks, { total: 2, done: 1 });
});

test('the builder is incremental (chunks) and equals the one-shot build', () => {
  const b = new vi.IndexBuilder();
  files.slice(0, 1).forEach(f => b.add(f.path, f.text));
  files.slice(1).forEach(f => b.add(f.path, f.text));
  assert.deepEqual(b.finish(all), idx);
});

test('search: file names rank exact, prefix, substring; content search finds lines', () => {
  assert.deepEqual(vi.searchPaths(['a/Ideas.md', 'Home.md', 'x/more-ideas.md'], 'ideas').slice(0, 2), ['a/Ideas.md', 'x/more-ideas.md']);
  const hits = vi.searchContent({ 'Home.md': fixture('sample', 'Home.md') }, 'welcome');
  assert.equal(hits[0].path, 'Home.md');
});
