import {
  IconButton,
  MenuItem,
  MenuSeparator,
  toast,
  useConfirmModal,
} from '@cloud-notes/component';
import { usePageHelper } from '@cloud-notes/core/blocksuite/block-suite-page-list/utils';
import { Guard } from '@cloud-notes/core/components/guard';
import { useAppSettingHelper } from '@cloud-notes/core/components/hooks/cloud-notes/use-app-setting-helper';
import { useBlockSuiteMetaHelper } from '@cloud-notes/core/components/hooks/cloud-notes/use-block-suite-meta-helper';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { IsFavoriteIcon } from '@cloud-notes/core/components/pure/icons';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { CompatibleFavoriteItemsAdapter } from '@cloud-notes/core/modules/favorite';
import { GuardService } from '@cloud-notes/core/modules/permissions';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import {
  DeleteIcon,
  DuplicateIcon,
  InformationIcon,
  LinkedPageIcon,
  OpenInNewIcon,
  PlusIcon,
  SplitViewIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useServices } from '@toeverything/infra';
import { useCallback, useMemo, useState } from 'react';

import type { NodeOperation } from '../../tree/types';

export const useNavigationPanelDocNodeOperations = (
  docId: string,
  options: {
    openInfoModal: () => void;
    openNodeCollapsed: () => void;
  }
): NodeOperation[] => {
  const t = useI18n();
  const {
    workbenchService,
    workspaceService,
    docsService,
    compatibleFavoriteItemsAdapter,
    guardService,
  } = useServices({
    DocsService,
    WorkbenchService,
    WorkspaceService,
    CompatibleFavoriteItemsAdapter,
    GuardService,
  });
  const { openConfirmModal } = useConfirmModal();

  const [addLinkedPageLoading, setAddLinkedPageLoading] = useState(false);
  const docRecord = useLiveData(docsService.list.doc$(docId));
  const { appSettings } = useAppSettingHelper();

  const { createPage } = usePageHelper(
    workspaceService.workspace.docCollection
  );

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
  const handleOpenInfoModal = useCallback(() => {
    track.$.docInfoPanel.$.open();
    options.openInfoModal();
  }, [options]);

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
    track.$.navigationPanel.docs.openDoc();
    track.$.navigationPanel.organize.openInNewTab({
      type: 'doc',
    });
  }, [docId, workbenchService]);

  const handleOpenInSplitView = useCallback(() => {
    workbenchService.workbench.openDoc(docId, {
      at: 'beside',
    });
    track.$.navigationPanel.docs.openDoc();
    track.$.navigationPanel.organize.openInSplitView({
      type: 'doc',
    });
  }, [docId, workbenchService.workbench]);

  const handleAddLinkedPage = useAsyncCallback(async () => {
    setAddLinkedPageLoading(true);
    try {
      const canEdit = await guardService.can('Doc_Update', docId);
      if (!canEdit) {
        toast(t['com.cloud-notes.no-permission']());
        return;
      }
      const newDoc = createPage();
      // TODO: handle timeout & error
      await docsService.addLinkedDoc(docId, newDoc.id);
      track.$.navigationPanel.docs.createDoc({ control: 'linkDoc' });
      track.$.navigationPanel.docs.linkDoc({ control: 'createDoc' });
      options.openNodeCollapsed();
    } finally {
      setAddLinkedPageLoading(false);
    }
  }, [createPage, guardService, docId, docsService, options, t]);

  const handleToggleFavoriteDoc = useCallback(() => {
    compatibleFavoriteItemsAdapter.toggle(docId, 'doc');
    track.$.navigationPanel.organize.toggleFavorite({
      type: 'doc',
    });
  }, [docId, compatibleFavoriteItemsAdapter]);

  return useMemo(
    () => [
      ...(appSettings.showLinkedDocInSidebar
        ? [
            {
              index: 0,
              inline: true,
              view: (
                <IconButton
                  size="16"
                  icon={<PlusIcon />}
                  tooltip={t[
                    'com.cloud-notes.rootAppSidebar.explorer.doc-add-tooltip'
                  ]()}
                  onClick={handleAddLinkedPage}
                  loading={addLinkedPageLoading}
                  disabled={addLinkedPageLoading}
                />
              ),
            },
          ]
        : []),
      {
        index: 50,
        view: (
          <MenuItem
            prefixIcon={<InformationIcon />}
            onClick={handleOpenInfoModal}
          >
            {t['com.cloud-notes.page-properties.page-info.view']()}
          </MenuItem>
        ),
      },
      {
        index: 99,
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
        index: 99,
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
      ...(BUILD_CONFIG.isElectron
        ? [
            {
              index: 100,
              view: (
                <MenuItem
                  prefixIcon={<SplitViewIcon />}
                  onClick={handleOpenInSplitView}
                >
                  {t['com.cloud-notes.workbench.split-view.page-menu-open']()}
                </MenuItem>
              ),
            },
          ]
        : []),
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
      addLinkedPageLoading,
      appSettings.showLinkedDocInSidebar,
      docId,
      favorite,
      handleAddLinkedPage,
      handleDuplicate,
      handleMoveToTrash,
      handleOpenInNewTab,
      handleOpenInSplitView,
      handleOpenInfoModal,
      handleToggleFavoriteDoc,
      t,
    ]
  );
};
