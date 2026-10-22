import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';
import { linkQuickTool } from './link-tool';

export class LinkViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-link-gfx';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    if (this.isEdgeless(context.scope)) {
      context.register(linkQuickTool);
    }
  }
}
