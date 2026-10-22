import { EmbedFigmaBlockSchema } from '@blocksuite/cloud-notes-model';
import { BlockPlainTextAdapterExtension } from '@blocksuite/cloud-notes-shared/adapters';

import { createEmbedBlockPlainTextAdapterMatcher } from '../../common/adapters/plain-text.js';

export const embedFigmaBlockPlainTextAdapterMatcher =
  createEmbedBlockPlainTextAdapterMatcher(EmbedFigmaBlockSchema.model.flavour);

export const EmbedFigmaBlockPlainTextAdapterExtension =
  BlockPlainTextAdapterExtension(embedFigmaBlockPlainTextAdapterMatcher);
