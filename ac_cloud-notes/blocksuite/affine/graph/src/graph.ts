export type GraphNodeKind = 'doc' | 'tag';

export interface GraphNode {
  /** doc id, or `tag:<tagId>` for a tag node */
  id: string;
  kind: GraphNodeKind;
  label: string;
  /** true for a doc with no edge at all */
  orphan: boolean;
}

export interface GraphEdge {
  source: string;
  target: string;
  kind: 'link' | 'tag';
}

export interface GraphModel {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface GraphDocInput {
  id: string;
  title?: string;
  trash?: boolean;
}

export interface GraphTagInput {
  id: string;
  name: string;
  docIds: string[];
}

export interface GraphInput {
  docs: GraphDocInput[];
  /** doc `from` links to doc `to` (one entry per link is enough; repeats are merged) */
  links: { from: string; to: string }[];
  tags?: GraphTagInput[];
}

export const tagNodeId = (tagId: string) => `tag:${tagId}`;

const byId = (a: { id: string }, b: { id: string }) =>
  a.id < b.id ? -1 : a.id > b.id ? 1 : 0;

/**
 * The graph of a workspace. Nodes are the live (not trashed) docs and the tags
 * that tag at least one live doc; edges are doc -> doc links and tag -> doc
 * membership. Self links, links to unknown or trashed docs and duplicates are
 * dropped. Everything is sorted, so the result is a stable golden value.
 */
export function buildGraph(input: GraphInput): GraphModel {
  const live = new Map<string, GraphDocInput>();
  for (const d of input.docs) if (!d.trash) live.set(d.id, d);

  const edgeKeys = new Set<string>();
  const edges: GraphEdge[] = [];
  const add = (source: string, target: string, kind: GraphEdge['kind']) => {
    const key = `${kind}\u0000${source}\u0000${target}`;
    if (edgeKeys.has(key)) return;
    edgeKeys.add(key);
    edges.push({ source, target, kind });
  };

  for (const l of input.links) {
    if (l.from === l.to || !live.has(l.from) || !live.has(l.to)) continue;
    add(l.from, l.to, 'link');
  }

  const tagNodes: GraphNode[] = [];
  for (const t of input.tags ?? []) {
    const members = Array.from(new Set(t.docIds)).filter(id => live.has(id));
    if (members.length === 0) continue;
    const id = tagNodeId(t.id);
    tagNodes.push({ id, kind: 'tag', label: t.name, orphan: false });
    for (const m of members) add(id, m, 'tag');
  }

  const degree = new Set<string>();
  for (const e of edges) {
    degree.add(e.source);
    degree.add(e.target);
  }
  const docNodes: GraphNode[] = Array.from(live.values()).map(d => ({
    id: d.id,
    kind: 'doc',
    label: d.title?.trim() || 'Untitled',
    orphan: !degree.has(d.id),
  }));

  edges.sort(
    (a, b) =>
      (a.source < b.source ? -1 : a.source > b.source ? 1 : 0) ||
      (a.target < b.target ? -1 : a.target > b.target ? 1 : 0) ||
      (a.kind < b.kind ? -1 : a.kind > b.kind ? 1 : 0)
  );
  return { nodes: [...docNodes, ...tagNodes].sort(byId), edges };
}

/** Doc nodes with no edge at all. */
export function orphans(model: GraphModel): GraphNode[] {
  return model.nodes.filter(n => n.kind === 'doc' && n.orphan);
}

/** Node id -> ids of its neighbours (edges taken as undirected). */
export function adjacency(model: GraphModel): Map<string, Set<string>> {
  const adj = new Map<string, Set<string>>();
  for (const n of model.nodes) adj.set(n.id, new Set());
  for (const e of model.edges) {
    adj.get(e.source)?.add(e.target);
    adj.get(e.target)?.add(e.source);
  }
  return adj;
}

/**
 * The neighbourhood of `centerId` within `depth` hops (undirected BFS), with
 * every edge between the kept nodes. An unknown centre gives the empty graph.
 */
export function localGraph(
  model: GraphModel,
  centerId: string,
  depth: number
): GraphModel {
  const adj = adjacency(model);
  if (!adj.has(centerId)) return { nodes: [], edges: [] };
  const seen = new Set<string>([centerId]);
  let frontier = [centerId];
  for (let d = 0; d < depth && frontier.length > 0; d++) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const n of adj.get(id) ?? []) {
        if (!seen.has(n)) {
          seen.add(n);
          next.push(n);
        }
      }
    }
    frontier = next;
  }
  return {
    nodes: model.nodes.filter(n => seen.has(n.id)),
    edges: model.edges.filter(e => seen.has(e.source) && seen.has(e.target)),
  };
}

/** Docs that link to `docId` (the backlinks list), by id order. */
export function backlinks(model: GraphModel, docId: string): GraphNode[] {
  const from = new Set(
    model.edges
      .filter(e => e.kind === 'link' && e.target === docId)
      .map(e => e.source)
  );
  return model.nodes.filter(n => from.has(n.id));
}

/** Keep only nodes matching `keep`, and the edges between them. */
export function filterGraph(
  model: GraphModel,
  keep: (n: GraphNode) => boolean
): GraphModel {
  const ids = new Set(model.nodes.filter(keep).map(n => n.id));
  return {
    nodes: model.nodes.filter(n => ids.has(n.id)),
    edges: model.edges.filter(e => ids.has(e.source) && ids.has(e.target)),
  };
}
