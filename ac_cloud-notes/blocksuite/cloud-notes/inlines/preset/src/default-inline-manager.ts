import { CommentInlineSpecExtension } from '@blocksuite/cloud-notes-inline-comment';
import { FootNoteInlineSpecExtension } from '@blocksuite/cloud-notes-inline-footnote';
import { LatexInlineSpecExtension } from '@blocksuite/cloud-notes-inline-latex';
import { LinkInlineSpecExtension } from '@blocksuite/cloud-notes-inline-link';
import { MentionInlineSpecExtension } from '@blocksuite/cloud-notes-inline-mention';
import { ReferenceInlineSpecExtension } from '@blocksuite/cloud-notes-inline-reference';
import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import { InlineManagerExtension } from '@blocksuite/std/inline';

import {
  BackgroundInlineSpecExtension,
  BoldInlineSpecExtension,
  CodeInlineSpecExtension,
  ColorInlineSpecExtension,
  ItalicInlineSpecExtension,
  StrikeInlineSpecExtension,
  UnderlineInlineSpecExtension,
} from './inline-spec';

export const DefaultInlineManagerExtension =
  InlineManagerExtension<CloudNotesTextAttributes>({
    id: 'DefaultInlineManager',
    specs: [
      BoldInlineSpecExtension.identifier,
      ItalicInlineSpecExtension.identifier,
      UnderlineInlineSpecExtension.identifier,
      StrikeInlineSpecExtension.identifier,
      CodeInlineSpecExtension.identifier,
      BackgroundInlineSpecExtension.identifier,
      ColorInlineSpecExtension.identifier,
      LatexInlineSpecExtension.identifier,
      ReferenceInlineSpecExtension.identifier,
      LinkInlineSpecExtension.identifier,
      FootNoteInlineSpecExtension.identifier,
      MentionInlineSpecExtension.identifier,
      CommentInlineSpecExtension.identifier,
    ],
  });
