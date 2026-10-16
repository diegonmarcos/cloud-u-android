import {
  copyAsSynced,
  insertSyncedCopy,
  pasteAsSynced,
  syncBlockToDoc,
  SyncedBlocksExtension,
  syncedClipboard,
} from '@blocksuite/affine-block-paragraph';
import {
  NoteBlockSchemaExtension,
  ParagraphBlockSchemaExtension,
  RootBlockSchemaExtension,
} from '@blocksuite/affine-model';
import { Text } from '@blocksuite/store';
import {
  createAutoIncrementIdGenerator,
  TestWorkspace,
} from '@blocksuite/store/test';
import { beforeEach, describe, expect, test } from 'vitest';

const extensions = [
  RootBlockSchemaExtension,
  NoteBlockSchemaExtension,
  ParagraphBlockSchemaExtension,
  SyncedBlocksExtension,
];

function setup() {
  const ws = new TestWorkspace({
    id: 'synced-ui',
    idGenerator: createAutoIncrementIdGenerator(),
  });
  ws.meta.initialize();
  const mk = (id: string, text: string) => {
    const doc = ws.createDoc(id);
    doc.load();
    const store = doc.getStore({ id, extensions });
    const root = store.addBlock('affine:page', { title: new Text(id) });
    const note = store.addBlock('affine:note', {}, root);
    const paragraph = store.addBlock(
      'affine:paragraph',
      { text: new Text(text), type: 'h2' },
      note
    );
    store.load();
    return { store, root, note, paragraph };
  };
  const text = (store: any, id: string) =>
    (store.getModelById(id) as any).props.text as Text;
  return { ws, mk, text };
}

describe('A5.6 creating synced blocks (slash, toolbar, paste)', () => {
  beforeEach(() => syncedClipboard.set(null));

  test('copy as synced then paste as synced makes a live, same-type copy', () => {
    const { mk, text } = setup();
    const a = mk('docA', 'Shared');
    const b = mk('docB', 'Other');
    expect(copyAsSynced(a.store, a.paragraph)).toEqual({
      docId: 'docA',
      blockId: a.paragraph,
    });
    const id = pasteAsSynced(b.store, b.paragraph)!;
    expect(id).toBeTruthy();
    const copy = b.store.getModelById(id) as any;
    expect(copy.props.type).toBe('h2');
    expect(text(b.store, id).toString()).toBe('Shared');
    // pasted right after the block it was pasted on
    const kids = b.store.getModelById(b.note)!.children.map((c: any) => c.id);
    expect(kids).toEqual([b.paragraph, id]);
    text(b.store, id).insert('!', 6);
    expect(text(a.store, a.paragraph).toString()).toBe('Shared!');
    text(a.store, a.paragraph).insert('?', 7);
    expect(text(b.store, id).toString()).toBe('Shared!?');
  });

  test('paste with nothing copied does nothing', () => {
    const { mk } = setup();
    const b = mk('docB', 'Other');
    expect(pasteAsSynced(b.store, b.paragraph)).toBeNull();
  });

  test('copying a synced copy points at the original, not the copy', () => {
    const { mk } = setup();
    const a = mk('docA', 'x');
    const b = mk('docB', 'y');
    const c = mk('docC', 'z');
    copyAsSynced(a.store, a.paragraph);
    const inB = pasteAsSynced(b.store, b.paragraph)!;
    expect(copyAsSynced(b.store, inB)).toEqual({
      docId: 'docA',
      blockId: a.paragraph,
    });
    expect(pasteAsSynced(c.store, c.paragraph)).toBeTruthy();
  });

  test('sync to another doc appends a live copy in its first note', () => {
    const { mk, text } = setup();
    const a = mk('docA', 'Fact');
    const b = mk('docB', 'Notes');
    const id = syncBlockToDoc(a.store, a.paragraph, b.store)!;
    const last = b.store.getModelById(b.note)!.children.at(-1) as any;
    expect(last.id).toBe(id);
    text(a.store, a.paragraph).insert('s', 4);
    expect(text(b.store, id).toString()).toBe('Facts');
  });

  test('a source that is gone cannot be pasted', () => {
    const { mk } = setup();
    const a = mk('docA', 'x');
    const b = mk('docB', 'y');
    expect(
      insertSyncedCopy(
        b.store,
        { docId: 'docA', blockId: 'missing' },
        { parentId: b.note }
      )
    ).toBeNull();
    expect(copyAsSynced(a.store, a.root)).toBeNull();
  });
});
