import { ViewExtensionProvider } from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';

export class OutlineViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-outline-fragment';

  override effect() {
    super.effect();
    effects();
  }
}
