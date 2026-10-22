import { DatabaseBlockModel } from '@blocksuite/cloud-notes-model';
import type { Point } from '@blocksuite/global/gfx';
import { BLOCK_ID_ATTR } from '@blocksuite/std';
import type { BlockModel } from '@blocksuite/store';

import { getRectByBlockComponent } from '../dom/index.js';
import { matchModels } from '../model/index.js';
import { DropFlags } from './types.js';

const ATTR_SELECTOR = `[${BLOCK_ID_ATTR}]`;

/**
 * Gets the drop rect by block and point.
 */
export function getDropRectByPoint(
  point: Point,
  model: BlockModel,
  element: Element
): null | {
  rect: DOMRect;
  flag: DropFlags;
} {
  const result = {
    rect: getRectByBlockComponent(element),
    flag: DropFlags.Normal,
  };

  const isDatabase = matchModels(model, [DatabaseBlockModel]);

  if (isDatabase) {
    const table = getDatabaseBlockTableElement(element);
    if (!table) {
      return result;
    }

    let bounds = table.getBoundingClientRect();
    if (model.children.length === 0) {
      result.flag = DropFlags.EmptyDatabase;

      if (point.y < bounds.top) return result;

      const header = getDatabaseBlockColumnHeaderElement(element);
      if (!header) {
        return null;
      }

      bounds = header.getBoundingClientRect();
      result.rect = new DOMRect(
        result.rect.left,
        bounds.bottom,
        result.rect.width,
        1
      );

      return result;
    }

    result.flag = DropFlags.Database;
    const rows = getDatabaseBlockRowsElement(element);
    if (!rows) {
      return null;
    }
    const rowsBounds = rows.getBoundingClientRect();

    if (point.y < rowsBounds.top || point.y > rowsBounds.bottom) return result;

    const elements = document.elementsFromPoint(point.x, point.y);
    const len = elements.length;
    let e;
    let i = 0;
    for (; i < len; i++) {
      e = elements[i];

      if (e.classList.contains('cloud-notes-database-block-row-cell-content')) {
        const cellRect = getCellRect(e, bounds);
        if (!cellRect) {
          return null;
        }
        result.rect = cellRect;
        return result;
      }

      if (e.classList.contains('cloud-notes-database-block-row')) {
        e = e.querySelector(ATTR_SELECTOR);
        if (!e) {
          return null;
        }
        const cellRect = getCellRect(e, bounds);
        if (!cellRect) {
          return null;
        }
        result.rect = cellRect;
        return result;
      }
    }

    return result;
  }

  const parent = element.parentElement;
  if (parent?.classList.contains('cloud-notes-database-block-row-cell-content')) {
    result.flag = DropFlags.Database;
    const cellRect = getCellRect(parent);
    if (!cellRect) {
      return null;
    }
    result.rect = cellRect;
    return result;
  }

  return result;
}

/**
 * Gets the table of the database.
 */
function getDatabaseBlockTableElement(element: Element) {
  return element.querySelector('.cloud-notes-database-block-table');
}

/**
 * Gets the column header of the database.
 */
function getDatabaseBlockColumnHeaderElement(element: Element) {
  return element.querySelector('.cloud-notes-database-column-header');
}

/**
 * Gets the rows of the database.
 */
function getDatabaseBlockRowsElement(element: Element) {
  return element.querySelector('.cloud-notes-database-block-rows');
}

function getCellRect(element: Element, bounds?: DOMRect) {
  if (!bounds) {
    const table = element.closest('.cloud-notes-database-block-table');
    if (!table) {
      return null;
    }
    bounds = table.getBoundingClientRect();
  }
  // cloud-notes-database-block-row-cell
  const col = element.parentElement;
  if (!col) {
    return null;
  }
  // cloud-notes-database-block-row
  const row = col.parentElement;
  if (!row) {
    return null;
  }
  const colRect = col.getBoundingClientRect();
  return new DOMRect(
    bounds.left,
    colRect.top,
    colRect.right - bounds.left,
    colRect.height
  );
}
