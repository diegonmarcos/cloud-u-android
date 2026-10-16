import { describe, expect, test } from 'vitest';

import { boardsOf } from './boards';

describe('A3.1 Canvas > Boards lists exactly the edgeless docs', () => {
  test('edgeless only, not trashed, newest first, titled', () => {
    expect(
      boardsOf([
        { id: 'p1', title: 'A page', mode: 'page', updatedDate: 9 },
        { id: 'b1', title: 'Old board', mode: 'edgeless', updatedDate: 1 },
        { id: 'b2', title: ' ', mode: 'edgeless', updatedDate: 5 },
        { id: 'b3', title: 'Gone', mode: 'edgeless', trash: true, updatedDate: 8 },
        { id: 'b4', mode: 'edgeless', updatedDate: 3 },
      ])
    ).toEqual([
      { id: 'b2', title: 'Untitled board' },
      { id: 'b4', title: 'Untitled board' },
      { id: 'b1', title: 'Old board' },
    ]);
  });

  test('no boards gives an empty list, not an error', () => {
    expect(boardsOf([])).toEqual([]);
    expect(boardsOf([{ id: 'p', mode: 'page' }])).toEqual([]);
  });
});
