import { bindYTexts, SyncedBlocksExtension } from '@blocksuite/cloud-notes-block-paragraph';
import {
  NoteBlockSchemaExtension,
  ParagraphBlockSchemaExtension,
  RootBlockSchemaExtension,
} from '@blocksuite/cloud-notes-model';
import { Text } from '@blocksuite/store';
import {
  createAutoIncrementIdGenerator,
  TestWorkspace,
} from '@blocksuite/store/test';
import { describe, expect, test } from 'vitest';
import * as Y from 'yjs';

const extensions = [
  RootBlockSchemaExtension,
  NoteBlockSchemaExtension,
  ParagraphBlockSchemaExtension,
  SyncedBlocksExtension,
];

function twoDocs() {
  const ws = new TestWorkspace({
    id: 'synced',
    idGenerator: createAutoIncrementIdGenerator(),
  });
  ws.meta.initialize();
  const mk = (id: string, text: string) => {
    const doc = ws.createDoc(id);
    doc.load();
    const store = doc.getStore({ id, extensions });
    const root = store.addBlock('cloud-notes:page', { title: new Text(id) });
    const note = store.addBlock('cloud-notes:note', {}, root);
    const paragraph = store.addBlock('cloud-notes:paragraph', { text: new Text(text) }, note);
    store.load();
    return { store, paragraph };
  };
  const a = mk('docA', 'Hello');
  const b = mk('docB', 'Something else');
  const textOf = (d: { store: any; paragraph: string }) =>
    (d.store.getModelById(d.paragraph) as any).props.text as Text;
  return { a, b, textOf };
}

describe('A5.6 synced blocks', () => {
  test('the copy takes the source text, then edits flow both ways between two docs', () => {
    const { a, b, textOf } = twoDocs();
    b.store
      .get(SyncedBlocksExtension)
      .link(b.paragraph, { docId: 'docA', blockId: a.paragraph });
    expect(textOf(b).toString()).toBe('Hello');

    textOf(a).insert(' world', 5); // edited in page X ...
    expect(textOf(b).toString()).toBe('Hello world'); // ... appears in page Y
    textOf(b).insert('Oh, ', 0); // edited in page Y ...
    expect(textOf(a).toString()).toBe('Oh, Hello world'); // ... appears in page X
    textOf(b).delete(0, 4);
    expect(textOf(a).toString()).toBe('Hello world');
  });

  test('survives a restart: the link is stored in the doc and re-bound on load, source wins', () => {
    const { a, b, textOf } = twoDocs();
    const ext = b.store.get(SyncedBlocksExtension);
    ext.link(b.paragraph, { docId: 'docA', blockId: a.paragraph });

    b.store.dispose(); // the app is closed
    textOf(a).insert('!', 5); // the source is edited meanwhile
    const raw = () =>
      (
        b.store.workspace.getDoc('docB')!.yBlocks.get(b.paragraph)!.get('prop:text') as Y.Text
      ).toString();
    expect(raw()).toBe('Hello'); // not bound while closed

    b.store.load(); // the app is opened again
    expect(b.store.get(SyncedBlocksExtension).getLink(b.paragraph)).toEqual({
      docId: 'docA',
      blockId: a.paragraph,
    });
    expect(textOf(b).toString()).toBe('Hello!');
    textOf(b).insert('?', 6);
    expect(textOf(a).toString()).toBe('Hello!?');
  });

  test('unlinking stops the flow', () => {
    const { a, b, textOf } = twoDocs();
    const ext = b.store.get(SyncedBlocksExtension);
    ext.link(b.paragraph, { docId: 'docA', blockId: a.paragraph });
    ext.unlink(b.paragraph);
    textOf(a).insert(' more', 5);
    expect(textOf(b).toString()).toBe('Hello');
  });
});

describe('bindYTexts', () => {
  const pair = (a: string, b: string) => {
    const da = new Y.Doc();
    const db = new Y.Doc();
    da.getText('t').insert(0, a);
    db.getText('t').insert(0, b);
    return { ta: da.getText('t'), tb: db.getText('t') };
  };

  test('keeps formatting, deletes and replacements in step', () => {
    const { ta, tb } = pair('', 'abc');
    bindYTexts(ta, tb);
    expect(ta.toString()).toBe('abc');
    tb.format(0, 3, { bold: true });
    expect(ta.toDelta()).toEqual([{ insert: 'abc', attributes: { bold: true } }]);
    ta.delete(1, 1);
    expect(tb.toString()).toBe('ac');
    ta.applyDelta([{ retain: 1 }, { insert: 'ZZ' }]);
    expect(tb.toString()).toBe('aZZc');
  });

  test('a change does not bounce back, and unbinding stops the flow', () => {
    const { ta, tb } = pair('x', 'x');
    let events = 0;
    ta.observe(() => events++);
    const unbind = bindYTexts(ta, tb);
    tb.insert(1, 'y');
    expect(events).toBe(1);
    expect(ta.toString()).toBe('xy');
    unbind();
    tb.insert(2, 'z');
    expect(ta.toString()).toBe('xy');
  });

  test('refuses texts that are not in a doc', () => {
    expect(() => bindYTexts(new Y.Text(), new Y.Text())).toThrow(/inside a Y.Doc/);
  });
});
