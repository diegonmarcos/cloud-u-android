import { describe, expect, it } from 'vitest';

import {
  computeRollup,
  ensureMirror,
  findMirror,
  type RelationColumn,
  type RelationSpace,
  relationSet,
  relationValue,
  rollupValue,
} from '../core/relation/index.js';

/** An in-memory workspace of databases: the same contract the database block implements over its Store. */
function makeSpace(init: {
  dbs: Record<
    string,
    {
      name: string;
      rows: Record<string, { title: string; cells?: Record<string, unknown> }>;
      columns: RelationColumn[];
    }
  >;
}): RelationSpace & { dbs: typeof init.dbs } {
  const dbs = structuredClone(init.dbs);
  let n = 0;
  return {
    dbs,
    addColumn: (db, c) => {
      const id = `col-${++n}`;
      dbs[db].columns.push({ id, ...c });
      return id;
    },
    databases: () => Object.entries(dbs).map(([id, d]) => ({ id, name: d.name })),
    rowIds: db => Object.keys(dbs[db]?.rows ?? {}),
    rowTitle: (db, row) => dbs[db]?.rows[row]?.title ?? '',
    columns: db => dbs[db]?.columns ?? [],
    rawCell: (db, row, prop) => dbs[db]?.rows[row]?.cells?.[prop],
    setRawCell: (db, row, prop, value) => {
      const r = dbs[db].rows[row];
      r.cells = { ...r.cells, [prop]: value };
    },
  };
}

const col = (id: string, type: string, data: Record<string, unknown> = {}): RelationColumn => ({
  id,
  name: id,
  type,
  data,
});

const fixture = () =>
  makeSpace({
    dbs: {
      tasks: {
        name: 'Tasks',
        rows: { t1: { title: 'Write' }, t2: { title: 'Ship' } },
        columns: [
          col('project', 'relation', { targetDatabaseId: 'projects' }),
          col('total', 'rollup', {}),
        ],
      },
      projects: {
        name: 'Projects',
        rows: {
          p1: { title: 'Alpha', cells: { budget: 100, done: true } },
          p2: { title: 'Beta', cells: { budget: 50, done: false } },
          p3: { title: 'Gamma', cells: { budget: 25 } },
        },
        columns: [
          col('budget', 'number'),
          col('done', 'checkbox'),
          col('tasks', 'relation', {
            targetDatabaseId: 'tasks',
            mirrorOf: { databaseId: 'tasks', propertyId: 'project' },
          }),
        ],
      },
    },
  });

describe('A5.4 relations are two-way', () => {
  it('linking t1 -> p1 shows t1 on the back relation of p1', () => {
    const s = fixture();
    relationSet(s, 'tasks', 't1', 'project', ['p1']);
    expect(relationValue(s, 'tasks', 't1', 'project')).toEqual(['p1']);
    expect(relationValue(s, 'projects', 'p1', 'tasks')).toEqual(['t1']);
    expect(relationValue(s, 'projects', 'p2', 'tasks')).toEqual([]);
  });

  it('editing the back relation edits the forward cells', () => {
    const s = fixture();
    relationSet(s, 'projects', 'p2', 'tasks', ['t1', 't2']);
    expect(relationValue(s, 'tasks', 't1', 'project')).toEqual(['p2']);
    expect(relationValue(s, 'tasks', 't2', 'project')).toEqual(['p2']);
    relationSet(s, 'projects', 'p2', 'tasks', ['t2']);
    expect(relationValue(s, 'tasks', 't1', 'project')).toEqual([]);
    expect(relationValue(s, 'tasks', 't2', 'project')).toEqual(['p2']);
  });

  it('a link to a deleted row disappears from both sides, and duplicates collapse', () => {
    const s = fixture();
    relationSet(s, 'tasks', 't1', 'project', ['p1', 'p1', 'p2']);
    expect(s.dbs.tasks.rows.t1.cells!.project).toEqual(['p1', 'p2']);
    delete s.dbs.projects.rows.p2;
    expect(relationValue(s, 'tasks', 't1', 'project')).toEqual(['p1']);
  });

  it('a property without a target, or an unknown property, relates to nothing', () => {
    const s = fixture();
    s.dbs.tasks.columns.push(col('loose', 'relation', {}));
    expect(relationValue(s, 'tasks', 't1', 'loose')).toEqual([]);
    expect(relationValue(s, 'tasks', 't1', 'nope')).toEqual([]);
  });
});

describe('A5.5 rollups follow the related rows', () => {
  const withRollup = (s: ReturnType<typeof fixture>, fn: any, target = 'budget') => {
    s.dbs.tasks.columns.find(c => c.id === 'total')!.data = {
      relationPropertyId: 'project',
      targetPropertyId: target,
      fn,
    };
  };

  it('sum(budget) updates when a related row\'s value changes', () => {
    const s = fixture();
    withRollup(s, 'sum');
    relationSet(s, 'tasks', 't1', 'project', ['p1', 'p2']);
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBe(150);
    s.setRawCell('projects', 'p2', 'budget', 80);
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBe(180);
    relationSet(s, 'tasks', 't1', 'project', ['p1']);
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBe(100);
  });

  it('count, average, min, max, percent checked', () => {
    const s = fixture();
    relationSet(s, 'tasks', 't1', 'project', ['p1', 'p2', 'p3']);
    withRollup(s, 'count');
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBe(3);
    withRollup(s, 'avg');
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBeCloseTo(175 / 3);
    withRollup(s, 'min');
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBe(25);
    withRollup(s, 'max');
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBe(100);
    withRollup(s, 'percent_checked', 'done');
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBeCloseTo(100 / 3);
  });

  it('rolls the related rows\' titles up with count_unique, and an empty relation gives 0 / null', () => {
    const s = fixture();
    withRollup(s, 'count_unique', 'title');
    relationSet(s, 'tasks', 't1', 'project', ['p1', 'p2']);
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBe(2);
    expect(rollupValue(s, 'tasks', 't2', 'total')).toBe(0);
    withRollup(s, 'avg');
    expect(rollupValue(s, 'tasks', 't2', 'total')).toBeNull();
  });

  it('is null until it is configured', () => {
    const s = fixture();
    expect(rollupValue(s, 'tasks', 't1', 'total')).toBeNull();
  });
});

describe('computeRollup', () => {
  it('ignores non-numbers for the numeric functions and counts empties for the percent ones', () => {
    const v = [1, 'x', null, 4, ''];
    expect(computeRollup('sum', v)).toBe(5);
    expect(computeRollup('count_values', v)).toBe(3);
    expect(computeRollup('percent_empty', v)).toBe(40);
    expect(computeRollup('percent_not_empty', v)).toBe(60);
    expect(computeRollup('min', ['a'])).toBeNull();
  });
});

describe('creating the back side of a relation', () => {
  it('adds a mirror column once, and the new back relation lists the linking rows (A5.4)', () => {
    const s = makeSpace({
      dbs: {
        tasks: {
          name: 'Tasks',
          rows: { t1: { title: 'Write' } },
          columns: [col('project', 'relation', { targetDatabaseId: 'projects' })],
        },
        projects: { name: 'Projects', rows: { p1: { title: 'Alpha' } }, columns: [] },
      },
    });
    expect(findMirror(s, 'tasks', 'project')).toBeUndefined();
    const mirror = ensureMirror(s, 'tasks', 'project')!;
    expect(mirror.databaseId).toBe('projects');
    expect(ensureMirror(s, 'tasks', 'project')).toEqual(mirror); // not duplicated
    expect(s.dbs.projects.columns).toHaveLength(1);
    expect(s.dbs.projects.columns[0].name).toBe('Tasks');

    relationSet(s, 'tasks', 't1', 'project', ['p1']);
    expect(relationValue(s, 'projects', 'p1', mirror.propertyId)).toEqual(['t1']);
  });

  it('does not mirror a mirror, an unconfigured relation, or a non-relation', () => {
    const s = fixture();
    expect(ensureMirror(s, 'projects', 'tasks')).toBeUndefined();
    expect(ensureMirror(s, 'tasks', 'total')).toBeUndefined();
    s.dbs.tasks.columns.push(col('loose', 'relation', {}));
    expect(ensureMirror(s, 'tasks', 'loose')).toBeUndefined();
  });
});
