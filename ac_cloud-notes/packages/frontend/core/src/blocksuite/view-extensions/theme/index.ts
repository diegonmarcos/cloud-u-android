import { getPreviewThemeExtension } from '@cloud-notes/core/blocksuite/view-extensions/theme/preview-theme';
import { getThemeExtension } from '@cloud-notes/core/blocksuite/view-extensions/theme/theme';
import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes/ext-loader';
import { FrameworkProvider } from '@toeverything/infra';
import { z } from 'zod';

const optionsSchema = z.object({
  framework: z.instanceof(FrameworkProvider).optional(),
});

type CloudNotesThemeViewOptions = z.infer<typeof optionsSchema>;

export class CloudNotesThemeViewExtension extends ViewExtensionProvider<CloudNotesThemeViewOptions> {
  override name = 'cloud-notes-view-theme';

  override schema = optionsSchema;

  override setup(
    context: ViewExtensionContext,
    options?: CloudNotesThemeViewOptions
  ) {
    super.setup(context, options);
    const framework = options?.framework;
    if (!framework) {
      return;
    }

    if (this.isPreview(context.scope)) {
      context.register(getPreviewThemeExtension(framework));
    } else {
      context.register(getThemeExtension(framework));
    }
  }
}
