import type { GraphModel } from './graph';

export interface LayoutOptions {
  width?: number;
  height?: number;
  iterations?: number;
  /** same seed + same model = same positions */
  seed?: number;
}

export type Positions = Map<string, { x: number; y: number }>;

/** mulberry32: tiny seeded PRNG so layouts are reproducible */
function prng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Fruchterman-Reingold with a spatial grid: repulsion only acts inside a
 * cutoff radius (2k), so a step costs ~O(n + m) instead of O(n^2). Positions
 * stay inside [0,width] x [0,height].
 */
export function forceLayout(
  model: GraphModel,
  options: LayoutOptions = {}
): Positions {
  const width = options.width ?? 1000;
  const height = options.height ?? 1000;
  const iterations = options.iterations ?? 60;
  const rand = prng(options.seed ?? 1);
  const n = model.nodes.length;
  const out: Positions = new Map();
  if (n === 0) return out;

  const index = new Map<string, number>();
  model.nodes.forEach((node, i) => index.set(node.id, i));
  const x = new Float64Array(n);
  const y = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    x[i] = rand() * width;
    y[i] = rand() * height;
  }
  const edges: [number, number][] = [];
  for (const e of model.edges) {
    const s = index.get(e.source);
    const t = index.get(e.target);
    if (s !== undefined && t !== undefined) edges.push([s, t]);
  }

  const k = Math.sqrt((width * height) / n) * 0.9;
  const cutoff = 2 * k;
  const dx = new Float64Array(n);
  const dy = new Float64Array(n);
  let temp = Math.min(width, height) / 8;
  const cool = temp / (iterations + 1);

  for (let it = 0; it < iterations; it++) {
    dx.fill(0);
    dy.fill(0);

    // grid of cutoff-sized cells
    const cols = Math.max(1, Math.ceil(width / cutoff));
    const rows = Math.max(1, Math.ceil(height / cutoff));
    const cells = new Map<number, number[]>();
    for (let i = 0; i < n; i++) {
      const cx = Math.min(cols - 1, Math.max(0, Math.floor(x[i] / cutoff)));
      const cy = Math.min(rows - 1, Math.max(0, Math.floor(y[i] / cutoff)));
      const key = cy * cols + cx;
      const cell = cells.get(key);
      if (cell) cell.push(i);
      else cells.set(key, [i]);
    }
    for (let i = 0; i < n; i++) {
      const cx = Math.min(cols - 1, Math.max(0, Math.floor(x[i] / cutoff)));
      const cy = Math.min(rows - 1, Math.max(0, Math.floor(y[i] / cutoff)));
      for (let oy = -1; oy <= 1; oy++) {
        for (let ox = -1; ox <= 1; ox++) {
          const gx = cx + ox;
          const gy = cy + oy;
          if (gx < 0 || gy < 0 || gx >= cols || gy >= rows) continue;
          const cell = cells.get(gy * cols + gx);
          if (!cell) continue;
          for (const j of cell) {
            if (j <= i) continue;
            let ddx = x[i] - x[j];
            let ddy = y[i] - y[j];
            let d2 = ddx * ddx + ddy * ddy;
            if (d2 > cutoff * cutoff) continue;
            if (d2 < 1e-6) {
              ddx = rand() - 0.5;
              ddy = rand() - 0.5;
              d2 = ddx * ddx + ddy * ddy + 1e-6;
            }
            const d = Math.sqrt(d2);
            const f = (k * k) / d; // repulsion
            const fx = (ddx / d) * f;
            const fy = (ddy / d) * f;
            dx[i] += fx;
            dy[i] += fy;
            dx[j] -= fx;
            dy[j] -= fy;
          }
        }
      }
    }
    for (const [s, t] of edges) {
      const ddx = x[s] - x[t];
      const ddy = y[s] - y[t];
      const d = Math.sqrt(ddx * ddx + ddy * ddy) || 1e-3;
      const f = (d * d) / k; // attraction
      const fx = (ddx / d) * f;
      const fy = (ddy / d) * f;
      dx[s] -= fx;
      dy[s] -= fy;
      dx[t] += fx;
      dy[t] += fy;
    }
    for (let i = 0; i < n; i++) {
      // gentle pull to the centre keeps disconnected components on screen
      dx[i] += (width / 2 - x[i]) * 0.02;
      dy[i] += (height / 2 - y[i]) * 0.02;
      const len = Math.sqrt(dx[i] * dx[i] + dy[i] * dy[i]) || 1e-3;
      const step = Math.min(len, temp);
      x[i] = Math.min(width, Math.max(0, x[i] + (dx[i] / len) * step));
      y[i] = Math.min(height, Math.max(0, y[i] + (dy[i] / len) * step));
    }
    temp -= cool;
  }

  model.nodes.forEach((node, i) => out.set(node.id, { x: x[i], y: y[i] }));
  return out;
}
