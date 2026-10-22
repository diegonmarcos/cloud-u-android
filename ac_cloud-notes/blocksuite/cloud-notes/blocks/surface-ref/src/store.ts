import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { SurfaceRefBlockSchemaExtension } from '@blocksuite/cloud-notes-model';

export class SurfaceRefStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-surface-ref-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(SurfaceRefBlockSchemaExtension);
  }
}
