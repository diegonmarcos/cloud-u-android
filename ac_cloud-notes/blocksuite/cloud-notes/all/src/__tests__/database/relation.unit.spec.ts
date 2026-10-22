import {
  addProperty,
  databaseBlockProperties,
  StoreRelationSpace,
  updateCell,
} from '@blocksuite/cloud-notes-block-database';
import {
  type DatabaseBlockModel,
  DatabaseBlockSchemaExtension,
  NoteBlockSchemaExtension,
  ParagraphBlockSchemaExtension,
  RootBlockSchemaExtension,
} from '@blocksuite/cloud-notes-model';
import {
  ensureMirror,
  findMirror,
  relationSet,
  relationValue,
  rollupValue,
} from '@blocksuite/data-view';
import { Text } from '@blocksuite/store';
import {
  createAutoIncrementIdGenerator,
  TestWorkspace,
} from '@blocksuite/store/test';
import { describe, expect, test } from 'vitest';

const extensions = [
  RootBlockSchemaExtension,
  NoteBlockSchemaExtension,
  ParagraphBlockSchemaExtension,
  DatabaseBlockSchemaExtension,
];

function setup() {
  const collection = new TestWorkspace({
    id: 'relations',
    idGenerator: createAutoIncrementIdGenerator(),
  });
  collection.meta.initialize();
  const doc = collection.createDoc('doc0');
  doc.load();
  const store = doc.getStore({ extensions });
  const root = store.addBlock('cloud-notes:page', { title: new Text('relations') });
  const note = store.addBlock('cloud-notes:note', {}, root);
  const mkDb = (title: string) =>
    store.addBlock(
      'cloud-notes:database',
      { columns: [], title: new Text(title) },
      note
    );
  const projectsId = mkDb('Projects');
  const tasksId = mkDb('Tasks');
  const projects = store.getModelById(projectsId) as DatabaseBlockModel;
  const tasks = store.getModelById(tasksId) as DatabaseBlockModel;
  const row = (dbId: string, title: string) =>
    store.addBlock('cloud-notes:paragraph', { text: new Text(title) }, dbId);

  const budget = addProperty(
    projects,
    'end',
    databaseBlockProperties.numberColumnConfig.create('Budget')
  );
  const p1 = row(projectsId, 'Alpha');
  const p2 = row(projectsId, 'Beta');
  updateCell(projects, p1, { columnId: budget, value: 100 });
  updateCell(projects, p2, { columnId: budget, value: 50 });

  const project = addProperty(
    tasks,
    'end',
    databaseBlockProperties.relationColumnConfig.create('Project', {
      targetDatabaseId: projectsId,
    })
  );
  const total = addProperty(
    tasks,
    'end',
    databaseBlockProperties.rollupColumnConfig.create('Total', {
      relationPropertyId: project,
      targetPropertyId: budget,
      fn: 'sum',
    })
  );
  const t1 = row(tasksId, 'Write');
  const t2 = row(tasksId, 'Ship');
  return {
    store,
    space: new StoreRelationSpace(store),
    projectsId,
    tasksId,
    projects,
    budget,
    project,
    total,
    p1,
    p2,
    t1,
    t2,
  };
}

describe('relations and rollups over real database blocks', () => {
  test('the space sees both databases, their rows and titles', () => {
    const s = setup();
    expect(s.space.databases().map(d => d.name).sort()).toEqual(['Projects', 'Tasks']);
    expect(s.space.rowIds(s.projectsId)).toEqual([s.p1, s.p2]);
    expect(s.space.rowTitle(s.projectsId, s.p1)).toBe('Alpha');
  });

  test('A5.4 the back relation shows the linking row, and editing it edits the forward cells', () => {
    const s = setup();
    relationSet(s.space, s.tasksId, s.t1, s.project, [s.p1]);
    expect(relationValue(s.space, s.tasksId, s.t1, s.project)).toEqual([s.p1]);

    const mirror = ensureMirror(s.space, s.tasksId, s.project)!;
    expect(mirror.databaseId).toBe(s.projectsId);
    expect(findMirror(s.space, s.tasksId, s.project)).toEqual(mirror);
    expect(s.projects.props.columns.some(c => c.id === mirror.propertyId && c.type === 'relation')).toBe(true);
    expect(relationValue(s.space, s.projectsId, s.p1, mirror.propertyId)).toEqual([s.t1]);

    relationSet(s.space, s.projectsId, s.p2, mirror.propertyId, [s.t1, s.t2]);
    expect(relationValue(s.space, s.tasksId, s.t1, s.project)).toEqual([s.p1, s.p2]);
    expect(relationValue(s.space, s.tasksId, s.t2, s.project)).toEqual([s.p2]);
  });

  test('A5.5 sum(budget) follows the related rows and their values', () => {
    const s = setup();
    relationSet(s.space, s.tasksId, s.t1, s.project, [s.p1, s.p2]);
    expect(rollupValue(s.space, s.tasksId, s.t1, s.total)).toBe(150);
    updateCell(s.projects, s.p2, { columnId: s.budget, value: 80 });
    expect(rollupValue(s.space, s.tasksId, s.t1, s.total)).toBe(180);
    expect(rollupValue(s.space, s.tasksId, s.t2, s.total)).toBe(0);
  });

  test('deleting a related row removes it from the relation and the rollup', () => {
    const s = setup();
    relationSet(s.space, s.tasksId, s.t1, s.project, [s.p1, s.p2]);
    s.store.deleteBlock(s.store.getModelById(s.p2)!);
    expect(relationValue(s.space, s.tasksId, s.t1, s.project)).toEqual([s.p1]);
    expect(rollupValue(s.space, s.tasksId, s.t1, s.total)).toBe(100);
  });
});
