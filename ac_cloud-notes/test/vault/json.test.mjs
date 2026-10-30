import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, src } from './helpers.mjs';

const jt = await src('json-tree.ts');
const text = fixture('sample', 'data.json');

test('tree: root open, children collapsed until expanded, pointers are RFC 6901', () => {
  const p = jt.parseJson(text);
  assert.equal(p.ok, true);
  const closed = jt.jsonRows(p.value, new Set());
  assert.deepEqual(closed.map(r => r.key), ['$', 'name', 'version', 'tags', 'nested', 'list']);
  assert.equal(closed.find(r => r.key === 'tags').preview, '[2]');
  assert.equal(closed.find(r => r.key === 'nested').preview, '{3}');
  const open = jt.jsonRows(p.value, new Set(['/nested', '/list', '/list/1']));
  assert.ok(open.some(r => r.pointer === '/nested/ok' && r.kind === 'boolean'));
  assert.ok(open.some(r => r.pointer === '/nested/none' && r.kind === 'null'));
  assert.ok(open.some(r => r.pointer === '/list/1/x' && r.preview === '2'));
});

test('text editor side: a syntax error is reported, format keeps the data', () => {
  const bad = jt.parseJson('{"a":');
  assert.equal(bad.ok, false);
  assert.ok(bad.error.length > 0);
  assert.deepEqual(JSON.parse(jt.formatJson(text)), JSON.parse(text));
});
