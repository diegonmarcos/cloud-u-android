import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { LatexBlockSchemaExtension } from '@blocksuite/cloud-notes-model';

import { LatexBlockAdapterExtensions } from './adapters/extension';
import { LatexMarkdownPreprocessorExtension } from './adapters/markdown/preprocessor';

export class LatexStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-latex-block';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register([LatexBlockSchemaExtension]);
    context.register(LatexBlockAdapterExtensions);
    context.register(LatexMarkdownPreprocessorExtension);
  }
}
