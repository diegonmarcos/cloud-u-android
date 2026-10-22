import { insertEmbedCard } from '@blocksuite/cloud-notes-block-embed';
import type { EmbedCardStyle, ReferenceParams } from '@blocksuite/cloud-notes-model';
import type { Command } from '@blocksuite/std';

export type LinkableFlavour =
  | 'cloud-notes:bookmark'
  | 'cloud-notes:embed-linked-doc'
  | 'cloud-notes:embed-synced-doc'
  | 'cloud-notes:embed-iframe'
  | 'cloud-notes:embed-figma'
  | 'cloud-notes:embed-github'
  | 'cloud-notes:embed-loom'
  | 'cloud-notes:embed-youtube';

export type InsertedLinkType = {
  flavour: LinkableFlavour;
} | null;

export const insertEmbedLinkedDocCommand: Command<
  {
    docId: string;
    params?: ReferenceParams;
  },
  { blockId: string }
> = (ctx, next) => {
  const { docId, params, std } = ctx;
  const flavour = 'cloud-notes:embed-linked-doc';
  const targetStyle: EmbedCardStyle = 'vertical';
  const props: Record<string, unknown> = { pageId: docId };
  if (params) props.params = params;
  const blockId = insertEmbedCard(std, { flavour, targetStyle, props });
  if (!blockId) return;
  next({ blockId });
};
