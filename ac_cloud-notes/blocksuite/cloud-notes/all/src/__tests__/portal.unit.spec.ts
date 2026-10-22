import {
  EmbedSyncedDocBlockSchemaExtension,
  NoteBlockSchemaExtension,
  ParagraphBlockSchemaExtension,
  RootBlockSchemaExtension,
  defaultEmbedSyncedDocBlockProps,
  syncedDocStoreOptions,
} from '@blocksuite/cloud-notes-model';
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
  EmbedSyncedDocBlockSchemaExtension,
];

describe('A3.5 live doc-in-canvas portal', () => {
  test('store options: only a portal in a writable host is editable', () => {
    expect(syncedDocStoreOptions({}).readonly).toBe(true);
    expect(syncedDocStoreOptions({ portal: false }).readonly).toBe(true);
    expect(syncedDocStoreOptions({ portal: true }).readonly).toBe(false);
    expect(
      syncedDocStoreOptions({ portal: true, parentReadonly: true }).readonly
    ).toBe(true);
    const query = { mode: 'strict', match: [] } as any;
    expect(syncedDocStoreOptions({ query }).query).toBe(query);
    expect('query' in syncedDocStoreOptions({})).toBe(false);
  });

  test('the portal prop defaults off and is stored on the block', () => {
    expect(defaultEmbedSyncedDocBlockProps.portal).toBeUndefined();
    const ws = new TestWorkspace({
      id: 'portal',
      idGenerator: createAutoIncrementIdGenerator(),
    });
    ws.meta.initialize();
    const doc = ws.createDoc('host');
    doc.load();
    const store = doc.getStore({ id: 'host', extensions });
    const root = store.addBlock('cloud-notes:page', { title: new Text('host') });
    const id = store.addBlock('cloud-notes:embed-synced-doc', { pageId: 'src' }, root);
    store.load();
    const model = store.getModelById(id) as any;
    expect(model.props.portal).toBeFalsy();
    store.updateBlock(model, { portal: true });
    expect(model.props.portal).toBe(true);
  });

  test('a writable and a read-only store on one doc see the same edits', () => {
    const ws = new TestWorkspace({
      id: 'portal2',
      idGenerator: createAutoIncrementIdGenerator(),
    });
    ws.meta.initialize();
    const doc = ws.createDoc('src');
    doc.load();
    const writable = doc.getStore({
      id: 'w',
      extensions,
      ...syncedDocStoreOptions({ portal: true }),
    });
    const root = writable.addBlock('cloud-notes:page', { title: new Text('src') });
    const note = writable.addBlock('cloud-notes:note', {}, root);
    const p = writable.addBlock('cloud-notes:paragraph', { text: new Text('a') }, note);
    writable.load();
    const preview = doc.getStore({
      id: 'r',
      extensions,
      ...syncedDocStoreOptions({}),
    });
    preview.load();
    expect(writable.readonly).toBe(false);
    expect(preview.readonly).toBe(true);
    const text = (s: any) => (s.getModelById(p) as any).props.text as Text;
    text(writable).insert('b', 1);
    expect(text(preview).toString()).toBe('ab');
  });
});
