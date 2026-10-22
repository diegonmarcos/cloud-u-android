import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { ParagraphBlockSchemaExtension } from '@blocksuite/cloud-notes-model';

import { ParagraphBlockAdapterExtensions } from './adapters/extension';
import { SyncedBlocksExtension } from './synced/synced-blocks';

export class ParagraphStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-paragraph-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(ParagraphBlockSchemaExtension);
    context.register(ParagraphBlockAdapterExtensions);
    context.register(SyncedBlocksExtension);
  }
}
