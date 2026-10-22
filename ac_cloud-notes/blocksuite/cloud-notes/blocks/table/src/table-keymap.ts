import { textKeymap } from '@blocksuite/cloud-notes-inline-preset';
import { TableBlockSchema } from '@blocksuite/cloud-notes-model';
import { KeymapExtension } from '@blocksuite/std';

export const TableKeymapExtension = KeymapExtension(textKeymap, {
  flavour: TableBlockSchema.model.flavour,
});
