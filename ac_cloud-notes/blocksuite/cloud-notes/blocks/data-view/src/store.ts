import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { DataViewBlockSchemaExtension } from './data-view-model';

export class DataViewStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-data-view-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(DataViewBlockSchemaExtension);
  }
}
