import { EmbedLoomBlockSchema } from '@blocksuite/cloud-notes-model';
import { SlashMenuConfigExtension } from '@blocksuite/cloud-notes-widget-slash-menu';
import { BlockViewExtension, FlavourExtension } from '@blocksuite/std';
import type { ExtensionType } from '@blocksuite/store';
import { literal } from 'lit/static-html.js';

import { createBuiltinToolbarConfigExtension } from '../configs/toolbar';
import { embedLoomSlashMenuConfig } from './configs/slash-menu';
import { EmbedLoomBlockInteraction } from './embed-edgeless-loom-bock';
import { EmbedLoomBlockComponent } from './embed-loom-block';
import {
  EmbedLoomBlockOptionConfig,
  EmbedLoomBlockService,
} from './embed-loom-service';

const flavour = EmbedLoomBlockSchema.model.flavour;

export const EmbedLoomViewExtensions: ExtensionType[] = [
  FlavourExtension(flavour),
  EmbedLoomBlockService,
  BlockViewExtension(flavour, model => {
    return model.parent?.flavour === 'cloud-notes:surface'
      ? literal`cloud-notes-embed-edgeless-loom-block`
      : literal`cloud-notes-embed-loom-block`;
  }),
  EmbedLoomBlockOptionConfig,
  createBuiltinToolbarConfigExtension(flavour, EmbedLoomBlockComponent),
  SlashMenuConfigExtension(flavour, embedLoomSlashMenuConfig),
  EmbedLoomBlockInteraction,
].flat();
