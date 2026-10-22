import { IconButton, notify, toast, useConfirmModal } from '@cloud-notes/component';
import {
  MenuSeparator,
  MenuSub,
  MobileMenu,
  MobileMenuItem,
} from '@cloud-notes/component/ui/menu';
import { useFavorite } from '@cloud-notes/core/blocksuite/block-suite-header/favorite';
import { Guard, useGuard } from '@cloud-notes/core/components/guard';
import { IsFavoriteIcon } from '@cloud-notes/core/components/pure/icons';
import { DocInfoSheet } from '@cloud-notes/core/mobile/components';
import { MobileTocMenu } from '@cloud-notes/core/mobile/components/toc-menu';
import { DocService } from '@cloud-notes/core/modules/doc';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { ViewService } from '@cloud-notes/core/modules/workbench/services/view';
import { preventDefault } from '@cloud-notes/core/utils';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import {
  DeleteIcon,
  EdgelessIcon,
  InformationIcon,
  MoreHorizontalIcon,
  PageIcon,
  TocIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { truncate } from 'lodash-es';
import { useCallback, useEffect, useState } from 'react';

import { MobileBackCoordinator } from '../../../modules/back-coordinator';
import { JournalConflictsMenuItem } from './menu/journal-conflicts';
import { JournalTodayActivityMenuItem } from './menu/journal-today-activity';
import { EditorModeSwitch } from './menu/mode-switch';
import * as styles from './page-header-more-button.css';

export const PageHeaderMenuButton = () => {
  const t = useI18n();

  const doc = useService(DocService).doc;
  const docId = doc?.id;
  const canEdit = useGuard('Doc_Update', docId);

  const editorService = useService(EditorService);
  const editorContainer = useLiveData(editorService.editor.editorContainer$);

  const [open, setOpen] = useState(false);
  const location = useLiveData(useService(ViewService).view.location$);

  const isInTrash = useLiveData(
    editorService.editor.doc.meta$.map(meta => meta.trash)
  );
  const primaryMode = useLiveData(editorService.editor.doc.primaryMode$);
  const title = useLiveData(editorService.editor.doc.title$);

  const { favorite, toggleFavorite } = useFavorite(docId);
  const { openConfirmModal } = useConfirmModal();
  const backCoordinator = useService(MobileBackCoordinator);

  const handleSwitchMode = useCallback(() => {
    const mode = primaryMode === 'page' ? 'edgeless' : 'page';
    // TODO(@JimmFly): remove setMode when there has view mode switch
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

  const handleMenuOpenChange = useCallback((open: boolean) => {
    if (open) {
      track.$.header.docOptions.open();
    }
    setOpen(open);
  }, []);
  useEffect(() => {
    // when the location is changed, close the menu
    handleMenuOpenChange(false);
  }, [handleMenuOpenChange, location.pathname]);

  const handleToggleFavorite = useCallback(() => {
    track.$.header.docOptions.toggleFavorite();
    toggleFavorite();
  }, [toggleFavorite]);

  const handleMoveToTrash = useCallback(() => {
    if (!doc) {
      return;
    }
    openConfirmModal({
      title: t['com.cloud-notes.moveToTrash.title'](),
      description: t['com.cloud-notes.moveToTrash.confirmModal.description']({
        title: truncate(doc.title$.value, { length: 64 }),
      }),
      confirmText: t['com.cloud-notes.moveToTrash.confirmModal.confirm'](),
      cancelText: t['com.cloud-notes.moveToTrash.confirmModal.cancel'](),
      confirmButtonOptions: {
        variant: 'error',
      },
      async onConfirm() {
        await doc.moveToTrash();
        track.$.navigationPanel.docs.deleteDoc({
          control: 'button',
        });
        toast(t['com.cloud-notes.toastMessage.movedTrash']());
        if (!backCoordinator.request('ui-back')) {
          backCoordinator.request('ui-up');
        }
      },
    });
  }, [backCoordinator, doc, openConfirmModal, t]);

  const EditMenu = (
    <>
      <EditorModeSwitch />
      <JournalTodayActivityMenuItem suffix={<MenuSeparator />} />
      <MobileMenuItem
        prefixIcon={primaryMode === 'page' ? <EdgelessIcon /> : <PageIcon />}
        data-testid="editor-option-menu-mode-switch"
        onSelect={handleSwitchMode}
        disabled={!canEdit}
      >
        {primaryMode === 'page'
          ? t['com.cloud-notes.editorDefaultMode.edgeless']()
          : t['com.cloud-notes.editorDefaultMode.page']()}
      </MobileMenuItem>
      <MobileMenuItem
        data-testid="editor-option-menu-favorite"
        onSelect={handleToggleFavorite}
        prefixIcon={<IsFavoriteIcon favorite={favorite} />}
      >
        {favorite
          ? t['com.cloud-notes.favoritePageOperation.remove']()
          : t['com.cloud-notes.favoritePageOperation.add']()}
      </MobileMenuItem>
      <MenuSeparator />
      <MenuSub
        triggerOptions={{
          prefixIcon: <InformationIcon />,
          onClick: preventDefault,
        }}
        title={title ?? t['unnamed']()}
        items={<DocInfoSheet docId={docId} />}
      >
        <span>{t['com.cloud-notes.page-properties.page-info.view']()}</span>
      </MenuSub>
      <MobileMenu
        title={t['com.cloud-notes.header.menu.toc']()}
        items={
          <div className={styles.outlinePanel}>
            <MobileTocMenu editor={editorContainer?.host ?? null} />
          </div>
        }
      >
        <MobileMenuItem prefixIcon={<TocIcon />} onClick={preventDefault}>
          <span>{t['com.cloud-notes.header.option.view-toc']()}</span>
        </MobileMenuItem>
      </MobileMenu>
      <JournalConflictsMenuItem />
      <Guard docId={docId} permission="Doc_Trash">
        {canMoveToTrash => (
          <MobileMenuItem
            prefixIcon={<DeleteIcon />}
            type="danger"
            disabled={!canMoveToTrash}
            onSelect={handleMoveToTrash}
          >
            {t['com.cloud-notes.moveToTrash.title']()}
          </MobileMenuItem>
        )}
      </Guard>
    </>
  );
  if (isInTrash) {
    return null;
  }
  return (
    <MobileMenu
      items={EditMenu}
      contentOptions={{
        align: 'center',
      }}
      rootOptions={{
        open,
        onOpenChange: handleMenuOpenChange,
      }}
    >
      <IconButton
        size={24}
        data-testid="detail-page-header-more-button"
        className={styles.iconButton}
      >
        <MoreHorizontalIcon />
      </IconButton>
    </MobileMenu>
  );
};
