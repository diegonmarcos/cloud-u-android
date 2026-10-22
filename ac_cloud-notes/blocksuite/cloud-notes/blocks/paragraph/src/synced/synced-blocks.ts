import { StoreExtension } from '@blocksuite/store';
import type * as Y from 'yjs';

import { bindYTexts } from './bind-ytexts.js';

export type SyncedSource = { docId: string; blockId: string };

const LINKS_KEY = 'synced-blocks';
const RETRY_MS = 300;
const MAX_RETRIES = 20;

/**
 * Synced blocks (Notion): a block whose text is kept identical with a block of ANOTHER doc,
 * edits flowing both ways. The links live in the doc itself (a Y.Map beside the blocks), so
 * they travel with the doc through storage and sync, and are re-bound whenever the doc loads;
 * the source's content wins at bind time.
 *
 * Text blocks (paragraph, heading, list, quote...) are synced; the link is made with
 * {@link SyncedBlocksExtension.link}.
 */
export class SyncedBlocksExtension extends StoreExtension {
  static override key = 'cloud-notes-synced-blocks';

  private readonly unbinders = new Map<string, () => void>();
  private readonly timers = new Set<ReturnType<typeof setTimeout>>();
  private disposed_ = false;
  private stopObserving?: () => void;

  private get links(): Y.Map<SyncedSource> {
    return this.store.spaceDoc.getMap<SyncedSource>(LINKS_KEY);
  }

  /** Link the text of `targetBlockId` (in this doc) to `source`; the target takes the source's text. */
  link(targetBlockId: string, source: SyncedSource) {
    this.links.set(targetBlockId, { docId: source.docId, blockId: source.blockId });
  }

  unlink(targetBlockId: string) {
    this.links.delete(targetBlockId);
  }

  getLink(targetBlockId: string): SyncedSource | undefined {
    return this.links.get(targetBlockId);
  }

  private textOf(docId: string, blockId: string): Y.Text | undefined {
    const doc = this.store.workspace.getDoc(docId);
    if (!doc) return undefined;
    if (!doc.ready) doc.load();
    const text = doc.yBlocks.get(blockId)?.get('prop:text');
    return text && typeof (text as Y.Text).toDelta === 'function'
      ? (text as Y.Text)
      : undefined;
  }

  private tryBind(targetBlockId: string, attempt = 0) {
    if (this.disposed_ || this.unbinders.has(targetBlockId)) return;
    const link = this.links.get(targetBlockId);
    if (!link) return;
    const copy = this.textOf(this.store.id, targetBlockId);
    const source = this.textOf(link.docId, link.blockId);
    if (!copy || !source || copy === source) {
      // the other doc (or the block) is not loaded yet: look again shortly
      if (attempt < MAX_RETRIES) {
        const timer = setTimeout(() => {
          this.timers.delete(timer);
          this.tryBind(targetBlockId, attempt + 1);
        }, RETRY_MS);
        this.timers.add(timer);
      }
      return;
    }
    this.unbinders.set(targetBlockId, bindYTexts(copy, source));
  }

  private unbind(targetBlockId: string) {
    this.unbinders.get(targetBlockId)?.();
    this.unbinders.delete(targetBlockId);
  }

  override loaded() {
    this.disposed_ = false; // a store can be disposed and loaded again (the app reopening a doc)
    this.stopObserving?.();
    this.links.forEach((_, id) => this.tryBind(id));
    const links = this.links;
    const onLinks = (event: Y.YMapEvent<SyncedSource>) => {
      for (const id of event.keysChanged) {
        if (links.has(id)) {
          this.unbind(id);
          this.tryBind(id);
        } else {
          this.unbind(id);
        }
      }
    };
    links.observe(onLinks);
    this.stopObserving = () => links.unobserve(onLinks);
    // a synced block pasted before its text exists, or restored by undo
    this.store.disposableGroup.add(
      this.store.slots.blockUpdated.subscribe(payload => {
        if (payload.type === 'add') this.tryBind(payload.id);
        if (payload.type === 'delete') this.unbind(payload.id);
      })
    );
  }

  override disposed() {
    this.disposed_ = true;
    this.stopObserving?.();
    this.stopObserving = undefined;
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
    for (const id of [...this.unbinders.keys()]) this.unbind(id);
  }
}
