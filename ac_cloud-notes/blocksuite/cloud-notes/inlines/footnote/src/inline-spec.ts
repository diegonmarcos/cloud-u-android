import { FootNoteSchema } from '@blocksuite/cloud-notes-model';
import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes-shared/types';
import { StdIdentifier } from '@blocksuite/std';
import { InlineSpecExtension } from '@blocksuite/std/inline';
import { html } from 'lit';
import z from 'zod';

import { FootNoteNodeConfigIdentifier } from './footnote-node/footnote-config';

export const FootNoteInlineSpecExtension =
  InlineSpecExtension<CloudNotesTextAttributes>('footnote', provider => {
    const std = provider.get(StdIdentifier);
    const config =
      provider.getOptional(FootNoteNodeConfigIdentifier) ?? undefined;
    return {
      name: 'footnote',
      schema: z.object({
        footnote: FootNoteSchema.optional().nullable().catch(undefined),
      }),
      match: delta => {
        return !!delta.attributes?.footnote;
      },
      renderer: ({ delta }) => {
        return html`<cloud-notes-footnote-node
          .delta=${delta}
          .std=${std}
          .config=${config}
        ></cloud-notes-footnote-node>`;
      },
      embed: true,
    };
  });
