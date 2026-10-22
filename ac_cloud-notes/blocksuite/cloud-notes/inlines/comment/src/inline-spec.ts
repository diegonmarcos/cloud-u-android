import { type CommentId } from '@blocksuite/cloud-notes-shared/services';
import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import { dynamicSchema, InlineSpecExtension } from '@blocksuite/std/inline';
import { html, nothing } from 'lit-html';
import { when } from 'lit-html/directives/when.js';
import { z } from 'zod';

import { extractCommentIdFromDelta } from './utils';

type InlineCommendId = `comment-${CommentId}`;
function isInlineCommendId(key: string): key is InlineCommendId {
  return key.startsWith('comment-');
}

export const CommentInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'comment',
    schema: dynamicSchema(
      isInlineCommendId,
      z.boolean().optional().nullable().catch(undefined)
    ),
    match: delta => {
      if (!delta.attributes) return false;
      const comments = Object.keys(delta.attributes).filter(isInlineCommendId);
      return comments.length > 0;
    },
    renderer: ({ delta, children }) => {
      if (!delta.attributes) return html`${nothing}`;

      const unresolved = Object.entries(delta.attributes).some(
        ([key, value]) => isInlineCommendId(key) && value === true
      );
      return html`<inline-comment
        .unresolved=${unresolved}
        .commentIds=${extractCommentIdFromDelta(delta)}
        >${when(
          children,
          () => html`${children}`,
          () => nothing
        )}</inline-comment
      >`;
    },
    wrapper: true,
  });

export const NullCommentInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>({
    name: 'comment',
    schema: dynamicSchema(
      isInlineCommendId,
      z.boolean().optional().nullable().catch(undefined)
    ),
    match: () => false,
    renderer: () => html``,
  });

// reuse the same identifier
NullCommentInlineSpecExtension.identifier =
  CommentInlineSpecExtension.identifier;
