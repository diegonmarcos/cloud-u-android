import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';
import { TemplateTool } from './template-tool';
import { templateSeniorTool } from './toolbar/senior-tool';

export class TemplateViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-template-view';

  override effect(): void {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    if (this.isEdgeless(context.scope)) {
      context.register(TemplateTool);
      context.register(templateSeniorTool);
    }
  }
}
