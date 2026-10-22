import { DividerBlockSchema } from '@blocksuite/cloud-notes-model';
import {
  BlockPlainTextAdapterExtension,
  type BlockPlainTextAdapterMatcher,
} from '@blocksuite/cloud-notes-shared/adapters';

export const dividerBlockPlainTextAdapterMatcher: BlockPlainTextAdapterMatcher =
  {
    flavour: DividerBlockSchema.model.flavour,
    toMatch: () => false,
    fromMatch: o => o.node.flavour === DividerBlockSchema.model.flavour,
    toBlockSnapshot: {},
    fromBlockSnapshot: {
      enter: (_, context) => {
        context.textBuffer.content += '---\n';
      },
    },
  };

export const DividerBlockPlainTextAdapterExtension =
  BlockPlainTextAdapterExtension(dividerBlockPlainTextAdapterMatcher);
