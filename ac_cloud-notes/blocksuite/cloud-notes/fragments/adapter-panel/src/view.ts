import { ViewExtensionProvider } from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';

export class AdapterPanelViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-adapter-panel-fragment';

  override effect() {
    super.effect();
    effects();
  }
}
