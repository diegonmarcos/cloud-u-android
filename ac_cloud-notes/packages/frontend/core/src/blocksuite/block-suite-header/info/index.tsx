import { IconButton } from '@cloud-notes/component';
import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { InformationIcon } from '@blocksuite/icons/rc';
import { useService } from '@toeverything/infra';
import { useCallback } from 'react';

export const InfoButton = ({ docId }: { docId: string }) => {
  const workspaceDialogService = useService(WorkspaceDialogService);
  const t = useI18n();

  const onOpenInfoModal = useCallback(() => {
    track.$.header.actions.openDocInfo();
    workspaceDialogService.open('doc-info', { docId });
  }, [docId, workspaceDialogService]);

  return (
    <IconButton
      size="20"
      tooltip={t['com.cloud-notes.page-properties.page-info.view']()}
      data-testid="header-info-button"
      onClick={onOpenInfoModal}
    >
      <InformationIcon />
    </IconButton>
  );
};
