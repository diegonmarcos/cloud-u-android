import { computeRollup, type RollupFn } from './rollup.js';

export type RelationData = {
  /** the database whose rows this property points to */
  targetDatabaseId?: string;
  /**
   * Set on the BACK side of a two-way relation: it mirrors the forward property
   * `propertyId` of database `databaseId`. A mirror stores nothing; its value is computed, and
   * editing it edits the forward cells (so the two sides cannot disagree).
   */
  mirrorOf?: { databaseId: string; propertyId: string };
};

export type RollupData = {
  relationPropertyId?: string;
  targetPropertyId?: string;
  fn?: RollupFn;
};

export type RelationColumn = {
  id: string;
  name: string;
  type: string;
  data: Record<string, unknown>;
};

/** What the relation functions need from the workspace; the database block implements it over its Store. */
export interface RelationSpace {
  databases(): { id: string; name: string }[];
  rowIds(databaseId: string): string[];
  rowTitle(databaseId: string, rowId: string): string;
  columns(databaseId: string): RelationColumn[];
  /** the stored value of a cell, undefined when never set */
  rawCell(databaseId: string, rowId: string, propertyId: string): unknown;
  setRawCell(
    databaseId: string,
    rowId: string,
    propertyId: string,
    value: unknown
  ): void;
  /** add a column to a database, returning its id (needed to create the back side of a relation) */
  addColumn?(databaseId: string, column: Omit<RelationColumn, 'id'>): string;
}

const asIds = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];

const column = (space: RelationSpace, databaseId: string, propertyId: string) =>
  space.columns(databaseId).find(c => c.id === propertyId);

/** The rows `rowId` points to through the relation property, existing rows only, in the target's row order. */
export function relationValue(
  space: RelationSpace,
  databaseId: string,
  rowId: string,
  propertyId: string
): string[] {
  const col = column(space, databaseId, propertyId);
  if (!col) return [];
  const data = col.data as RelationData;
  const target = data.targetDatabaseId;
  if (!target) return [];
  const existing = new Set(space.rowIds(target));

  if (data.mirrorOf) {
    // rows of the other database whose forward cell holds THIS row
    const { databaseId: srcDb, propertyId: srcProp } = data.mirrorOf;
    return space
      .rowIds(srcDb)
      .filter(src => asIds(space.rawCell(srcDb, src, srcProp)).includes(rowId));
  }
  const stored = asIds(space.rawCell(databaseId, rowId, propertyId));
  return space.rowIds(target).filter(id => stored.includes(id) && existing.has(id));
}

/** Point `rowId` at `targetRowIds`. On a mirror property this edits the forward cells of the other database. */
export function relationSet(
  space: RelationSpace,
  databaseId: string,
  rowId: string,
  propertyId: string,
  targetRowIds: string[]
): void {
  const col = column(space, databaseId, propertyId);
  if (!col) return;
  const data = col.data as RelationData;
  const wanted = new Set(targetRowIds);
  if (data.mirrorOf) {
    const { databaseId: srcDb, propertyId: srcProp } = data.mirrorOf;
    for (const src of space.rowIds(srcDb)) {
      const cur = asIds(space.rawCell(srcDb, src, srcProp));
      const has = cur.includes(rowId);
      const should = wanted.has(src);
      if (has === should) continue;
      space.setRawCell(
        srcDb,
        src,
        srcProp,
        should ? [...cur, rowId] : cur.filter(id => id !== rowId)
      );
    }
    return;
  }
  space.setRawCell(databaseId, rowId, propertyId, [...new Set(targetRowIds)]);
}

/** The rollup of `rowId`: the chosen property's values over the rows its relation points to. */
export function rollupValue(
  space: RelationSpace,
  databaseId: string,
  rowId: string,
  propertyId: string
): number | null {
  const col = column(space, databaseId, propertyId);
  if (!col) return null;
  const data = col.data as RollupData;
  if (!data.relationPropertyId || !data.targetPropertyId) return null;
  const relation = column(space, databaseId, data.relationPropertyId);
  const target = (relation?.data as RelationData | undefined)?.targetDatabaseId;
  if (!relation || relation.type !== 'relation' || !target) return null;
  const related = relationValue(space, databaseId, rowId, relation.id);
  const values = related.map(id =>
    data.targetPropertyId === 'title'
      ? space.rowTitle(target, id)
      : space.rawCell(target, id, data.targetPropertyId!)
  );
  return computeRollup(data.fn ?? 'count', values);
}

/** The back-relation column of a forward relation, if one exists. */
export function findMirror(
  space: RelationSpace,
  databaseId: string,
  propertyId: string
): { databaseId: string; propertyId: string } | undefined {
  const target = (column(space, databaseId, propertyId)?.data as RelationData | undefined)
    ?.targetDatabaseId;
  if (!target) return undefined;
  const mirror = space
    .columns(target)
    .find(
      c =>
        c.type === 'relation' &&
        (c.data as RelationData).mirrorOf?.databaseId === databaseId &&
        (c.data as RelationData).mirrorOf?.propertyId === propertyId
    );
  return mirror ? { databaseId: target, propertyId: mirror.id } : undefined;
}

/**
 * Make a forward relation two-way: add the mirror column to the target database (once).
 * Returns the mirror, or undefined when the property is not a forward relation with a target
 * or the space cannot add columns.
 */
export function ensureMirror(
  space: RelationSpace,
  databaseId: string,
  propertyId: string
): { databaseId: string; propertyId: string } | undefined {
  const col = column(space, databaseId, propertyId);
  const data = col?.data as RelationData | undefined;
  if (!col || col.type !== 'relation' || !data?.targetDatabaseId || data.mirrorOf) {
    return undefined;
  }
  const existing = findMirror(space, databaseId, propertyId);
  if (existing) return existing;
  if (!space.addColumn) return undefined;
  const sourceName = space.databases().find(d => d.id === databaseId)?.name ?? 'Related';
  const id = space.addColumn(data.targetDatabaseId, {
    name: sourceName,
    type: 'relation',
    data: {
      targetDatabaseId: databaseId,
      mirrorOf: { databaseId, propertyId },
    } satisfies RelationData,
  });
  return { databaseId: data.targetDatabaseId, propertyId: id };
}
