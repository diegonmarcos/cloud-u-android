// JSON Canvas 1.0 (https://jsoncanvas.org) reader / writer / editor operations.
// Pure: no DOM, no dependencies. Unknown fields are preserved byte-for-meaning so a
// canvas written by Obsidian round-trips through Cloud Notes without losing anything.

export type CanvasSide = 'top' | 'right' | 'bottom' | 'left';
export type CanvasNodeType = 'text' | 'file' | 'link' | 'group';

export interface CanvasNode {
  id: string;
  type: CanvasNodeType;
  x: number;
  y: number;
  width: number;
  height: number;
  /** "1".."6" preset or "#rrggbb" */
  color?: string;
  text?: string;
  file?: string;
  subpath?: string;
  url?: string;
  label?: string;
  background?: string;
  backgroundStyle?: 'cover' | 'ratio' | 'repeat';
  [extra: string]: unknown;
}

export interface CanvasEdge {
  id: string;
  fromNode: string;
  toNode: string;
  fromSide?: CanvasSide;
  toSide?: CanvasSide;
  fromEnd?: 'none' | 'arrow';
  toEnd?: 'none' | 'arrow';
  color?: string;
  label?: string;
  [extra: string]: unknown;
}

export interface JsonCanvas {
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  [extra: string]: unknown;
}

export interface CanvasParseResult {
  canvas: JsonCanvas;
  /** human-readable problems; the canvas is still usable (bad entries are dropped) */
  errors: string[];
}

const TYPES: CanvasNodeType[] = ['text', 'file', 'link', 'group'];
const SIDES: string[] = ['top', 'right', 'bottom', 'left'];

const isObj = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

export function emptyCanvas(): JsonCanvas {
  return { nodes: [], edges: [] };
}

export function parseCanvas(source: string): CanvasParseResult {
  const errors: string[] = [];
  let raw: unknown;
  if (source.trim() === '') return { canvas: emptyCanvas(), errors };
  try {
    raw = JSON.parse(source);
  } catch (e) {
    return {
      canvas: emptyCanvas(),
      errors: ['not valid JSON: ' + (e instanceof Error ? e.message : String(e))],
    };
  }
  if (!isObj(raw)) {
    return { canvas: emptyCanvas(), errors: ['top level must be an object'] };
  }
  const canvas: JsonCanvas = { ...raw, nodes: [], edges: [] };
  const ids = new Set<string>();
  const rawNodes = Array.isArray(raw.nodes) ? raw.nodes : [];
  if (raw.nodes !== undefined && !Array.isArray(raw.nodes)) errors.push('"nodes" must be an array');
  rawNodes.forEach((n, i) => {
    if (!isObj(n)) return void errors.push(`node ${i}: not an object`);
    const id = n.id;
    if (typeof id !== 'string' || id === '') return void errors.push(`node ${i}: missing id`);
    if (ids.has(id)) return void errors.push(`node ${id}: duplicate id`);
    if (!TYPES.includes(n.type as CanvasNodeType)) {
      return void errors.push(`node ${id}: unknown type ${String(n.type)}`);
    }
    for (const k of ['x', 'y', 'width', 'height']) {
      if (typeof n[k] !== 'number' || !Number.isFinite(n[k])) {
        return void errors.push(`node ${id}: ${k} must be a number`);
      }
    }
    ids.add(id);
    canvas.nodes.push({ ...n } as CanvasNode);
  });
  const edgeIds = new Set<string>();
  const rawEdges = Array.isArray(raw.edges) ? raw.edges : [];
  if (raw.edges !== undefined && !Array.isArray(raw.edges)) errors.push('"edges" must be an array');
  rawEdges.forEach((e, i) => {
    if (!isObj(e)) return void errors.push(`edge ${i}: not an object`);
    const id = e.id;
    if (typeof id !== 'string' || id === '') return void errors.push(`edge ${i}: missing id`);
    if (edgeIds.has(id)) return void errors.push(`edge ${id}: duplicate id`);
    if (typeof e.fromNode !== 'string' || !ids.has(e.fromNode)) {
      return void errors.push(`edge ${id}: fromNode does not exist`);
    }
    if (typeof e.toNode !== 'string' || !ids.has(e.toNode)) {
      return void errors.push(`edge ${id}: toNode does not exist`);
    }
    for (const k of ['fromSide', 'toSide']) {
      if (e[k] !== undefined && !SIDES.includes(e[k] as string)) {
        return void errors.push(`edge ${id}: bad ${k}`);
      }
    }
    edgeIds.add(id);
    canvas.edges.push({ ...e } as CanvasEdge);
  });
  return { canvas, errors };
}

/** Obsidian writes canvases with tab indentation; so do we, so diffs stay small. */
export function serializeCanvas(canvas: JsonCanvas): string {
  return JSON.stringify(canvas, null, '\t');
}

export function newId(existing: Iterable<string>): string {
  const used = new Set(existing);
  let id = '';
  do {
    id = Array.from({ length: 16 }, () => '0123456789abcdef'[Math.floor(Math.random() * 16)]).join('');
  } while (used.has(id));
  return id;
}

const allIds = (c: JsonCanvas) => [...c.nodes.map(n => n.id), ...c.edges.map(e => e.id)];

export function addTextNode(
  canvas: JsonCanvas,
  at: { x: number; y: number },
  text = '',
  size = { width: 250, height: 60 }
): { canvas: JsonCanvas; id: string } {
  const id = newId(allIds(canvas));
  const node: CanvasNode = { id, type: 'text', text, x: at.x, y: at.y, ...size };
  return { canvas: { ...canvas, nodes: [...canvas.nodes, node] }, id };
}

export function addGroup(
  canvas: JsonCanvas,
  rect: { x: number; y: number; width: number; height: number },
  label = ''
): { canvas: JsonCanvas; id: string } {
  const id = newId(allIds(canvas));
  const node: CanvasNode = { id, type: 'group', label, ...rect };
  // groups sit under their members: the spec orders z by array position
  return { canvas: { ...canvas, nodes: [node, ...canvas.nodes] }, id };
}

export function updateNode(canvas: JsonCanvas, id: string, patch: Partial<CanvasNode>): JsonCanvas {
  return { ...canvas, nodes: canvas.nodes.map(n => (n.id === id ? { ...n, ...patch } : n)) };
}

export function moveNode(canvas: JsonCanvas, id: string, dx: number, dy: number): JsonCanvas {
  const target = canvas.nodes.find(n => n.id === id);
  if (!target) return canvas;
  // dragging a group drags what it contains
  const moving = new Set([id, ...(target.type === 'group' ? membersOf(canvas, id).map(n => n.id) : [])]);
  return {
    ...canvas,
    nodes: canvas.nodes.map(n => (moving.has(n.id) ? { ...n, x: n.x + dx, y: n.y + dy } : n)),
  };
}

export function removeNode(canvas: JsonCanvas, id: string): JsonCanvas {
  return {
    ...canvas,
    nodes: canvas.nodes.filter(n => n.id !== id),
    edges: canvas.edges.filter(e => e.fromNode !== id && e.toNode !== id),
  };
}

export function addEdge(
  canvas: JsonCanvas,
  fromNode: string,
  toNode: string,
  extra: Partial<CanvasEdge> = {}
): { canvas: JsonCanvas; id: string | null } {
  const has = (id: string) => canvas.nodes.some(n => n.id === id);
  if (fromNode === toNode || !has(fromNode) || !has(toNode)) return { canvas, id: null };
  const id = newId(allIds(canvas));
  const edge: CanvasEdge = { id, fromNode, toNode, ...extra };
  return { canvas: { ...canvas, edges: [...canvas.edges, edge] }, id };
}

export function removeEdge(canvas: JsonCanvas, id: string): JsonCanvas {
  return { ...canvas, edges: canvas.edges.filter(e => e.id !== id) };
}

/** Nodes fully inside a group's rectangle (the spec has no explicit membership). */
export function membersOf(canvas: JsonCanvas, groupId: string): CanvasNode[] {
  const g = canvas.nodes.find(n => n.id === groupId && n.type === 'group');
  if (!g) return [];
  return canvas.nodes.filter(
    n =>
      n.id !== g.id &&
      n.x >= g.x &&
      n.y >= g.y &&
      n.x + n.width <= g.x + g.width &&
      n.y + n.height <= g.y + g.height
  );
}

export function bounds(canvas: JsonCanvas): { x: number; y: number; width: number; height: number } {
  if (canvas.nodes.length === 0) return { x: 0, y: 0, width: 0, height: 0 };
  const x0 = Math.min(...canvas.nodes.map(n => n.x));
  const y0 = Math.min(...canvas.nodes.map(n => n.y));
  const x1 = Math.max(...canvas.nodes.map(n => n.x + n.width));
  const y1 = Math.max(...canvas.nodes.map(n => n.y + n.height));
  return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}

/** The point on the node's border an edge attaches to. */
export function anchor(node: CanvasNode, side: CanvasSide | undefined, toward: { x: number; y: number }) {
  const cx = node.x + node.width / 2;
  const cy = node.y + node.height / 2;
  const s =
    side ??
    (Math.abs(toward.x - cx) * node.height > Math.abs(toward.y - cy) * node.width
      ? toward.x > cx
        ? 'right'
        : 'left'
      : toward.y > cy
        ? 'bottom'
        : 'top');
  switch (s) {
    case 'top':
      return { x: cx, y: node.y };
    case 'bottom':
      return { x: cx, y: node.y + node.height };
    case 'left':
      return { x: node.x, y: cy };
    default:
      return { x: node.x + node.width, y: cy };
  }
}

/** The Obsidian colour presets (preset "1".."6") as CSS. */
export const CANVAS_PRESET_COLORS: Record<string, string> = {
  '1': '#fb464c',
  '2': '#e9973f',
  '3': '#e0de71',
  '4': '#44cf6e',
  '5': '#53dfdd',
  '6': '#a882ff',
};

export const canvasColor = (c: string | undefined): string | undefined =>
  c === undefined ? undefined : (CANVAS_PRESET_COLORS[c] ?? (/^#[0-9a-f]{3,8}$/i.test(c) ? c : undefined));
