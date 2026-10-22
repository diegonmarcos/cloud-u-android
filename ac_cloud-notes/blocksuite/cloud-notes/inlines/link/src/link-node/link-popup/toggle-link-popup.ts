import type { CloudNotesInlineEditor } from '@blocksuite/cloud-notes-shared/types';
import type { BlockStdScope } from '@blocksuite/std';
import type { InlineRange } from '@blocksuite/std/inline';

import { LinkPopup } from './link-popup';

export function toggleLinkPopup(
  std: BlockStdScope,
  type: LinkPopup['type'],
  inlineEditor: CloudNotesInlineEditor,
  targetInlineRange: InlineRange,
  abortController: AbortController
): LinkPopup {
  const popup = new LinkPopup();
  popup.std = std;
  popup.type = type;
  popup.inlineEditor = inlineEditor;
  popup.targetInlineRange = targetInlineRange;
  popup.abortController = abortController;

  const root =
    inlineEditor.rootElement?.closest('editor-host')?.parentElement ??
    document.body;
  root.append(popup);

  return popup;
}
