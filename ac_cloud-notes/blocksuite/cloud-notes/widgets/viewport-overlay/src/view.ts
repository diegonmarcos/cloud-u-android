import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';
import { viewportOverlayWidget } from './index';

export class ViewportOverlayViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-viewport-overlay-widget';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    context.register(viewportOverlayWidget);
  }
}
