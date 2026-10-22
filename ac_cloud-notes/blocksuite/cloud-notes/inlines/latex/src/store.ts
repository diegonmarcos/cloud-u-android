import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import {
  latexDeltaMarkdownAdapterMatch,
  latexDeltaToMarkdownAdapterMatcher,
  markdownInlineMathToDeltaMatcher,
} from './adapters';

export class LatexStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-latex-inline';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(latexDeltaMarkdownAdapterMatch);
    context.register(latexDeltaToMarkdownAdapterMatcher);
    context.register(markdownInlineMathToDeltaMatcher);
  }
}
