import { EmbedYoutubeBlockSchema } from '@blocksuite/cloud-notes-model';
import { SlashMenuConfigExtension } from '@blocksuite/cloud-notes-widget-slash-menu';
import { BlockViewExtension, FlavourExtension } from '@blocksuite/std';
import type { ExtensionType } from '@blocksuite/store';
import { literal } from 'lit/static-html.js';

import { createBuiltinToolbarConfigExtension } from '../configs/toolbar';
import { embedYoutubeSlashMenuConfig } from './configs/slash-menu';
import { EmbedYoutubeBlockInteraction } from './embed-edgeless-youtube-block';
import { EmbedYoutubeBlockComponent } from './embed-youtube-block';
import {
  EmbedYoutubeBlockOptionConfig,
  EmbedYoutubeBlockService,
} from './embed-youtube-service';

const flavour = EmbedYoutubeBlockSchema.model.flavour;

export const EmbedYoutubeViewExtensions: ExtensionType[] = [
  FlavourExtension(flavour),
  EmbedYoutubeBlockService,
  BlockViewExtension(flavour, model => {
    return model.parent?.flavour === 'cloud-notes:surface'
      ? literal`cloud-notes-embed-edgeless-youtube-block`
      : literal`cloud-notes-embed-youtube-block`;
  }),
  EmbedYoutubeBlockOptionConfig,
  createBuiltinToolbarConfigExtension(flavour, EmbedYoutubeBlockComponent),
  SlashMenuConfigExtension('cloud-notes:embed-youtube', embedYoutubeSlashMenuConfig),
  EmbedYoutubeBlockInteraction,
].flat();
