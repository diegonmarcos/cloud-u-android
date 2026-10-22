import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { ListBlockSchemaExtension } from '@blocksuite/cloud-notes-model';

import { ListBlockAdapterExtensions } from './adapters/extension';

export class ListStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-list-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(ListBlockSchemaExtension);
    context.register(ListBlockAdapterExtensions);
  }
}
