import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';
import { FootNoteInlineSpecExtension } from './inline-spec';

export class FootnoteViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-footnote-inline';

  override effect(): void {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    context.register(FootNoteInlineSpecExtension);
  }
}
