import {
  IconButton,
  type IconButtonProps,
  Menu,
  MenuItem,
  type MenuProps,
  useConfirmModal,
} from '@cloud-notes/component';
import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { CompatibleFavoriteItemsAdapter } from '@cloud-notes/core/modules/favorite';
import { GuardService } from '@cloud-notes/core/modules/permissions';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { useI18n } from '@cloud-notes/i18n';
import track from '@cloud-notes/track';
import {
  DeleteIcon,
  DuplicateIcon,
  InformationIcon,
  MoreVerticalIcon,
  OpenInNewIcon,
  SplitViewIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useContext } from 'react';

import { useBlockSuiteMetaHelper } from '../../hooks/cloud-notes/use-block-suite-meta-helper';
import { IsFavoriteIcon } from '../../pure/icons';
import { DocExplorerContext } from '../context';

interface DocOperationProps {
  docId: string;
}

/**
 * Favorite Operation
 */
const ToggleFavorite = ({ docId }: DocOperationProps) => {
  const t = useI18n();
  const favAdapter = useService(CompatibleFavoriteItemsAdapter);
  const favourite = useLiveData(favAdapter.isFavorite$(docId, 'doc'));

  const toggleFavorite = useCallback(() => {
    favAdapter.toggle(docId, 'doc');
    track.allDocs.list.docMenu.toggleFavorite();
  }, [docId, favAdapter]);

  return (
    <MenuItem
      prefixIcon={<IsFavoriteIcon favorite={favourite} />}
      onClick={toggleFavorite}
      data-testid="doc-list-operation-favorite"
    >
      {favourite
        ? t['com.cloud-notes.favoritePageOperation.remove']()
        : t['com.cloud-notes.favoritePageOperation.add']()}
    </MenuItem>
  );
};

/**
 * Doc Info Operation
 */
const DocInfo = ({ docId }: DocOperationProps) => {
  const t = useI18n();
  const workspaceDialogService = useService(WorkspaceDialogService);

  const onOpenInfoModal = useCallback(() => {
    if (docId) {
      track.allDocs.list.docMenu.openDocInfo();
      workspaceDialogService.open('doc-info', { docId });
    }
  }, [docId, workspaceDialogService]);

  return (
    <MenuItem onClick={onOpenInfoModal} prefixIcon={<InformationIcon />}>
      {t['com.cloud-notes.page-properties.page-info.view']()}
    </MenuItem>
  );
};

/**
 * Open in New Tab Operation
 */
const NewTab = ({ docId }: DocOperationProps) => {
  const t = useI18n();
  const workbench = useService(WorkbenchService).workbench;
  const onOpenInNewTab = useCallback(() => {
    track.allDocs.list.doc.openDoc();
    track.allDocs.list.docMenu.openInNewTab();
    workbench.openDoc(docId, { at: 'new-tab' });
  }, [docId, workbench]);

  return (
    <MenuItem onClick={onOpenInNewTab} prefixIcon={<OpenInNewIcon />}>
      {t['com.cloud-notes.workbench.tab.page-menu-open']()}
    </MenuItem>
  );
};

/**
 * Open in Split View Operation
 */
const SplitView = ({ docId }: DocOperationProps) => {
  const t = useI18n();
  const workbench = useService(WorkbenchService).workbench;

  const onOpenInSplitView = useCallback(() => {
    track.allDocs.list.doc.openDoc();
    track.allDocs.list.docMenu.openInSplitView();
    workbench.openDoc(docId, { at: 'tail' });
  }, [docId, workbench]);

  return (
    <MenuItem onClick={onOpenInSplitView} prefixIcon={<SplitViewIcon />}>
      {t['com.cloud-notes.workbench.split-view.page-menu-open']()}
    </MenuItem>
  );
};

/**
 * Duplicate Operation
 */
const Duplicate = ({ docId }: DocOperationProps) => {
  const { duplicate } = useBlockSuiteMetaHelper();
  const t = useI18n();

  const onDuplicate = useCallback(() => {
    duplicate(docId, false);
    track.allDocs.list.docMenu.createDoc({
      control: 'duplicate',
    });
  }, [docId, duplicate]);

  return (
    <MenuItem prefixIcon={<DuplicateIcon />} onSelect={onDuplicate}>
      {t['com.cloud-notes.header.option.duplicate']()}
    </MenuItem>
  );
};

/**
 * Move to Trash Operation
 */
const MoveToTrash = ({ docId }: DocOperationProps) => {
  const t = useI18n();
  const docsService = useService(DocsService);
  const { openConfirmModal } = useConfirmModal();
  const doc = useLiveData(docsService.list.doc$(docId));
  const guardService = useService(GuardService);

  const canTrash = useLiveData(guardService.can$('Doc_Trash', docId));

  const onMoveToTrash = useCallback(() => {
    if (!doc) {
      return;
    }

    track.allDocs.list.docMenu.deleteDoc();
    openConfirmModal({
      title: t['com.cloud-notes.moveToTrash.confirmModal.title'](),
      description: t['com.cloud-notes.moveToTrash.confirmModal.description']({
        title: doc.title$.value || t['Untitled'](),
      }),
      cancelText: t['com.cloud-notes.confirmModal.button.cancel'](),
      confirmText: t.Delete(),
      confirmButtonOptions: {
        variant: 'error',
      },
      onConfirm: async () => {
        await doc.moveToTrash();
      },
    });
  }, [doc, openConfirmModal, t]);

  return (
    <MenuItem
      prefixIcon={<DeleteIcon />}
      data-testid="doc-list-operation-trash"
      onClick={onMoveToTrash}
      disabled={!canTrash}
    >
      {t['com.cloud-notes.moveToTrash.title']()}
    </MenuItem>
  );
};

export const MoreMenuContent = (props: DocOperationProps) => {
  return (
    <>
      <ToggleFavorite {...props} />
      <DocInfo {...props} />
      <NewTab {...props} />
      {BUILD_CONFIG.isElectron ? <SplitView {...props} /> : null}
      <Duplicate {...props} />
      <MoveToTrash {...props} />
    </>
  );
};

export const MoreMenu = ({
  docId,
  children,
  contentOptions,
  ...menuProps
}: Omit<MenuProps, 'items'> & { docId: string }) => {
  return (
    <Menu
      items={<MoreMenuContent docId={docId} />}
      contentOptions={{
        ...contentOptions,
        onClick: e => {
          // prevent external click events from being triggered
          e.stopPropagation();
          contentOptions?.onClick?.(e);
        },
      }}
      {...menuProps}
    >
      {children}
    </Menu>
  );
};

export const MoreMenuButton = ({
  docId,
  iconProps,
  ...menuProps
}: Omit<MenuProps, 'items' | 'children'> & {
  docId: string;
  iconProps?: IconButtonProps;
}) => {
  const contextValue = useContext(DocExplorerContext);
  const showMoreOperation = useLiveData(contextValue.showMoreOperation$);

  if (!showMoreOperation) {
    return null;
  }

  return (
    <MoreMenu docId={docId} {...menuProps}>
      <IconButton
        data-testid="doc-list-operation-button"
        icon={<MoreVerticalIcon />}
        {...iconProps}
      />
    </MoreMenu>
  );
};
