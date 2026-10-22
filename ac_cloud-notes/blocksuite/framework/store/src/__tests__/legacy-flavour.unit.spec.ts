import { describe, expect, test } from 'vitest';
import * as Y from 'yjs';

import {
  FLAVOUR_PREFIX,
  LEGACY_FLAVOUR_MIGRATION_ORIGIN,
  LEGACY_FLAVOUR_PREFIX,
  migrateStoredFlavour,
  normalizeFlavour,
} from '../utils/legacy-flavour.js';
import type { YBlock } from '../model/block/types.js';
import {
  ParagraphBlockSchemaExtension,
  RootBlockSchemaExtension,
} from './test-schema.js';
import { TestWorkspace } from '../test/test-workspace.js';
import { createAutoIncrementIdGenerator } from '../test/index.js';

const L = LEGACY_FLAVOUR_PREFIX;
const N = FLAVOUR_PREFIX;

describe('normalizeFlavour', () => {
  test('reads the old prefix as the current one and leaves the rest alone', () => {
    expect(normalizeFlavour(`${L}paragraph`)).toBe(`${N}paragraph`);
    expect(normalizeFlavour(`${L}embed-linked-doc`)).toBe(`${N}embed-linked-doc`);
    expect(normalizeFlavour(`${N}paragraph`)).toBe(`${N}paragraph`);
    expect(normalizeFlavour('custom:flavour')).toBe('custom:flavour');
    expect(normalizeFlavour(undefined)).toBeUndefined();
  });
});

describe('a document stored with the old flavour ids', () => {
  function storedBlocks(flavours: Record<string, string>) {
    const ydoc = new Y.Doc();
    const blocks = ydoc.getMap<YBlock>('blocks');
    for (const [id, flavour] of Object.entries(flavours)) {
      const yBlock = new Y.Map() as YBlock;
      blocks.set(id, yBlock);
      yBlock.set('sys:id', id);
      yBlock.set('sys:flavour', flavour);
      yBlock.set('sys:version', 1);
      yBlock.set('sys:children', new Y.Array<string>());
    }
    return { ydoc, blocks };
  }

  test('migrateStoredFlavour rewrites the stored value once and reports the origin', () => {
    const { ydoc, blocks } = storedBlocks({ a: `${L}paragraph` });
    const origins: unknown[] = [];
    ydoc.on('afterTransaction', tr => origins.push(tr.origin));
    const yBlock = blocks.get('a') as YBlock;

    expect(migrateStoredFlavour(yBlock)).toBe(`${N}paragraph`);
    expect(yBlock.get('sys:flavour')).toBe(`${N}paragraph`);
    expect(origins).toEqual([LEGACY_FLAVOUR_MIGRATION_ORIGIN]);

    // already current: no second write
    expect(migrateStoredFlavour(yBlock)).toBe(`${N}paragraph`);
    expect(origins).toHaveLength(1);
  });

  test('adding a block under the old spelling stores the current flavour', () => {
    const workspace = new TestWorkspace({
      id: 'w',
      idGenerator: createAutoIncrementIdGenerator(),
    });
    workspace.meta.initialize();
    const doc = workspace.createDoc('d');
    doc.load();
    const store = doc.getStore({
      extensions: [RootBlockSchemaExtension, ParagraphBlockSchemaExtension],
    });
    const rootId = store.addBlock(`${L}page`);
    const id = store.addBlock(`${L}paragraph`, {}, rootId);
    expect(doc.yBlocks.get(id)?.get('sys:flavour')).toBe(`${N}paragraph`);
    expect(store.getBlock(id)?.flavour).toBe(`${N}paragraph`);
  });

  test('the schema map answers for either spelling of a registered flavour', () => {
    const workspace = new TestWorkspace({
      id: 'w2',
      idGenerator: createAutoIncrementIdGenerator(),
    });
    workspace.meta.initialize();
    const doc = workspace.createDoc('d2');
    doc.load();
    const store = doc.getStore({ extensions: [ParagraphBlockSchemaExtension] });
    expect(store.schema.get(`${L}paragraph`)).toBeDefined();
    expect(store.schema.get(`${N}paragraph`)).toBe(
      store.schema.get(`${L}paragraph`)
    );
  });
});
