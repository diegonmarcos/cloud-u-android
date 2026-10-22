import {
  MenuItem,
  MenuSeparator,
  MenuSub,
  toast,
  useConfirmModal,
} from '@cloud-notes/component';
import { usePageHelper } from '@cloud-notes/core/blocksuite/block-suite-page-list/utils';
import { Guard } from '@cloud-notes/core/components/guard';
import { useBlockSuiteMetaHelper } from '@cloud-notes/core/components/hooks/cloud-notes/use-block-suite-meta-helper';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { IsFavoriteIcon } from '@cloud-notes/core/components/pure/icons';
import type { NodeOperation } from '@cloud-notes/core/desktop/components/navigation-panel';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { CompatibleFavoriteItemsAdapter } from '@cloud-notes/core/modules/favorite';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { preventDefault } from '@cloud-notes/core/utils';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import {
  DeleteIcon,
  DuplicateIcon,
  InformationIcon,
  LinkedPageIcon,
  OpenInNewIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useService, useServices } from '@toeverything/infra';
import { useCallback, useMemo } from 'react';

import { DocFrameScope, DocInfoSheet } from '../../../doc-info';
import { MobileNavigationMenuItems } from '../../menu-host';
import { DocRenameSubMenu } from './dialog';

export const useNavigationPanelDocNodeAddLinkedPage = (
  docId: string,
  openNodeCollapsed: () => void
) => {
  const { docsService, workspaceService } = useServices({
    DocsService,
    WorkspaceService,
  });
  const { createPage } = usePageHelper(
    workspaceService.workspace.docCollection
  );
  return useAsyncCallback(async () => {
    const newDoc = createPage();
    await docsService.addLinkedDoc(docId, newDoc.id);
    track.$.navigationPanel.docs.createDoc({ control: 'linkDoc' });
    track.$.navigationPanel.docs.linkDoc({ control: 'createDoc' });
    openNodeCollapsed();
  }, [createPage, docId, docsService, openNodeCollapsed]);
};

export const useNavigationPanelDocNodeOperations = (docId: string) => {
  const t = useI18n();
  const { workbenchService, docsService, compatibleFavoriteItemsAdapter } =
    useServices({
      DocsService,
      WorkbenchService,
      CompatibleFavoriteItemsAdapter,
    });

  const { openConfirmModal } = useConfirmModal();

  const docRecord = useLiveData(docsService.list.doc$(docId));

  const favorite = useLiveData(
    useMemo(() => {
      return compatibleFavoriteItemsAdapter.isFavorite$(docId, 'doc');
    }, [docId, compatibleFavoriteItemsAdapter])
  );

  const { duplicate } = useBlockSuiteMetaHelper();
  const handleDuplicate = useCallback(() => {
    duplicate(docId, true);
    track.$.navigationPanel.docs.createDoc();
  }, [docId, duplicate]);

  const handleMoveToTrash = useCallback(() => {
    if (!docRecord) {
      return;
    }
    openConfirmModal({
      title: t['com.cloud-notes.moveToTrash.title'](),
      description: t['com.cloud-notes.moveToTrash.confirmModal.description']({
        title: docRecord.title$.value,
      }),
      confirmText: t['com.cloud-notes.moveToTrash.confirmModal.confirm'](),
      cancelText: t['com.cloud-notes.moveToTrash.confirmModal.cancel'](),
      confirmButtonOptions: {
        variant: 'error',
      },
      async onConfirm() {
        await docRecord.moveToTrash();
        track.$.navigationPanel.docs.deleteDoc({
          control: 'button',
        });
        toast(t['com.cloud-notes.toastMessage.movedTrash']());
      },
    });
  }, [docRecord, openConfirmModal, t]);

  const handleOpenInNewTab = useCallback(() => {
    workbenchService.workbench.openDoc(docId, {
      at: 'new-tab',
    });
    track.$.navigationPanel.organize.openInNewTab({
      type: 'doc',
    });
  }, [docId, workbenchService]);

  const handleOpenInSplitView = useCallback(() => {
    workbenchService.workbench.openDoc(docId, {
      at: 'beside',
    });
    track.$.navigationPanel.organize.openInSplitView({
      type: 'doc',
    });
  }, [docId, workbenchService.workbench]);

  const handleToggleFavoriteDoc = useCallback(() => {
    compatibleFavoriteItemsAdapter.toggle(docId, 'doc');
    track.$.navigationPanel.organize.toggleFavorite({
      type: 'doc',
    });
  }, [docId, compatibleFavoriteItemsAdapter]);

  const handleRename = useAsyncCallback(
    async (newName: string) => {
      await docsService.changeDocTitle(docId, newName);
      track.$.navigationPanel.organize.renameOrganizeItem({ type: 'doc' });
    },
    [docId, docsService]
  );

  return useMemo(
    () => ({
      favorite,
      handleDuplicate,
      handleToggleFavoriteDoc,
      handleOpenInSplitView,
      handleOpenInNewTab,
      handleMoveToTrash,
      handleRename,
    }),
    [
      favorite,
      handleDuplicate,
      handleMoveToTrash,
      handleOpenInNewTab,
      handleOpenInSplitView,
      handleRename,
      handleToggleFavoriteDoc,
    ]
  );
};

export const useNavigationPanelDocNodeOperationsMenu = (
  docId: string,
  options: {
    handleAddLinkedPage: () => void;
  }
): {
  operations: NodeOperation[];
  handleAddLinkedPage: () => void;
} => {
  const t = useI18n();
  const { handleAddLinkedPage } = options;
  const {
    favorite,
    handleDuplicate,
    handleToggleFavoriteDoc,
    handleOpenInNewTab,
    handleMoveToTrash,
    handleRename,
  } = useNavigationPanelDocNodeOperations(docId);

  const docService = useService(DocsService);
  const docRecord = useLiveData(docService.list.doc$(docId));
  const title = useLiveData(docRecord?.title$);

  const operations = useMemo(
    () => [
      {
        index: 10,
        view: (
          <Guard docId={docId} permission="Doc_Update">
            {canEdit => (
              <DocRenameSubMenu
                onConfirm={handleRename}
                initialName={title}
                disabled={!canEdit}
              />
            )}
          </Guard>
        ),
      },
      {
        index: 11,
        view: <MenuSeparator />,
      },
      {
        index: 50,
        view: (
          <MenuSub
            triggerOptions={{
              prefixIcon: <InformationIcon />,
              onClick: preventDefault,
            }}
            title={title ?? t['unnamed']()}
            items={
              <DocFrameScope docId={docId}>
                <DocInfoSheet docId={docId} />
              </DocFrameScope>
            }
          >
            <span>{t['com.cloud-notes.page-properties.page-info.view']()}</span>
          </MenuSub>
        ),
      },
      {
        index: 97,
        view: (
          <Guard docId={docId} permission="Doc_Update">
            {canEdit => (
              <MenuItem
                prefixIcon={<LinkedPageIcon />}
                onClick={handleAddLinkedPage}
                disabled={!canEdit}
              >
                {t['com.cloud-notes.page-operation.add-linked-page']()}
              </MenuItem>
            )}
          </Guard>
        ),
      },
      {
        index: 98,
        view: (
          <MenuItem prefixIcon={<DuplicateIcon />} onClick={handleDuplicate}>
            {t['com.cloud-notes.header.option.duplicate']()}
          </MenuItem>
        ),
      },
      {
        index: 99,
        view: (
          <MenuItem prefixIcon={<OpenInNewIcon />} onClick={handleOpenInNewTab}>
            {t['com.cloud-notes.workbench.tab.page-menu-open']()}
          </MenuItem>
        ),
      },
      {
        index: 199,
        view: (
          <MenuItem
            prefixIcon={<IsFavoriteIcon favorite={favorite} />}
            onClick={handleToggleFavoriteDoc}
          >
            {favorite
              ? t['com.cloud-notes.favoritePageOperation.remove']()
              : t['com.cloud-notes.favoritePageOperation.add']()}
          </MenuItem>
        ),
      },
      {
        index: 9999,
        view: <MenuSeparator key="menu-separator" />,
      },
      {
        index: 10000,
        view: (
          <Guard docId={docId} permission="Doc_Trash">
            {canMoveToTrash => (
              <MenuItem
                type={'danger'}
                prefixIcon={<DeleteIcon />}
                onClick={handleMoveToTrash}
                disabled={!canMoveToTrash}
              >
                {t['com.cloud-notes.moveToTrash.title']()}
              </MenuItem>
            )}
          </Guard>
        ),
      },
    ],
    [
      docId,
      favorite,
      handleAddLinkedPage,
      handleDuplicate,
      handleMoveToTrash,
      handleOpenInNewTab,
      handleRename,
      handleToggleFavoriteDoc,
      t,
      title,
    ]
  );

  return useMemo(
    () => ({ operations, handleAddLinkedPage }),
    [handleAddLinkedPage, operations]
  );
};

export const NavigationPanelDocNodeMenu = ({
  docId,
  handleAddLinkedPage,
  additionalOperations,
}: {
  docId: string;
  handleAddLinkedPage: () => void;
  additionalOperations?: NodeOperation[];
}) => {
  const { operations } = useNavigationPanelDocNodeOperationsMenu(docId, {
    handleAddLinkedPage,
  });
  const allOperations = useMemo(
    () => [...operations, ...(additionalOperations ?? [])],
    [additionalOperations, operations]
  );
  return <MobileNavigationMenuItems operations={allOperations} />;
};
