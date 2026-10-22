import { textKeymap } from '@blocksuite/cloud-notes-inline-preset';
import { CodeBlockSchema } from '@blocksuite/cloud-notes-model';
import { KeymapExtension } from '@blocksuite/std';

export const CodeKeymapExtension = KeymapExtension(textKeymap, {
  flavour: CodeBlockSchema.model.flavour,
});
