import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { SlashMenuConfigExtension } from '@blocksuite/cloud-notes-widget-slash-menu';
import { BlockViewExtension, FlavourExtension } from '@blocksuite/std';
import { literal } from 'lit/static-html.js';

import { CalloutKeymapExtension } from './callout-keymap';
import { calloutSlashMenuConfig } from './configs/slash-menu';
import { createBuiltinToolbarConfigExtension } from './configs/toolbar';
import { effects } from './effects';

export class CalloutViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-callout-block';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    context.register([
      FlavourExtension('cloud-notes:callout'),
      BlockViewExtension('cloud-notes:callout', literal`cloud-notes-callout`),
      CalloutKeymapExtension,
      SlashMenuConfigExtension('cloud-notes:callout', calloutSlashMenuConfig),
      ...createBuiltinToolbarConfigExtension('cloud-notes:callout'),
    ]);
  }
}
