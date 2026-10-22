import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { InlineAdapterExtensions } from './adapters/extensions';

export class InlinePresetStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-inline-preset';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(InlineAdapterExtensions);
  }
}
