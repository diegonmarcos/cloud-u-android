import { EmbedYoutubeBlockSchema } from '@blocksuite/cloud-notes-model';
import { BlockPlainTextAdapterExtension } from '@blocksuite/cloud-notes-shared/adapters';

import { createEmbedBlockPlainTextAdapterMatcher } from '../../common/adapters/plain-text.js';

export const embedYoutubeBlockPlainTextAdapterMatcher =
  createEmbedBlockPlainTextAdapterMatcher(
    EmbedYoutubeBlockSchema.model.flavour
  );

export const EmbedYoutubeBlockPlainTextAdapterExtension =
  BlockPlainTextAdapterExtension(embedYoutubeBlockPlainTextAdapterMatcher);
