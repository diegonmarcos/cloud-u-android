import { notify, toast, useConfirmModal } from '@cloud-notes/component';
import {
  Menu,
  MenuItem,
  MenuSeparator,
  MenuSub,
} from '@cloud-notes/component/ui/menu';
import { PageHistoryModal } from '@cloud-notes/core/components/cloud-notes/page-history-modal';
import { useGuard } from '@cloud-notes/core/components/guard';
import { useBlockSuiteMetaHelper } from '@cloud-notes/core/components/hooks/cloud-notes/use-block-suite-meta-helper';
import { useEnableCloud } from '@cloud-notes/core/components/hooks/cloud-notes/use-enable-cloud';
import { useExportPage } from '@cloud-notes/core/components/hooks/cloud-notes/use-export-page';
import { Export, MoveToTrash } from '@cloud-notes/core/components/page-list';
import { IsFavoriteIcon } from '@cloud-notes/core/components/pure/icons';
import { useDetailPageHeaderResponsive } from '@cloud-notes/core/desktop/pages/workspace/detail-page/use-header-responsive';
import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { OpenInAppService } from '@cloud-notes/core/modules/open-in-app/services';
import { GuardService } from '@cloud-notes/core/modules/permissions';
import { ShareMenuContent } from '@cloud-notes/core/modules/share-menu';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { ViewService } from '@cloud-notes/core/modules/workbench/services/view';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import type { Store } from '@blocksuite/cloud-notes/store';
import {
  DuplicateIcon,
  EdgelessIcon,
  EditIcon,
  FrameIcon,
  HistoryIcon,
  ImportIcon,
  InformationIcon,
  LocalWorkspaceIcon,
  OpenInNewIcon,
  PageIcon,
  ShareIcon,
  SplitViewIcon,
  TocIcon,
} from '@blocksuite/icons/rc';
import {
  useLiveData,
  useService,
  useServiceOptional,
} from '@toeverything/infra';
import { useCallback, useState } from 'react';

import { HeaderDropDownButton } from '../../../components/pure/header-drop-down-button';
import { useFavorite } from '../favorite';
import { HistoryTipsModal } from './history-tips-modal';
import { shareMenu } from './style.css';

type PageMenuProps = {
  rename?: () => void;
  page: Store;
  isJournal?: boolean;
  containerWidth: number;
};

export const PageHeaderMenuButton = ({
  rename,
  page,
  isJournal,
  containerWidth,
}: PageMenuProps) => {
  const workspace = useService(WorkspaceService).workspace;
  const editorService = useService(EditorService);
  const isInTrash = useLiveData(
    editorService.editor.doc.meta$.map(meta => meta.trash)
  );

  const [historyModalOpen, setHistoryModalOpen] = useState(false);
  const [openHistoryTipsModal, setOpenHistoryTipsModal] = useState(false);

  const handleMenuOpenChange = useCallback((open: boolean) => {
    if (open) {
      track.$.header.docOptions.open();
    }
  }, []);

  const openHistoryModal = useCallback(() => {
    track.$.header.history.open();
    if (workspace.flavour !== 'local') {
      return setHistoryModalOpen(true);
    }
    return setOpenHistoryTipsModal(true);
  }, [setOpenHistoryTipsModal, workspace.flavour]);

  if (isInTrash) {
    return null;
  }

  return (
    <>
      <Menu
        items={
          <PageHeaderMenuItem
            page={page}
            containerWidth={containerWidth}
            rename={rename}
            isJournal={isJournal}
            openHistoryModal={openHistoryModal}
          />
        }
        contentOptions={{
          align: 'center',
        }}
        rootOptions={{
          onOpenChange: handleMenuOpenChange,
        }}
      >
        <HeaderDropDownButton />
      </Menu>
      {workspace.flavour !== 'local' ? (
        <PageHistoryModal
          docCollection={workspace.docCollection}
          open={historyModalOpen}
          pageId={page.id}
          onOpenChange={setHistoryModalOpen}
        />
      ) : null}
      <HistoryTipsModal
        open={openHistoryTipsModal}
        setOpen={setOpenHistoryTipsModal}
      />
    </>
  );
};

// fixme: refactor this file
const PageHeaderMenuItem = ({
  rename,
  page,
  isJournal,
  containerWidth,
  openHistoryModal,
}: PageMenuProps & {
  openHistoryModal: () => void;
}) => {
  const pageId = page?.id;
  const t = useI18n();
  const { hideShare } = useDetailPageHeaderResponsive(containerWidth);
  const confirmEnableCloud = useEnableCloud();

  const workspace = useService(WorkspaceService).workspace;
  const guardService = useService(GuardService);
  const editorService = useService(EditorService);
  const currentMode = useLiveData(editorService.editor.mode$);
  const primaryMode = useLiveData(editorService.editor.doc.primaryMode$);

  const workbench = useService(WorkbenchService).workbench;
  const openInAppService = useServiceOptional(OpenInAppService);

  const { favorite, toggleFavorite } = useFavorite(pageId);

  const { duplicate } = useBlockSuiteMetaHelper();

  const view = useService(ViewService).view;

  const openSidePanel = useCallback(
    (id: string) => {
      workbench.openSidebar();
      view.activeSidebarTab(id);
    },
    [workbench, view]
  );

  const openAllFrames = useCallback(() => {
    openSidePanel('frame');
  }, [openSidePanel]);

  const openOutlinePanel = useCallback(() => {
    openSidePanel('outline');
  }, [openSidePanel]);

  const workspaceDialogService = useService(WorkspaceDialogService);
  const openInfoModal = useCallback(() => {
    track.$.header.pageInfo.open();
    workspaceDialogService.open('doc-info', { docId: pageId });
  }, [workspaceDialogService, pageId]);

  const handleOpenInNewTab = useCallback(() => {
    workbench.openDoc(pageId, {
      at: 'new-tab',
    });
  }, [pageId, workbench]);

  const handleOpenInSplitView = useCallback(() => {
    workbench.openDoc(pageId, {
      at: 'tail',
    });
  }, [pageId, workbench]);

  const { openConfirmModal } = useConfirmModal();

  const handleOpenTrashModal = useCallback(() => {
    track.$.header.docOptions.deleteDoc();
    openConfirmModal({
      title: t['com.cloud-notes.moveToTrash.confirmModal.title'](),
      description: t['com.cloud-notes.moveToTrash.confirmModal.description']({
        title: editorService.editor.doc.title$.value || t['Untitled'](),
      }),
      cancelText: t['com.cloud-notes.confirmModal.button.cancel'](),
      confirmText: t.Delete(),
      confirmButtonOptions: {
        variant: 'error',
      },
      onConfirm: async () => {
        const canTrash = await guardService.can('Doc_Trash', pageId);
        if (!canTrash) {
          toast(t['com.cloud-notes.no-permission']());
          return;
        }
        await editorService.editor.doc.moveToTrash();
      },
    });
  }, [editorService.editor.doc, guardService, openConfirmModal, pageId, t]);

  const handleRename = useCallback(() => {
    rename?.();
    track.$.header.docOptions.renameDoc();
  }, [rename]);

  const handleSwitchMode = useCallback(() => {
    const mode = primaryMode === 'page' ? 'edgeless' : 'page';
    editorService.editor.setMode(mode);
    editorService.editor.doc.setPrimaryMode(mode);
    track.$.header.docOptions.switchPageMode({
      mode,
    });
    notify.success({
      title:
        primaryMode === 'page'
          ? t['com.cloud-notes.toastMessage.defaultMode.edgeless.title']()
          : t['com.cloud-notes.toastMessage.defaultMode.page.title'](),
      message:
        primaryMode === 'page'
          ? t['com.cloud-notes.toastMessage.defaultMode.edgeless.message']()
          : t['com.cloud-notes.toastMessage.defaultMode.page.message'](),
    });
  }, [primaryMode, editorService, t]);

  const exportHandler = useExportPage();

  const handleDuplicate = useCallback(() => {
    duplicate(pageId);
    track.$.header.docOptions.createDoc({
      control: 'duplicate',
    });
  }, [duplicate, pageId]);

  const handleOpenDocs = useCallback(
    (result: {
      docIds: string[];
      entryId?: string;
      isWorkspaceFile?: boolean;
    }) => {
      const { docIds, entryId, isWorkspaceFile } = result;
      // If the imported file is a workspace file, open the entry page.
      if (isWorkspaceFile && entryId) {
        workbench.openDoc(entryId);
      } else if (!docIds.length) {
        return;
      }
      // Open all the docs when there are multiple docs imported.
      if (docIds.length > 1) {
        workbench.openAll();
      } else {
        // Otherwise, open the only doc.
        workbench.openDoc(docIds[0]);
      }
    },
    [workbench]
  );

  const handleOpenImportModal = useCallback(() => {
    track.$.header.importModal.open();
    workspaceDialogService.open('import', undefined, payload => {
      if (!payload) {
        return;
      }
      handleOpenDocs(payload);
    });
  }, [workspaceDialogService, handleOpenDocs]);

  const handleShareMenuOpenChange = useCallback((open: boolean) => {
    if (open) {
      track.$.sharePanel.$.open();
    }
  }, []);

  const handleToggleFavorite = useCallback(() => {
    track.$.header.docOptions.toggleFavorite();
    toggleFavorite();
  }, [toggleFavorite]);

  const showResponsiveMenu = hideShare;
  const ResponsiveMenuItems = (
    <>
      {hideShare ? (
        <MenuSub
          subContentOptions={{
            sideOffset: 12,
            alignOffset: -8,

            // to handle overflow when the width is not enough
            collisionPadding: 20,
          }}
          items={
            <div className={shareMenu}>
              <ShareMenuContent
                workspaceMetadata={workspace.meta}
                currentPage={page}
                onEnableCloudNotesCloud={() =>
                  confirmEnableCloud(workspace, {
                    openPageId: page.id,
                  })
                }
              />
            </div>
          }
          triggerOptions={{
            prefixIcon: <ShareIcon />,
          }}
          subOptions={{
            onOpenChange: handleShareMenuOpenChange,
          }}
        >
          {t['com.cloud-notes.share-menu.shareButton']()}
        </MenuSub>
      ) : null}
      <MenuSeparator />
    </>
  );

  const onOpenInDesktop = useCallback(() => {
    openInAppService?.showOpenInAppPage();
  }, [openInAppService]);

  const canEdit = useGuard('Doc_Update', pageId);
  const canMoveToTrash = useGuard('Doc_Trash', pageId);

  return (
    <>
      {showResponsiveMenu ? ResponsiveMenuItems : null}
      {!isJournal && (
        <MenuItem
          prefixIcon={<EditIcon />}
          data-testid="editor-option-menu-rename"
          onSelect={handleRename}
          disabled={!canEdit}
        >
          {t['Rename']()}
        </MenuItem>
      )}
      <MenuItem
        prefixIcon={primaryMode === 'page' ? <EdgelessIcon /> : <PageIcon />}
        data-testid="editor-option-menu-edgeless"
        onSelect={handleSwitchMode}
        disabled={!canEdit}
      >
        {primaryMode === 'page'
          ? t['com.cloud-notes.editorDefaultMode.edgeless']()
          : t['com.cloud-notes.editorDefaultMode.page']()}
      </MenuItem>
      <MenuItem
        data-testid="editor-option-menu-favorite"
        onSelect={handleToggleFavorite}
        prefixIcon={<IsFavoriteIcon favorite={favorite} />}
      >
        {favorite
          ? t['com.cloud-notes.favoritePageOperation.remove']()
          : t['com.cloud-notes.favoritePageOperation.add']()}
      </MenuItem>
      <MenuSeparator />
      <MenuItem
        prefixIcon={<OpenInNewIcon />}
        data-testid="editor-option-menu-open-in-new-tab"
        onSelect={handleOpenInNewTab}
      >
        {t['com.cloud-notes.workbench.tab.page-menu-open']()}
      </MenuItem>
      {BUILD_CONFIG.isElectron && (
        <MenuItem
          prefixIcon={<SplitViewIcon />}
          data-testid="editor-option-menu-open-in-split-new"
          onSelect={handleOpenInSplitView}
        >
          {t['com.cloud-notes.workbench.split-view.page-menu-open']()}
        </MenuItem>
      )}

      <MenuSeparator />
      <MenuItem
        prefixIcon={<InformationIcon />}
        data-testid="editor-option-menu-info"
        onSelect={openInfoModal}
      >
        {t['com.cloud-notes.page-properties.page-info.view']()}
      </MenuItem>
      {currentMode === 'page' ? (
        <MenuItem
          prefixIcon={<TocIcon />}
          data-testid="editor-option-toc"
          onSelect={openOutlinePanel}
        >
          {t['com.cloud-notes.header.option.view-toc']()}
        </MenuItem>
      ) : (
        <MenuItem
          prefixIcon={<FrameIcon />}
          data-testid="editor-option-frame"
          onSelect={openAllFrames}
        >
          {t['com.cloud-notes.header.option.view-frame']()}
        </MenuItem>
      )}
      <MenuItem
        prefixIcon={<HistoryIcon />}
        data-testid="editor-option-menu-history"
        onSelect={openHistoryModal}
      >
        {t['com.cloud-notes.history.view-history-version']()}
      </MenuItem>
      <MenuSeparator />
      {!isJournal && (
        <MenuItem
          prefixIcon={<DuplicateIcon />}
          data-testid="editor-option-menu-duplicate"
          onSelect={handleDuplicate}
        >
          {t['com.cloud-notes.header.option.duplicate']()}
        </MenuItem>
      )}
      <MenuItem
        prefixIcon={<ImportIcon />}
        data-testid="editor-option-menu-import"
        onSelect={handleOpenImportModal}
      >
        {t['Import']()}
      </MenuItem>
      <Export exportHandler={exportHandler} pageMode={currentMode} />
      <MenuSeparator />
      <MoveToTrash
        data-testid="editor-option-menu-delete"
        onSelect={handleOpenTrashModal}
        disabled={!canMoveToTrash}
      />
      {BUILD_CONFIG.isWeb && workspace.flavour !== 'local' ? (
        <MenuItem
          prefixIcon={<LocalWorkspaceIcon />}
          data-testid="editor-option-menu-link"
          onSelect={onOpenInDesktop}
        >
          {t['com.cloud-notes.header.option.open-in-desktop']()}
        </MenuItem>
      ) : null}
    </>
  );
};
