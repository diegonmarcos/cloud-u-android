import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import {
  EmbedLinkedDocBlockSchemaExtension,
  EmbedSyncedDocBlockSchemaExtension,
} from '@blocksuite/cloud-notes-model';

import { EmbedLinkedDocBlockAdapterExtensions } from './embed-linked-doc-block/adapters/extension';
import { EmbedSyncedDocBlockAdapterExtensions } from './embed-synced-doc-block/adapters/extension';

export class EmbedDocStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-embed-doc-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register([
      EmbedSyncedDocBlockSchemaExtension,
      EmbedLinkedDocBlockSchemaExtension,
    ]);
    context.register(EmbedLinkedDocBlockAdapterExtensions);
    context.register(EmbedSyncedDocBlockAdapterExtensions);
  }
}
