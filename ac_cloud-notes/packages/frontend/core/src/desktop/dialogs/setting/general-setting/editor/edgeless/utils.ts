import type { FrameBlockModel } from '@blocksuite/cloud-notes/model';
import type { Store } from '@blocksuite/cloud-notes/store';

export function getFrameBlock(doc: Store) {
  const blocks = doc.getBlocksByFlavour('cloud-notes:frame');
  return blocks.length !== 0 ? (blocks[0].model as FrameBlockModel) : null;
}
