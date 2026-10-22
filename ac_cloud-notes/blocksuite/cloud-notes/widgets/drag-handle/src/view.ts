import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { dragHandleWidget } from '.';
import { effects } from './effects';

export class DragHandleViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-drag-handle-widget';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    context.register(dragHandleWidget);
  }
}
