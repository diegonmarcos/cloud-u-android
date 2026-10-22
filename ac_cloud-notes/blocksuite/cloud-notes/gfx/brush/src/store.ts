import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import {
  brushToMarkdownAdapterMatcher,
  brushToPlainTextAdapterMatcher,
} from './adapter';

export class BrushStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-brush-gfx';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(brushToMarkdownAdapterMatcher);
    context.register(brushToPlainTextAdapterMatcher);
  }
}
