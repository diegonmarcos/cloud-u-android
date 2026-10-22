import { type DeltaInsert, type Store, Text } from '@blocksuite/store';
import type * as Y from 'yjs';

import { type SyncedSource, SyncedBlocksExtension } from './synced-blocks.js';

type Workspace = Store['workspace'];

export type SyncedBlockInfo = { type: string; delta: DeltaInsert[] };

/** Read the text and paragraph type of a block of any (loaded or loadable) doc of the workspace. */
export function readSyncedSource(
  workspace: Workspace,
  source: SyncedSource
): SyncedBlockInfo | null {
  const doc = workspace.getDoc(source.docId);
  if (!doc) return null;
  if (!doc.ready) doc.load();
  const block = doc.yBlocks.get(source.blockId);
  const text = block?.get('prop:text') as Y.Text | undefined;
  if (!block || !text || typeof text.toDelta !== 'function') return null;
  const type = block.get('prop:type');
  return {
    type: typeof type === 'string' ? type : 'text',
    delta: text.toDelta() as DeltaInsert[],
  };
}

/**
 * The block most recently "copied as synced". In memory on purpose: it is the clipboard of the
 * running editor, and pasting it is what makes the new block follow the source.
 */
let pending: SyncedSource | null = null;

export const syncedClipboard = {
  get: () => pending,
  set: (source: SyncedSource | null) => {
    pending = source;
  },
};

/** "Copy as synced block": remember the block. A copy of a synced block points at the original. */
export function copyAsSynced(store: Store, blockId: string): SyncedSource | null {
  const origin =
    store.get(SyncedBlocksExtension).getLink(blockId) ?? {
      docId: store.id,
      blockId,
    };
  if (!readSyncedSource(store.workspace, origin)) return null;
  syncedClipboard.set({ docId: origin.docId, blockId: origin.blockId });
  return syncedClipboard.get();
}

/** Insert a new text block that stays identical with `source`; returns its id. */
export function insertSyncedCopy(
  store: Store,
  source: SyncedSource,
  place: { parentId: string; index?: number }
): string | null {
  if (source.docId === store.id) {
    // same doc: a synced copy of a sibling block is allowed, but never of itself
    if (!store.getBlock(source.blockId)) return null;
  }
  const info = readSyncedSource(store.workspace, source);
  if (!info) return null;
  const id = store.addBlock(
    'cloud-notes:paragraph',
    { type: info.type as any, text: new Text(info.delta) },
    place.parentId,
    place.index
  );
  store.get(SyncedBlocksExtension).link(id, source);
  return id;
}

/** "Paste as synced block" / "/synced": the copied block goes in right after `afterBlockId`. */
export function pasteAsSynced(store: Store, afterBlockId: string): string | null {
  const source = syncedClipboard.get();
  const after = store.getBlock(afterBlockId)?.model;
  const parent = after?.parent;
  if (!source || !after || !parent) return null;
  return insertSyncedCopy(store, source, {
    parentId: parent.id,
    index: parent.children.indexOf(after) + 1,
  });
}

/** "Sync to…": append a synced copy of the block at the end of the target doc's first note. */
export function syncBlockToDoc(
  sourceStore: Store,
  blockId: string,
  targetStore: Store
): string | null {
  const source = copyAsSynced(sourceStore, blockId);
  const root = targetStore.root;
  if (!source || !root) return null;
  const note =
    root.children.find(child => child.flavour === 'cloud-notes:note')?.id ??
    targetStore.addBlock('cloud-notes:note', {}, root.id);
  return insertSyncedCopy(targetStore, source, { parentId: note });
}
