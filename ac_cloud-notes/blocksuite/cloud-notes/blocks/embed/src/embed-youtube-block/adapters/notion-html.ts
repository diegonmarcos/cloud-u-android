import { EmbedYoutubeBlockSchema } from '@blocksuite/cloud-notes-model';
import { BlockNotionHtmlAdapterExtension } from '@blocksuite/cloud-notes-shared/adapters';

import { createEmbedBlockNotionHtmlAdapterMatcher } from '../../common/adapters/notion-html.js';
import { youtubeUrlRegex } from '../embed-youtube-model.js';

export const embedYoutubeBlockNotionHtmlAdapterMatcher =
  createEmbedBlockNotionHtmlAdapterMatcher(
    EmbedYoutubeBlockSchema.model.flavour,
    youtubeUrlRegex
  );

export const EmbedYoutubeBlockNotionHtmlAdapterExtension =
  BlockNotionHtmlAdapterExtension(embedYoutubeBlockNotionHtmlAdapterMatcher);
