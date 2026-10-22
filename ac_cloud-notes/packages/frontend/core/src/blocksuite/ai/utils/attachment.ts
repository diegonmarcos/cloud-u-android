import { AttachmentBlockModel } from '@blocksuite/cloud-notes/model';
import type { BlockModel } from '@blocksuite/cloud-notes/store';
import type { GfxModel } from '@blocksuite/std/gfx';

export function isAttachment(
  model: GfxModel | BlockModel
): model is AttachmentBlockModel {
  return model instanceof AttachmentBlockModel;
}
