import {
  IconButton,
  MenuItem,
  MenuSeparator,
  notify,
  useConfirmModal,
} from '@cloud-notes/component';
import { usePageHelper } from '@cloud-notes/core/blocksuite/block-suite-page-list/utils';
import { IsFavoriteIcon } from '@cloud-notes/core/components/pure/icons';
import type { NodeOperation } from '@cloud-notes/core/desktop/components/navigation-panel';
import { CollectionService } from '@cloud-notes/core/modules/collection';
import { CompatibleFavoriteItemsAdapter } from '@cloud-notes/core/modules/favorite';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import {
  DeleteIcon,
  FilterIcon,
  OpenInNewIcon,
  PlusIcon,
  SplitViewIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useServices } from '@toeverything/infra';
import { useCallback, useMemo } from 'react';

import { MobileNavigationMenuItems } from '../../menu-host';
import { CollectionRenameSubMenu } from './dialog';

export const useNavigationPanelCollectionAddDoc = (
  collectionId: string,
  onOpenCollapsed: () => void
) => {
  const t = useI18n();
  const { workspaceService, collectionService } = useServices({
    WorkspaceService,
    CollectionService,
  });
  const { createPage } = usePageHelper(
    workspaceService.workspace.docCollection
  );
  const { openConfirmModal } = useConfirmModal();
  const createAndAddDocument = useCallback(() => {
    const newDoc = createPage();
    collectionService.addDocToCollection(collectionId, newDoc.id);
    track.$.navigationPanel.collections.createDoc();
    track.$.navigationPanel.collections.addDocToCollection({
      control: 'button',
    });
    onOpenCollapsed();
  }, [collectionId, collectionService, createPage, onOpenCollapsed]);
  return useCallback(() => {
    openConfirmModal({
      title: t['com.cloud-notes.collection.add-doc.confirm.title'](),
      description: t['com.cloud-notes.collection.add-doc.confirm.description'](),
      cancelText: t['Cancel'](),
      confirmText: t['Confirm'](),
      confirmButtonOptions: { variant: 'primary' },
      onConfirm: createAndAddDocument,
    });
  }, [createAndAddDocument, openConfirmModal, t]);
};

export const useNavigationPanelCollectionNodeOperations = (
  collectionId: string,
  handleAddDocToCollection: () => void,
  onOpenEdit: () => void
) => {
  const t = useI18n();
  const {
    workbenchService,
    collectionService,
    compatibleFavoriteItemsAdapter,
  } = useServices({
    WorkbenchService,
    CollectionService,
    CompatibleFavoriteItemsAdapter,
  });

  const favorite = useLiveData(
    useMemo(
      () =>
        compatibleFavoriteItemsAdapter.isFavorite$(collectionId, 'collection'),
      [collectionId, compatibleFavoriteItemsAdapter]
    )
  );
  const handleToggleFavoriteCollection = useCallback(() => {
    compatibleFavoriteItemsAdapter.toggle(collectionId, 'collection');
    track.$.navigationPanel.organize.toggleFavorite({
      type: 'collection',
    });
  }, [compatibleFavoriteItemsAdapter, collectionId]);

  const handleOpenInSplitView = useCallback(() => {
    workbenchService.workbench.openCollection(collectionId, { at: 'beside' });
    track.$.navigationPanel.organize.openInSplitView({
      type: 'collection',
    });
  }, [collectionId, workbenchService.workbench]);

  const handleOpenInNewTab = useCallback(() => {
    workbenchService.workbench.openCollection(collectionId, { at: 'new-tab' });
    track.$.navigationPanel.organize.openInNewTab({ type: 'collection' });
  }, [collectionId, workbenchService.workbench]);

  const handleDeleteCollection = useCallback(() => {
    collectionService.deleteCollection(collectionId);
    track.$.navigationPanel.organize.deleteOrganizeItem({
      type: 'collection',
    });
  }, [collectionId, collectionService]);

  const handleShowEdit = useCallback(() => {
    onOpenEdit();
  }, [onOpenEdit]);

  const handleRename = useCallback(
    (name: string) => {
      const collection = collectionService.collection$(collectionId).value;
      if (collection && collection.name$.value !== name) {
        collectionService.updateCollection(collectionId, {
          name,
        });

        track.$.navigationPanel.organize.renameOrganizeItem({
          type: 'collection',
        });
        notify.success({ message: t['com.cloud-notes.toastMessage.rename']() });
      }
    },
    [collectionId, collectionService, t]
  );

  return useMemo(
    () => ({
      favorite,
      handleAddDocToCollection,
      handleDeleteCollection,
      handleOpenInNewTab,
      handleOpenInSplitView,
      handleShowEdit,
      handleToggleFavoriteCollection,
      handleRename,
    }),
    [
      favorite,
      handleAddDocToCollection,
      handleDeleteCollection,
      handleOpenInNewTab,
      handleOpenInSplitView,
      handleRename,
      handleShowEdit,
      handleToggleFavoriteCollection,
    ]
  );
};

export const useNavigationPanelCollectionNodeOperationsMenu = (
  collectionId: string,
  handleAddDocToCollection: () => void,
  onOpenEdit: () => void
): NodeOperation[] => {
  const t = useI18n();

  const {
    favorite,
    handleAddDocToCollection: addDocToCollection,
    handleDeleteCollection,
    handleOpenInNewTab,
    handleOpenInSplitView,
    handleShowEdit,
    handleToggleFavoriteCollection,
    handleRename,
  } = useNavigationPanelCollectionNodeOperations(
    collectionId,
    handleAddDocToCollection,
    onOpenEdit
  );

  return useMemo(
    () => [
      {
        index: 0,
        inline: true,
        view: (
          <IconButton
            size="16"
            onClick={addDocToCollection}
            tooltip={t[
              'com.cloud-notes.rootAppSidebar.explorer.collection-add-tooltip'
            ]()}
          >
            <PlusIcon />
          </IconButton>
        ),
      },
      {
        index: 10,
        view: <CollectionRenameSubMenu onConfirm={handleRename} />,
      },
      {
        index: 11,
        view: <MenuSeparator />,
      },
      {
        index: 99,
        view: (
          <MenuItem prefixIcon={<FilterIcon />} onClick={handleShowEdit}>
            {t['com.cloud-notes.collection.menu.edit']()}
          </MenuItem>
        ),
      },
      {
        index: 99,
        view: (
          <MenuItem prefixIcon={<PlusIcon />} onClick={addDocToCollection}>
            {t['New Page']()}
          </MenuItem>
        ),
      },
      {
        index: 99,
        view: (
          <MenuItem
            prefixIcon={<IsFavoriteIcon favorite={favorite} />}
            onClick={handleToggleFavoriteCollection}
          >
            {favorite
              ? t['com.cloud-notes.favoritePageOperation.remove']()
              : t['com.cloud-notes.favoritePageOperation.add']()}
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
              index: 99,
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
        index: 9999,
        view: <MenuSeparator key="menu-separator" />,
      },
      {
        index: 10000,
        view: (
          <MenuItem
            type={'danger'}
            prefixIcon={<DeleteIcon />}
            onClick={handleDeleteCollection}
          >
            {t['Delete']()}
          </MenuItem>
        ),
      },
    ],
    [
      favorite,
      addDocToCollection,
      handleDeleteCollection,
      handleOpenInNewTab,
      handleOpenInSplitView,
      handleRename,
      handleShowEdit,
      handleToggleFavoriteCollection,
      t,
    ]
  );
};

export const NavigationPanelCollectionNodeMenu = ({
  collectionId,
  handleAddDocToCollection,
  onOpenEdit,
  additionalOperations,
}: {
  collectionId: string;
  handleAddDocToCollection: () => void;
  onOpenEdit: () => void;
  additionalOperations?: NodeOperation[];
}) => {
  const operations = useNavigationPanelCollectionNodeOperationsMenu(
    collectionId,
    handleAddDocToCollection,
    onOpenEdit
  );
  const allOperations = useMemo(
    () => [...(additionalOperations ?? []), ...operations],
    [additionalOperations, operations]
  );
  return <MobileNavigationMenuItems operations={allOperations} />;
};
