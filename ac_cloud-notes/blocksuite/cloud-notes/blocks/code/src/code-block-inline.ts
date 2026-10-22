import { CommentInlineSpecExtension } from '@blocksuite/cloud-notes-inline-comment';
import { LatexInlineSpecExtension } from '@blocksuite/cloud-notes-inline-latex';
import { LinkInlineSpecExtension } from '@blocksuite/cloud-notes-inline-link';
import {
  BackgroundInlineSpecExtension,
  BoldInlineSpecExtension,
  CodeInlineSpecExtension,
  ColorInlineSpecExtension,
  ItalicInlineSpecExtension,
  StrikeInlineSpecExtension,
  UnderlineInlineSpecExtension,
} from '@blocksuite/cloud-notes-inline-preset';
import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import {
  InlineManagerExtension,
  InlineSpecExtension,
} from '@blocksuite/std/inline';
import { html } from 'lit';
import { z } from 'zod';

export const CodeBlockUnitSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'code-block-unit',
    schema: z.object({
      'code-block-uint': z.undefined(),
    }),
    match: () => true,
    renderer: ({ delta }) => {
      return html`<cloud-notes-code-unit .delta=${delta}></cloud-notes-code-unit>`;
    },
  });

export const CodeBlockInlineManagerExtension =
  InlineManagerExtension<CloudNotesTextAttributes>({
    id: 'CodeBlockInlineManager',
    enableMarkdown: false,
    specs: [
      BoldInlineSpecExtension.identifier,
      ItalicInlineSpecExtension.identifier,
      UnderlineInlineSpecExtension.identifier,
      StrikeInlineSpecExtension.identifier,
      CodeInlineSpecExtension.identifier,
      BackgroundInlineSpecExtension.identifier,
      ColorInlineSpecExtension.identifier,
      LatexInlineSpecExtension.identifier,
      LinkInlineSpecExtension.identifier,
      CodeBlockUnitSpecExtension.identifier,
      CommentInlineSpecExtension.identifier,
    ],
  });
