export interface BoardCandidate {
  id: string;
  title?: string;
  trash?: boolean;
  updatedDate?: number;
  /** the doc's primary mode: 'edgeless' is a board (canvas), 'page' a page */
  mode: string;
}

export interface Board {
  id: string;
  title: string;
}

/** Canvas > Boards lists exactly the live edgeless docs, newest edit first (A3.1). */
export function boardsOf(docs: BoardCandidate[]): Board[] {
  return docs
    .filter(d => d.mode === 'edgeless' && !d.trash)
    .sort((a, b) => (b.updatedDate ?? 0) - (a.updatedDate ?? 0))
    .map(d => ({ id: d.id, title: d.title?.trim() || 'Untitled board' }));
}
