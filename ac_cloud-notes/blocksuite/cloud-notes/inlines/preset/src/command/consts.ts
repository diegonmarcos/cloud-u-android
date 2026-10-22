// corresponding to `formatText` command
import { TableModelFlavour } from '@blocksuite/cloud-notes-model';

export const FORMAT_TEXT_SUPPORT_FLAVOURS = [
  'cloud-notes:paragraph',
  'cloud-notes:list',
  'cloud-notes:code',
];
// corresponding to `formatBlock` command
export const FORMAT_BLOCK_SUPPORT_FLAVOURS = [
  'cloud-notes:paragraph',
  'cloud-notes:list',
  'cloud-notes:code',
];
// corresponding to `formatNative` command
export const FORMAT_NATIVE_SUPPORT_FLAVOURS = [
  'cloud-notes:database',
  TableModelFlavour,
];
