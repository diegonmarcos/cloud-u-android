import test from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { FIX, decl, folderReader, src } from './helpers.mjs';

const oc = await src('obsidian-config.ts');
const pvt = await oc.readObsidianConfig(folderReader(join(FIX, 'pvt-notes')), decl);
const grm = await oc.readObsidianConfig(folderReader(join(FIX, 'git-repos-master')), decl);

test('reads app, appearance, core and community plugins from the vault folder', () => {
  assert.equal(pvt.found, true);
  assert.deepEqual(pvt.files.filter(f => f.present).map(f => f.id).sort(), ['app', 'appearance', 'canvas', 'community-plugins', 'core-plugins', 'graph', 'hotkeys']);
  assert.ok(pvt.summary.some(s => s.label === 'Default view mode' && s.value === 'preview'));
  assert.ok(pvt.summary.some(s => s.label === 'Theme' && s.value === 'obsidian'));
  assert.equal(pvt.corePlugins.find(p => p.id === 'file-explorer').enabled, true);
  assert.equal(pvt.corePlugins.find(p => p.id === 'slash-command').enabled, false);
  assert.equal(pvt.communityPlugins.length, 21);
});

test('every plugin carries its native-support status (none unmapped) with a reason', () => {
  for (const r of [pvt, grm]) {
    for (const p of [...r.corePlugins, ...r.communityPlugins]) {
      assert.ok(['builtin', 'planned', 'na'].includes(p.status), `${p.id} -> ${p.status}`);
      assert.ok(p.feature.length > 5, p.id);
    }
  }
  const byId = Object.fromEntries(pvt.communityPlugins.map(p => [p.id, p]));
  assert.equal(byId['json-editor'].status, 'builtin');
  assert.equal(byId['dataview'].status, 'planned');
  assert.equal(byId['obsidian-git'].status, 'na');
});

test('installed plugin folders show name and version; missing ones are marked', () => {
  const dv = pvt.communityPlugins.find(p => p.id === 'dataview');
  assert.deepEqual([dv.installed, dv.name, dv.version], [true, 'Dataview', '0.5.68']);
  assert.equal(pvt.communityPlugins.find(p => p.id === 'quickadd').installed, false);
});

test('hotkeys and snippets (with enabled flag from appearance.json)', () => {
  assert.deepEqual(pvt.hotkeys.find(h => h.command === 'editor:toggle-bold').keys, ['Mod+B']);
  assert.deepEqual(pvt.snippets.map(s => [s.name, s.enabled]).sort(), [['canvas-zoom', true], ['readable-min-font', true]]);
});

test('a folder without .obsidian reports found:false, and bad JSON is reported not thrown', async () => {
  const none = await oc.readObsidianConfig(folderReader(join(FIX, 'sample')), decl);
  assert.equal(none.found, false);
  assert.equal(none.files.every(f => !f.present), true);
  const broken = await oc.readObsidianConfig(
    { tryReadText: async p => (p.endsWith('app.json') ? '{oops' : null), listNames: async () => [{ name: 'app.json', isDir: false, size: 5 }] },
    decl
  );
  assert.ok(broken.files.find(f => f.id === 'app').parseError);
});

test('read-only: the reader has no write path', () => {
  assert.deepEqual(Object.keys(oc).filter(k => /write|apply|save/i.test(k)), []);
});
