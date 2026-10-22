import { ViewExtensionProvider } from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';

export class DocTitleViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-doc-title-fragment';

  override effect() {
    super.effect();
    effects();
  }
}
