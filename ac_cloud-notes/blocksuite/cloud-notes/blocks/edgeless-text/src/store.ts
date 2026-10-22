import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { EdgelessTextBlockSchemaExtension } from '@blocksuite/cloud-notes-model';

export class EdgelessTextStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-edgeless-text-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(EdgelessTextBlockSchemaExtension);
  }
}
