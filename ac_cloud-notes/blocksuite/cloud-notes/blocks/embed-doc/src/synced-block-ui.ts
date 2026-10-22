import {
  copyAsSynced,
  pasteAsSynced,
  syncBlockToDoc,
  syncedClipboard,
} from '@blocksuite/cloud-notes-block-paragraph';
import { toast } from '@blocksuite/cloud-notes-components/toast';
import { ParagraphBlockModel } from '@blocksuite/cloud-notes-model';
import {
  type ToolbarContext,
  ToolbarModuleExtension,
} from '@blocksuite/cloud-notes-shared/services';
import { SlashMenuConfigExtension } from '@blocksuite/cloud-notes-widget-slash-menu';
import { CopyIcon, DualLinkIcon } from '@blocksuite/icons/lit';
import { BlockFlavourIdentifier } from '@blocksuite/std';
import type { ExtensionType } from '@blocksuite/store';

const MAX_TARGETS = 8;

/** `/synced`: paste the block most recently copied as synced right here. */
export const SyncedBlockSlashMenuExtension: ExtensionType =
  SlashMenuConfigExtension('affine:synced-block', {
    items: [
      {
        name: 'Synced Block',
        description: 'Paste the block you copied as synced; edits flow both ways.',
        icon: DualLinkIcon(),
        searchAlias: ['synced', 'sync', 'mirror', 'linked block'],
        group: '3_Page@3',
        action: ({ std, model }) => {
          const store = std.store;
          if (!syncedClipboard.get()) {
            toast(std.host, 'Copy a block as synced first (block toolbar)');
            return;
          }
          if (!pasteAsSynced(store, model.id)) {
            toast(std.host, 'The copied block is no longer available');
            return;
          }
          // the empty line the slash was typed on is replaced by the synced block
          const text = (model as any).props?.text;
          if (text && text.length === 0) store.deleteBlock(model);
        },
      },
    ],
  });

/** Block toolbar on a paragraph: copy as synced, paste as synced, sync to another doc. */
export const SyncedBlockToolbarExtension: ExtensionType = ToolbarModuleExtension({
  id: BlockFlavourIdentifier('affine:paragraph'),
  config: {
    actions: [
      {
        generate(ctx) {
          const model = ctx.getCurrentModelByType(ParagraphBlockModel);
          if (!model) return null;
          const targets = ctx.store.readonly
            ? []
            : ctx.store.workspace.meta.docMetas
                .filter(meta => meta.id !== ctx.store.id)
                .slice(0, MAX_TARGETS);
          return {
            actions: [
              {
                id: 'copy-as-synced',
                label: 'Copy as synced block',
                icon: CopyIcon(),
                run(ctx) {
                  const ok = copyAsSynced(ctx.store, model.id);
                  toast(
                    ctx.host,
                    ok
                      ? 'Copied as synced - use Paste as synced block or /synced'
                      : 'This block cannot be synced'
                  );
                },
              },
              {
                id: 'paste-as-synced',
                label: 'Paste as synced block',
                disabled: ctx => ctx.store.readonly || !syncedClipboard.get(),
                run(ctx) {
                  if (!pasteAsSynced(ctx.store, model.id)) {
                    toast(ctx.host, 'Copy a block as synced first');
                  }
                },
              },
              ...targets.map(target => ({
                id: `sync-to-${target.id}`,
                label: `Sync to ${target.title || 'Untitled'}`,
                run: (ctx: ToolbarContext) =>
                  syncTo(ctx, model.id, target.id, target.title),
              })),
            ],
          };
        },
        id: 'z.synced-block',
      } as any,
    ],
  },
});

function syncTo(ctx: ToolbarContext, blockId: string, docId: string, title?: string) {
  const target = ctx.store.workspace.getDoc(docId)?.getStore({ id: docId });
  const id = target && syncBlockToDoc(ctx.store, blockId, target);
  toast(
    ctx.host,
    id ? `Synced to ${title || 'Untitled'}` : 'Could not sync to that doc'
  );
}

export const SyncedBlockUIExtensions: ExtensionType[] = [
  SyncedBlockSlashMenuExtension,
  SyncedBlockToolbarExtension,
];
