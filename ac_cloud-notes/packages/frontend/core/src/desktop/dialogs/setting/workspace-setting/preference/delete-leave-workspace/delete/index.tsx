import { Input } from '@cloud-notes/component';
import type { ConfirmModalProps } from '@cloud-notes/component/ui/modal';
import { ConfirmModal } from '@cloud-notes/component/ui/modal';
import { useWorkspaceInfo } from '@cloud-notes/core/components/hooks/use-workspace-info';
import type { WorkspaceMetadata } from '@cloud-notes/core/modules/workspace';
import { UNTITLED_WORKSPACE_NAME } from '@cloud-notes/env/constant';
import { Trans, useI18n } from '@cloud-notes/i18n';
import { useCallback, useState } from 'react';

import * as styles from './style.css';

interface WorkspaceDeleteProps extends ConfirmModalProps {
  workspaceMetadata: WorkspaceMetadata;
  onConfirm?: () => void;
}

export const WorkspaceDeleteModal = ({
  workspaceMetadata,
  ...props
}: WorkspaceDeleteProps) => {
  const { onConfirm } = props;
  const [deleteStr, setDeleteStr] = useState<string>('');
  const info = useWorkspaceInfo(workspaceMetadata);
  const workspaceName = info?.name ?? UNTITLED_WORKSPACE_NAME;
  const allowDelete = deleteStr === workspaceName;
  const t = useI18n();

  const handleOnEnter = useCallback(() => {
    if (allowDelete) {
      return onConfirm?.();
    }
  }, [allowDelete, onConfirm]);

  return (
    <ConfirmModal
      title={`${t['com.cloud-notes.workspaceDelete.title']()}?`}
      cancelText={t['com.cloud-notes.workspaceDelete.button.cancel']()}
      confirmText={t['com.cloud-notes.workspaceDelete.button.delete']()}
      confirmButtonOptions={{
        variant: 'error',
        disabled: !allowDelete,
        'data-testid': 'delete-workspace-confirm-button',
      }}
      {...props}
    >
      {workspaceMetadata.flavour === 'local' ? (
        <Trans i18nKey="com.cloud-notes.workspaceDelete.description">
          Deleting (
          <span className={styles.workspaceName}>
            {{ workspace: workspaceName } as any}
          </span>
          ) cannot be undone, please proceed with caution. All contents will be
          lost.
        </Trans>
      ) : (
        <Trans i18nKey="com.cloud-notes.workspaceDelete.description2">
          Deleting (
          <span className={styles.workspaceName}>
            {{ workspace: workspaceName } as any}
          </span>
          ) will delete both local and cloud data, this operation cannot be
          undone, please proceed with caution.
        </Trans>
      )}
      <div className={styles.inputContent}>
        <Input
          autoFocus
          onChange={setDeleteStr}
          data-testid="delete-workspace-input"
          onEnter={handleOnEnter}
          placeholder={t['com.cloud-notes.workspaceDelete.placeholder']()}
          size="large"
        />
      </div>
    </ConfirmModal>
  );
};
