import { PDFDocument } from 'pdf-lib';
import { describe, expect, it } from 'vitest';

import {
  buildDeckPdf,
  sortFramesForDeck,
} from '../extensions/export-manager/deck-pdf.js';

// a 1x1 PNG
const PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

describe('A3.3 frames to a PDF deck', () => {
  it('a 3-frame board gives a 3-page PDF with each page the size of its frame', async () => {
    const bytes = await buildDeckPdf([
      { pngDataUrl: PNG, width: 800, height: 450 },
      { pngDataUrl: PNG, width: 1024, height: 768 },
      { pngDataUrl: PNG, width: 300, height: 600 },
    ]);
    const pdf = await PDFDocument.load(bytes);
    expect(pdf.getPageCount()).toBe(3);
    expect(pdf.getPages().map(p => [p.getWidth(), p.getHeight()])).toEqual([
      [800, 450],
      [1024, 768],
      [300, 600],
    ]);
  });

  it('refuses an empty deck instead of writing an empty file', async () => {
    await expect(buildDeckPdf([])).rejects.toThrow(/at least one frame/);
  });

  it('frames are ordered by presentation index, not by creation or position', () => {
    const f = (id: string, presentationIndex?: string) => ({
      id,
      xywh: '[0,0,1,1]',
      props: { presentationIndex },
    });
    const sorted = sortFramesForDeck([f('c', 'a2'), f('a', 'a0'), f('b', 'a1')]);
    expect(sorted.map(x => x.id)).toEqual(['a', 'b', 'c']);
    // the input is not mutated
    const input = [f('x', 'b'), f('y', 'a')];
    sortFramesForDeck(input);
    expect(input.map(x => x.id)).toEqual(['x', 'y']);
  });
});
