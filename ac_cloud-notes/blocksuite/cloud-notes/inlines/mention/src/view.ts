import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';

import { effects } from './effects';
import { MentionInlineSpecExtension } from './inline-spec';

export class MentionViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-mention-inline';

  override effect(): void {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext): void {
    super.setup(context);
    context.register(MentionInlineSpecExtension);
  }
}
