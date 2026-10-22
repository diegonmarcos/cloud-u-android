import { getEditorConfigExtension } from '@cloud-notes/core/blocksuite/view-extensions/editor-config/get-config';
import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes/ext-loader';
import { FrameworkProvider } from '@toeverything/infra';
import { z } from 'zod';

const optionsSchema = z.object({
  framework: z.instanceof(FrameworkProvider).optional(),
});

type CloudNotesEditorConfigViewOptions = z.infer<typeof optionsSchema>;

export class CloudNotesEditorConfigViewExtension extends ViewExtensionProvider<CloudNotesEditorConfigViewOptions> {
  override name = 'cloud-notes-view-editor-config';

  override schema = optionsSchema;

  override setup(
    context: ViewExtensionContext,
    options?: CloudNotesEditorConfigViewOptions
  ) {
    super.setup(context, options);
    const framework = options?.framework;
    if (!framework) {
      return;
    }

    if (context.scope === 'edgeless' || context.scope === 'page') {
      context.register(getEditorConfigExtension(framework));
    }
  }
}
