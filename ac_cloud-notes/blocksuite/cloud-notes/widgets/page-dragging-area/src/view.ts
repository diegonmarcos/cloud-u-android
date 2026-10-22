import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';
import { pageDraggingAreaWidget } from './index';

export class PageDraggingAreaViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-page-dragging-area-widget';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    if (this.isEdgeless(context.scope)) {
      return;
    }
    context.register(pageDraggingAreaWidget);
  }
}
