import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { FrameBlockSchemaExtension } from '@blocksuite/cloud-notes-model';

export class FrameStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-frame-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register([FrameBlockSchemaExtension]);
  }
}
