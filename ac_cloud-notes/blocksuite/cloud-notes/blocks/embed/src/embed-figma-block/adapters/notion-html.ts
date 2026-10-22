import { EmbedFigmaBlockSchema } from '@blocksuite/cloud-notes-model';
import { BlockNotionHtmlAdapterExtension } from '@blocksuite/cloud-notes-shared/adapters';

import { createEmbedBlockNotionHtmlAdapterMatcher } from '../../common/adapters/notion-html.js';
import { figmaUrlRegex } from '../embed-figma-model.js';

export const embedFigmaBlockNotionHtmlAdapterMatcher =
  createEmbedBlockNotionHtmlAdapterMatcher(
    EmbedFigmaBlockSchema.model.flavour,
    figmaUrlRegex
  );

export const EmbedFigmaBlockNotionHtmlAdapterExtension =
  BlockNotionHtmlAdapterExtension(embedFigmaBlockNotionHtmlAdapterMatcher);
