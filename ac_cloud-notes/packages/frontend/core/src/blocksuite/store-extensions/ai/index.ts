import { AIChatBlockSchemaExtension } from '@cloud-notes/core/blocksuite/ai/blocks';
import { TranscriptionBlockSchemaExtension } from '@cloud-notes/core/blocksuite/ai/blocks/transcription-block/model';
import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes/ext-loader';

export class AIStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-store-extensions';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(AIChatBlockSchemaExtension);
    context.register(TranscriptionBlockSchemaExtension);
  }
}
