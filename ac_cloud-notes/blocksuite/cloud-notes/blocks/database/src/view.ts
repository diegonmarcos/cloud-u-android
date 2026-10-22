import type { MenuOptions } from '@blocksuite/cloud-notes-components/context-menu';
import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { DatabaseBlockModel } from '@blocksuite/cloud-notes-model';
import { SlashMenuConfigExtension } from '@blocksuite/cloud-notes-widget-slash-menu';
import { BlockViewExtension, FlavourExtension } from '@blocksuite/std';
import { literal } from 'lit/static-html.js';
import { z } from 'zod';

import { DatabaseConfigExtension } from './config';
import { databaseSlashMenuConfig } from './configs/slash-menu.js';
import { effects } from './effects';

const optionsSchema = z.object({
  configure: z
    .function()
    .args(z.instanceof(DatabaseBlockModel), z.custom<MenuOptions>())
    .returns(z.custom<MenuOptions>()),
});

export type DatabaseViewExtensionOptions = z.infer<typeof optionsSchema>;

export class DatabaseViewExtension extends ViewExtensionProvider<DatabaseViewExtensionOptions> {
  override name = 'cloud-notes-database-block';

  override schema = optionsSchema;

  override effect() {
    super.effect();
    effects();
  }

  override setup(
    context: ViewExtensionContext,
    options?: DatabaseViewExtensionOptions
  ) {
    super.setup(context);
    context.register([
      FlavourExtension('cloud-notes:database'),
      BlockViewExtension('cloud-notes:database', literal`cloud-notes-database`),
      SlashMenuConfigExtension('cloud-notes:database', databaseSlashMenuConfig),
    ]);
    if (options) {
      context.register(
        DatabaseConfigExtension({ configure: options.configure })
      );
    }
  }
}
