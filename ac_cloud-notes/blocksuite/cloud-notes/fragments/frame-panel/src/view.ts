import { ViewExtensionProvider } from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';

export class FramePanelViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-frame-panel-fragment';

  override effect() {
    super.effect();
    effects();
  }
}
