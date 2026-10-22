import {
  type StoreExtensionContext,
  StoreExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import {
  footnoteReferenceDeltaToMarkdownAdapterMatcher,
  FootnoteReferenceMarkdownPreprocessorExtension,
  markdownFootnoteReferenceToDeltaMatcher,
} from './adapters';

export class FootnoteStoreExtension extends StoreExtensionProvider {
  override name = 'cloud-notes-footnote-inline';

  override setup(context: StoreExtensionContext) {
    super.setup(context);
    context.register(markdownFootnoteReferenceToDeltaMatcher);
    context.register(footnoteReferenceDeltaToMarkdownAdapterMatcher);
    context.register(FootnoteReferenceMarkdownPreprocessorExtension);
  }
}
