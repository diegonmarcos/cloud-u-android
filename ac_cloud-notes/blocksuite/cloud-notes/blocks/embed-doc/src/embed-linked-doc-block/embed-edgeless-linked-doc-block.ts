import {
  createEmbedEdgelessBlockInteraction,
  toEdgelessEmbedBlock,
} from '@blocksuite/cloud-notes-block-embed';
import {
  EdgelessCRUDIdentifier,
  reassociateConnectorsCommand,
} from '@blocksuite/cloud-notes-block-surface';
import { EmbedLinkedDocBlockSchema } from '@blocksuite/cloud-notes-model';
import {
  EMBED_CARD_HEIGHT,
  EMBED_CARD_WIDTH,
} from '@blocksuite/cloud-notes-shared/consts';
import {
  cloneReferenceInfoWithoutAliases,
  isNewTabTrigger,
  isNewViewTrigger,
} from '@blocksuite/cloud-notes-shared/utils';
import { Bound } from '@blocksuite/global/gfx';

import { EmbedLinkedDocBlockComponent } from './embed-linked-doc-block.js';

export class EmbedEdgelessLinkedDocBlockComponent extends toEdgelessEmbedBlock(
  EmbedLinkedDocBlockComponent
) {
  override convertToEmbed = () => {
    const { caption, xywh } = this.model.props;
    const { store, id } = this.model;

    const style = 'syncedDoc';
    const bound = Bound.deserialize(xywh);
    bound.w = EMBED_CARD_WIDTH[style];
    bound.h = EMBED_CARD_HEIGHT[style];

    const { addBlock } = this.std.get(EdgelessCRUDIdentifier);
    const surface = this.gfx.surface ?? undefined;
    const newId = addBlock(
      'affine:embed-synced-doc',
      {
        xywh: bound.serialize(),
        caption,
        ...cloneReferenceInfoWithoutAliases(this.referenceInfo$.peek()),
      },
      surface
    );

    this.std.command.exec(reassociateConnectorsCommand, {
      oldId: id,
      newId,
    });

    this.gfx.selection.set({
      editing: false,
      elements: [newId],
    });

    store.deleteBlock(this.model);
  };

  protected override _handleClick = (evt: MouseEvent): void => {
    if (isNewTabTrigger(evt)) {
      this.open({ openMode: 'open-in-new-tab', event: evt });
    } else if (isNewViewTrigger(evt)) {
      this.open({ openMode: 'open-in-new-view', event: evt });
    }
  };
}

export const EmbedLinkedDocInteraction = createEmbedEdgelessBlockInteraction(
  EmbedLinkedDocBlockSchema.model.flavour
);
