import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes/ext-loader';
import { FrameworkProvider } from '@toeverything/infra';
import { z } from 'zod';

import { patchDatabaseBlockConfigService } from './database-block-config-service';

const optionsSchema = z.object({
  framework: z.instanceof(FrameworkProvider).optional(),
});

export type CloudNotesDatabaseViewOptions = z.infer<typeof optionsSchema>;

export class CloudNotesDatabaseViewExtension extends ViewExtensionProvider<CloudNotesDatabaseViewOptions> {
  override name = 'cloud-notes-database-view';

  override schema = optionsSchema;

  override setup(
    context: ViewExtensionContext,
    options?: CloudNotesDatabaseViewOptions
  ) {
    super.setup(context, options);

    context.register(patchDatabaseBlockConfigService(options?.framework));
  }
}
