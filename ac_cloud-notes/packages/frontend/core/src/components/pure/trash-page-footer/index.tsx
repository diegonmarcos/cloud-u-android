import { Button } from '@cloud-notes/component/ui/button';
import { ConfirmModal } from '@cloud-notes/component/ui/modal';
import { DocService } from '@cloud-notes/core/modules/doc';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { DeleteIcon, ResetIcon } from '@blocksuite/icons/rc';
import { useService } from '@toeverything/infra';
import { useCallback, useState } from 'react';

import { useAppSettingHelper } from '../../../components/hooks/cloud-notes/use-app-setting-helper';
import { useBlockSuiteMetaHelper } from '../../../components/hooks/cloud-notes/use-block-suite-meta-helper';
import { useNavigateHelper } from '../../../components/hooks/use-navigate-helper';
import { toast } from '../../../utils';
import * as styles from './styles.css';

export const TrashPageFooter = () => {
  const workspace = useService(WorkspaceService).workspace;
  const docCollection = workspace.docCollection;
  const doc = useService(DocService).doc;
  const t = useI18n();
  const { appSettings } = useAppSettingHelper();
  const { jumpToPage } = useNavigateHelper();
  const { restoreFromTrash } = useBlockSuiteMetaHelper();
  const [open, setOpen] = useState(false);
  const hintText = t['com.cloud-notes.cmdk.cloud-notes.editor.trash-footer-hint']();

  const onRestore = useCallback(() => {
    restoreFromTrash(doc.id)
      .then(() => {
        toast(
          t['com.cloud-notes.toastMessage.restored']({
            title: doc.meta$.value.title || 'Untitled',
          })
        );
      })
      .catch(error => console.error(error));
  }, [doc.id, doc.meta$.value.title, restoreFromTrash, t]);

  const onConfirmDelete = useCallback(() => {
    jumpToPage(workspace.id, 'all');
    docCollection.removeDoc(doc.id);
    toast(t['com.cloud-notes.toastMessage.permanentlyDeleted']());
  }, [jumpToPage, workspace.id, docCollection, doc.id, t]);

  const onDelete = useCallback(() => {
    setOpen(true);
  }, []);

  return (
    <div
      className={styles.deleteHintContainer}
      data-has-background={!appSettings.clientBorder}
    >
      <div className={styles.deleteHintText}>{hintText}</div>
      <div className={styles.group}>
        <Button
          tooltip={t['com.cloud-notes.trashOperation.restoreIt']()}
          data-testid="page-restore-button"
          variant="primary"
          onClick={onRestore}
          className={styles.buttonContainer}
          prefix={<ResetIcon />}
          prefixClassName={styles.icon}
        />
        <Button
          tooltip={t['com.cloud-notes.trashOperation.deletePermanently']()}
          variant="error"
          onClick={onDelete}
          className={styles.buttonContainer}
          prefix={<DeleteIcon />}
          prefixClassName={styles.icon}
        />
      </div>
      <ConfirmModal
        title={t['com.cloud-notes.trashOperation.delete.title']()}
        cancelText={t['com.cloud-notes.confirmModal.button.cancel']()}
        description={t['com.cloud-notes.trashOperation.delete.description']()}
        confirmText={t['com.cloud-notes.trashOperation.delete']()}
        confirmButtonOptions={{
          variant: 'error',
        }}
        open={open}
        onConfirm={onConfirmDelete}
        onOpenChange={setOpen}
      />
    </div>
  );
};
