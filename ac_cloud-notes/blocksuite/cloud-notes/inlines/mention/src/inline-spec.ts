import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import { StdIdentifier } from '@blocksuite/std';
import { InlineSpecExtension } from '@blocksuite/std/inline';
import { html } from 'lit';
import { z } from 'zod';

export const MentionInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>('mention', provider => {
    const std = provider.get(StdIdentifier);
    return {
      name: 'mention',
      schema: z.object({
        mention: z
          .object({
            member: z.string(),
            notification: z.string().optional(),
          })
          .optional()
          .nullable()
          .catch(undefined),
      }),
      match: delta => {
        return !!delta.attributes?.mention?.member;
      },
      renderer: ({ delta, selected }) => {
        return html`<cloud-notes-mention
          .delta=${delta}
          .std=${std}
          .selected=${selected}
        ></cloud-notes-mention>`;
      },
      embed: true,
    };
  });
