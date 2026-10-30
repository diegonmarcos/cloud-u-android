import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, src } from './helpers.mjs';

const jc = await src('jsoncanvas.ts');
const text = fixture('sample', 'board.canvas');

test('parse: the fixture has nodes, edges and a group, no errors', () => {
  const { canvas, errors } = jc.parseCanvas(text);
  assert.deepEqual(errors, []);
  assert.deepEqual(canvas.nodes.map(n => n.type), ['group', 'text', 'file', 'link']);
  assert.equal(canvas.edges.length, 1);
  assert.equal(canvas.edges[0].label, 'leads to');
});

test('round trip: parse -> serialise -> parse is identical and serialising is stable', () => {
  const a = jc.parseCanvas(text).canvas;
  const s1 = jc.serializeCanvas(a);
  const b = jc.parseCanvas(s1).canvas;
  assert.deepEqual(b, a);
  assert.equal(jc.serializeCanvas(b), s1);
  assert.deepEqual(JSON.parse(s1), JSON.parse(text));
});

test('unknown fields survive (node customField, top-level extra)', () => {
  const s = JSON.parse(jc.serializeCanvas(jc.parseCanvas(text).canvas));
  assert.deepEqual(s.nodes[2].customField, { keep: true });
  assert.deepEqual(s.extra, { viewport: 1 });
});

test('edit: add, move a group with its members, remove a node and its edges', () => {
  let c = jc.parseCanvas(text).canvas;
  assert.deepEqual(jc.membersOf(c, 'g1').map(n => n.id).sort(), ['n1', 'n2']);
  c = jc.moveNode(c, 'g1', 10, 20);
  assert.deepEqual([c.nodes[0].x, c.nodes[1].x, c.nodes[1].y, c.nodes[3].x], [-10, 10, 20, 0]);
  const added = jc.addTextNode(c, { x: 5, y: 5 }, 'hi');
  assert.equal(added.canvas.nodes.length, 5);
  const e = jc.addEdge(added.canvas, added.id, 'n1');
  assert.ok(e.id);
  assert.equal(jc.addEdge(e.canvas, 'n1', 'n1').id, null);
  assert.equal(jc.addEdge(e.canvas, 'n1', 'missing').id, null);
  const gone = jc.removeNode(e.canvas, 'n1');
  assert.ok(gone.edges.every(x => x.fromNode !== 'n1' && x.toNode !== 'n1'));
  assert.equal(jc.parseCanvas(jc.serializeCanvas(gone)).errors.length, 0);
});

test('invalid input: bad entries are dropped with a message, not thrown', () => {
  assert.match(jc.parseCanvas('{nope').errors[0], /not valid JSON/);
  const r = jc.parseCanvas(
    '{"nodes":[{"id":"a","type":"text","x":0,"y":0,"width":1,"height":1},{"id":"a","type":"text","x":0,"y":0,"width":1,"height":1},{"id":"b","type":"zzz","x":0,"y":0,"width":1,"height":1}],"edges":[{"id":"e","fromNode":"a","toNode":"ghost"}]}'
  );
  assert.equal(r.canvas.nodes.length, 1);
  assert.equal(r.canvas.edges.length, 0);
  assert.equal(r.errors.length, 3);
  assert.deepEqual(jc.parseCanvas('').canvas, { nodes: [], edges: [] });
});
