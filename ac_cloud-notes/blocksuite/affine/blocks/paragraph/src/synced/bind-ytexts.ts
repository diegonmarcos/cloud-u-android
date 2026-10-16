import type * as Y from 'yjs';

/** Transaction origin of a mirrored change: the receiving side ignores it, so a change does not bounce back. */
export const SYNCED_ORIGIN = Symbol('synced-block');

/**
 * Keep two Y.Text in step, in both directions, across Y.Docs.
 * The `source` content wins at bind time (the copy is replaced), afterwards every delta applied
 * to one side is replayed on the other. Returns the function that unbinds.
 */
export function bindYTexts(copy: Y.Text, source: Y.Text): () => void {
  const copyDoc = copy.doc;
  const sourceDoc = source.doc;
  if (!copyDoc || !sourceDoc) {
    throw new Error('Both texts must be inside a Y.Doc to be bound.');
  }

  // source wins: make the copy identical before any observer exists
  const initial = source.toDelta();
  if (JSON.stringify(copy.toDelta()) !== JSON.stringify(initial)) {
    copyDoc.transact(() => {
      copy.delete(0, copy.length);
      copy.applyDelta(initial);
    }, SYNCED_ORIGIN);
  }

  const mirror =
    (to: Y.Text, toDoc: Y.Doc) => (event: Y.YTextEvent, tr: Y.Transaction) => {
      if (tr.origin === SYNCED_ORIGIN) return;
      toDoc.transact(() => {
        to.applyDelta(event.delta);
      }, SYNCED_ORIGIN);
    };

  const onCopy = mirror(source, sourceDoc);
  const onSource = mirror(copy, copyDoc);
  copy.observe(onCopy);
  source.observe(onSource);
  return () => {
    copy.unobserve(onCopy);
    source.unobserve(onSource);
  };
}
