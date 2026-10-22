import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { frameTitleWidget } from './cloud-notes-frame-title-widget';
import { effects } from './effects';

export class FrameTitleViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-frame-title-widget';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    if (context.scope === 'edgeless') {
      context.register(frameTitleWidget);
    }
  }
}
