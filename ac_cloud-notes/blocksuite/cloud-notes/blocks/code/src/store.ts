import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { CodeBlockSchemaExtension } from '@blocksuite/cloud-notes-model';

import { CodeBlockAdapterExtensions } from './adapters/extension';
import { CodeMarkdownPreprocessorExtension } from './adapters/markdown/preprocessor';

export class CodeStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-code-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(CodeBlockSchemaExtension);
    context.register(CodeBlockAdapterExtensions);
    context.register(CodeMarkdownPreprocessorExtension);
  }
}
