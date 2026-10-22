import { clearMarksOnDiscontinuousInput } from '@blocksuite/cloud-notes-rich-text';
import { getSelectedBlocksCommand } from '@blocksuite/cloud-notes-shared/commands';
import type {
  CloudNotesTextAttributes,
  CloudNotesTextStyleAttributes,
} from '@blocksuite/cloud-notes-shared/types';
import type { Command, TextSelection } from '@blocksuite/std';
import {
  INLINE_ROOT_ATTR,
  type InlineRootElement,
} from '@blocksuite/std/inline';

import { FORMAT_TEXT_SUPPORT_FLAVOURS } from './consts.js';

// for text selection
export const formatTextCommand: Command<{
  currentTextSelection?: TextSelection;
  textSelection?: TextSelection;
  styles: CloudNotesTextStyleAttributes;
  mode?: 'replace' | 'merge';
}> = (ctx, next) => {
  const { styles, mode = 'merge' } = ctx;

  const textSelection = ctx.textSelection ?? ctx.currentTextSelection;
  if (!textSelection) return;

  const success = ctx.std.command
    .chain()
    .pipe(getSelectedBlocksCommand, {
      textSelection,
      filter: el => FORMAT_TEXT_SUPPORT_FLAVOURS.includes(el.model.flavour),
      types: ['text'],
    })
    .pipe((ctx, next) => {
      const { selectedBlocks } = ctx;
      if (!selectedBlocks) return;

      const selectedInlineEditors = selectedBlocks.flatMap(el => {
        const inlineRoot = el.querySelector<
          InlineRootElement<CloudNotesTextAttributes>
        >(`[${INLINE_ROOT_ATTR}]`);
        if (inlineRoot && inlineRoot.inlineEditor.getInlineRange()) {
          return inlineRoot.inlineEditor;
        }
        return [];
      });

      selectedInlineEditors.forEach(inlineEditor => {
        const inlineRange = inlineEditor.getInlineRange();
        if (!inlineRange) return;

        if (inlineRange.length === 0) {
          const delta = inlineEditor.getDeltaByRangeIndex(inlineRange.index);

          inlineEditor.setMarks({
            ...inlineEditor.marks,
            ...Object.fromEntries(
              Object.entries(styles).map(([key, value]) => {
                if (typeof value === 'boolean') {
                  return [
                    key,
                    (inlineEditor.marks &&
                      inlineEditor.marks[key as keyof CloudNotesTextAttributes]) ||
                    (delta &&
                      delta.attributes &&
                      delta.attributes[key as keyof CloudNotesTextAttributes])
                      ? null
                      : value,
                  ];
                }
                return [key, value];
              })
            ),
          });
          clearMarksOnDiscontinuousInput(inlineEditor);
        } else {
          inlineEditor.formatText(inlineRange, styles, {
            mode,
          });
        }
      });

      Promise.all(selectedBlocks.map(el => el.updateComplete))
        .then(() => {
          ctx.std.range.syncTextSelectionToRange(textSelection);
        })
        .catch(console.error);

      next();
    })
    .run();

  if (success) next();
};
