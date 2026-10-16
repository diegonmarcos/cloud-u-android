import { describe, expect, test } from 'vitest';

import {
  backlinks,
  buildGraph,
  filterGraph,
  forceLayout,
  type GraphInput,
  type GraphModel,
  localGraph,
  orphans,
} from '../index';

// A4.1 fixture: a trashed doc, a self link, a duplicate link, a link to an
// unknown doc, a tag that only tags a trashed doc, an orphan.
const fixture: GraphInput = {
  docs: [
    { id: 'a', title: 'Alpha' },
    { id: 'b', title: 'Beta' },
    { id: 'c', title: 'Gamma' },
    { id: 'd', title: ' ' },
    { id: 'e', title: 'Epsilon', trash: true },
  ],
  links: [
    { from: 'a', to: 'b' },
    { from: 'b', to: 'a' },
    { from: 'a', to: 'c' },
    { from: 'a', to: 'c' },
    { from: 'c', to: 'c' },
    { from: 'a', to: 'e' },
    { from: 'b', to: 'zzz' },
  ],
  tags: [
    { id: 't1', name: 'project', docIds: ['a', 'b', 'e'] },
    { id: 't2', name: 'empty', docIds: ['e'] },
    { id: 't3', name: 'x', docIds: ['c', 'c'] },
  ],
};

describe('A4.1 graph builder', () => {
  test('golden node and edge set', () => {
    expect(buildGraph(fixture)).toEqual({
      nodes: [
        { id: 'a', kind: 'doc', label: 'Alpha', orphan: false },
        { id: 'b', kind: 'doc', label: 'Beta', orphan: false },
        { id: 'c', kind: 'doc', label: 'Gamma', orphan: false },
        { id: 'd', kind: 'doc', label: 'Untitled', orphan: true },
        { id: 'tag:t1', kind: 'tag', label: 'project', orphan: false },
        { id: 'tag:t3', kind: 'tag', label: 'x', orphan: false },
      ],
      edges: [
        { source: 'a', target: 'b', kind: 'link' },
        { source: 'a', target: 'c', kind: 'link' },
        { source: 'b', target: 'a', kind: 'link' },
        { source: 'tag:t1', target: 'a', kind: 'tag' },
        { source: 'tag:t1', target: 'b', kind: 'tag' },
        { source: 'tag:t3', target: 'c', kind: 'tag' },
      ],
    });
  });

  test('orphans are docs with no edge, tags excluded', () => {
    expect(orphans(buildGraph(fixture)).map(n => n.id)).toEqual(['d']);
  });

  test('input order does not change the result', () => {
    const shuffled: GraphInput = {
      docs: [...fixture.docs].reverse(),
      links: [...fixture.links].reverse(),
      tags: [...(fixture.tags ?? [])].reverse(),
    };
    expect(buildGraph(shuffled)).toEqual(buildGraph(fixture));
  });

  test('backlinks list the docs that link in', () => {
    const g = buildGraph(fixture);
    expect(backlinks(g, 'a').map(n => n.id)).toEqual(['b']);
    expect(backlinks(g, 'c').map(n => n.id)).toEqual(['a']);
    expect(backlinks(g, 'd')).toEqual([]);
  });

  test('filter keeps the edges between kept nodes only', () => {
    const g = filterGraph(buildGraph(fixture), n => n.kind === 'doc');
    expect(g.nodes.every(n => n.kind === 'doc')).toBe(true);
    expect(g.edges.every(e => e.kind === 'link')).toBe(true);
  });
});

/** a seeded random graph, so the BFS check and the builder see the same input */
function randomInput(n: number, m: number, seed: number): GraphInput {
  let a = seed;
  const rand = () => {
    a = (Math.imul(a, 1664525) + 1013904223) >>> 0;
    return a / 4294967296;
  };
  const docs = Array.from({ length: n }, (_, i) => ({ id: `n${i}` }));
  const links = Array.from({ length: m }, () => ({
    from: `n${Math.floor(rand() * n)}`,
    to: `n${Math.floor(rand() * n)}`,
  }));
  return { docs, links };
}

describe('A4.3 local graph', () => {
  test('depth 1-3 equals the BFS set computed independently (hop matrix)', () => {
    const n = 60;
    const input = randomInput(n, 90, 7);
    const model = buildGraph(input);
    const idx = new Map(model.nodes.map((nd, i) => [nd.id, i]));
    // independent computation: hop-count matrix by Floyd-Warshall on an undirected adjacency
    const INF = 1e9;
    const dist = Array.from({ length: n }, (_, i) =>
      Array.from({ length: n }, (_, j) => (i === j ? 0 : INF))
    );
    for (const e of model.edges) {
      const s = idx.get(e.source)!;
      const t = idx.get(e.target)!;
      dist[s][t] = dist[t][s] = 1;
    }
    for (let k = 0; k < n; k++)
      for (let i = 0; i < n; i++)
        for (let j = 0; j < n; j++)
          if (dist[i][k] + dist[k][j] < dist[i][j])
            dist[i][j] = dist[i][k] + dist[k][j];

    for (const center of ['n0', 'n13', 'n42']) {
      const c = idx.get(center)!;
      for (const depth of [1, 2, 3]) {
        const expected = model.nodes
          .filter((_, j) => dist[c][j] <= depth)
          .map(nd => nd.id);
        const got = localGraph(model, center, depth).nodes.map(nd => nd.id);
        expect(got).toEqual(expected);
      }
    }
  });

  test('edges are the ones between kept nodes; unknown centre is empty', () => {
    const g = buildGraph(fixture);
    const l = localGraph(g, 'a', 1);
    expect(l.nodes.map(n => n.id)).toEqual(['a', 'b', 'c', 'tag:t1']);
    expect(l.edges).toEqual(
      g.edges.filter(e => l.nodes.some(n => n.id === e.source) && l.nodes.some(n => n.id === e.target))
    );
    expect(localGraph(g, 'nope', 2)).toEqual({ nodes: [], edges: [] });
  });
});

describe('force layout', () => {
  const model = buildGraph(randomInput(80, 120, 3));

  test('is deterministic for a seed and stays inside the box', () => {
    const a = forceLayout(model, { width: 400, height: 300, seed: 5 });
    const b = forceLayout(model, { width: 400, height: 300, seed: 5 });
    expect(Array.from(a)).toEqual(Array.from(b));
    expect(a.size).toBe(model.nodes.length);
    for (const { x, y } of a.values()) {
      expect(x).toBeGreaterThanOrEqual(0);
      expect(x).toBeLessThanOrEqual(400);
      expect(y).toBeGreaterThanOrEqual(0);
      expect(y).toBeLessThanOrEqual(300);
    }
    const c = forceLayout(model, { width: 400, height: 300, seed: 6 });
    expect(Array.from(c)).not.toEqual(Array.from(a));
  });

  test('linked nodes end up closer than unrelated ones', () => {
    const p = forceLayout(model, { width: 1000, height: 1000, seed: 1 });
    const d = (u: string, v: string) => {
      const a = p.get(u)!;
      const b = p.get(v)!;
      return Math.hypot(a.x - b.x, a.y - b.y);
    };
    const linked =
      model.edges.reduce((s, e) => s + d(e.source, e.target), 0) /
      model.edges.length;
    let all = 0;
    let cnt = 0;
    for (let i = 0; i < model.nodes.length; i += 3)
      for (let j = i + 1; j < model.nodes.length; j += 5) {
        all += d(model.nodes[i].id, model.nodes[j].id);
        cnt++;
      }
    expect(linked).toBeLessThan(all / cnt);
  });

  test('A4.2 smoke: 2,000 nodes / 6,000 edges lay out well inside 3 s on CI', () => {
    const big: GraphModel = buildGraph(randomInput(2000, 6000, 11));
    const t0 = performance.now();
    const p = forceLayout(big, { seed: 1 });
    const ms = performance.now() - t0;
    expect(p.size).toBe(big.nodes.length);
    expect(ms).toBeLessThan(3000);
  });
});
