import {
  EmbedSyncedDocBlockSchema,
  type EmbedSyncedDocModel,
} from '@blocksuite/cloud-notes-model';
import { type BlockStdScope, ConfigExtensionFactory } from '@blocksuite/std';
import type { TemplateResult } from 'lit';

export type EmbedSyncedDocConfig = {
  edgelessHeader: (context: {
    model: EmbedSyncedDocModel;
    std: BlockStdScope;
  }) => TemplateResult;
};

export const EmbedSyncedDocConfigExtension =
  ConfigExtensionFactory<EmbedSyncedDocConfig>(
    EmbedSyncedDocBlockSchema.model.flavour
  );
