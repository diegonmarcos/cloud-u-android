export type DeckPage = {
  /** a PNG data URL of one frame */
  pngDataUrl: string;
  /** the frame's size: the PDF page has exactly this size */
  width: number;
  height: number;
};

type FrameLike = {
  xywh: string;
  props: { presentationIndex?: string };
};

/** Frames in presentation order: the order of the frame navigator (A3.3: page order = frame order). */
export function sortFramesForDeck<T extends FrameLike>(frames: T[]): T[] {
  return [...frames].sort((a, b) => {
    const x = a.props.presentationIndex ?? '';
    const y = b.props.presentationIndex ?? '';
    return x < y ? -1 : x > y ? 1 : 0;
  });
}

/**
 * One PDF page per frame, in the given order, each page the size of its frame.
 * Returns the PDF as raw bytes (the caller decides: download, share sheet, test).
 */
export async function buildDeckPdf(pages: DeckPage[]): Promise<Uint8Array> {
  if (pages.length === 0) {
    throw new Error('A deck needs at least one frame.');
  }
  const PDFLib = await import('pdf-lib');
  const pdf = await PDFLib.PDFDocument.create();
  for (const p of pages) {
    const page = pdf.addPage([p.width, p.height]);
    const image = await pdf.embedPng(p.pngDataUrl);
    page.drawImage(image, { x: 0, y: 0, width: p.width, height: p.height });
  }
  return pdf.save();
}
