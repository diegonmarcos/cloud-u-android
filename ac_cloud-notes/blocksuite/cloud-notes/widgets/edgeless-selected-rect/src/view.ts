import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';
import { edgelessSelectedRectWidget } from './spec';

export class EdgelessSelectedRectViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-edgeless-selected-rect-widget';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    if (this.isEdgeless(context.scope)) {
      context.register(edgelessSelectedRectWidget);
    }
  }
}
