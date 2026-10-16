import {
  ANCHOR_PROBE_OFFSET,
  anchorProbePoints,
  getAnchors,
  MAGNETIC_ANCHOR_DISTANCE,
} from '@blocksuite/affine-gfx-connector';
import { Bound, PointLocation } from '@blocksuite/global/gfx';
import { describe, expect, test } from 'vitest';

/** A rectangle with the one method getAnchors asks of an element: where a line from `from` to `to` meets the outline. */
function rect(x: number, y: number, w: number, h: number) {
  const cx = x + w / 2;
  const cy = y + h / 2;
  return {
    xywh: `[${x},${y},${w},${h}]`,
    rotate: 0,
    getLineIntersections(from: number[], to: number[]) {
      const dx = to[0] - from[0];
      const dy = to[1] - from[1];
      const k = Math.max(Math.abs(dx) / (w / 2), Math.abs(dy) / (h / 2));
      return [new PointLocation([cx + dx / k, cy + dy / k])];
    },
  } as any;
}

describe('magnetic connector anchors', () => {
  test('eight anchors: the four side middles, then the four corners', () => {
    const el = rect(10, 20, 100, 60);
    const coords = getAnchors(el).map(a => a.coord.map(v => Math.round(v * 1000) / 1000 + 0));
    expect(coords).toEqual([
      [0.5, 0],
      [0.5, 1],
      [0, 0.5],
      [1, 0.5],
      [0, 0],
      [1, 0],
      [0, 1],
      [1, 1],
    ]);
    expect(anchorProbePoints(Bound.deserialize(el.xywh))).toHaveLength(8);
  });

  test('A3.2 an endpoint stays on the same anchor when the shape is moved or resized (0.5 px)', () => {
    const before = rect(10, 20, 100, 60);
    const near = (a: { coord: number[] }, cx: number, cy: number) =>
      Math.abs(a.coord[0] - cx) < 1e-6 && Math.abs(a.coord[1] - cy) < 1e-6;
    const anchor = getAnchors(before).find(a => near(a, 1, 0))!; // top-right corner
    const [rx, ry] = anchor.coord;

    for (const next of [rect(300, -40, 100, 60), rect(10, 20, 240, 90), rect(-5, 7, 33, 21)]) {
      const b = Bound.deserialize(next.xywh);
      // where the connector end is drawn after the change: the stored relative coordinate on the new bound
      const drawn = [b.x + rx * b.w, b.y + ry * b.h];
      const expected = getAnchors(next).find(a => near(a, 1, 0))!.point;
      expect(Math.abs(drawn[0] - expected[0])).toBeLessThan(0.5);
      expect(Math.abs(drawn[1] - expected[1])).toBeLessThan(0.5);
    }
  });

  test('the snap pulls from further than the old 8 px, and the probes sit outside the shape', () => {
    expect(MAGNETIC_ANCHOR_DISTANCE).toBeGreaterThan(8);
    const b = Bound.deserialize('[0,0,100,100]');
    for (const [x, y] of anchorProbePoints(b)) {
      expect(x < 0 || x > 100 || y < 0 || y > 100).toBe(true);
    }
    expect(ANCHOR_PROBE_OFFSET).toBeGreaterThan(0);
  });
});
