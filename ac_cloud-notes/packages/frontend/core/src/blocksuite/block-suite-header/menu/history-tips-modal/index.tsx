import { OverlayModal } from '@cloud-notes/component';
import { useEnableCloud } from '@cloud-notes/core/components/hooks/cloud-notes/use-enable-cloud';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { useService } from '@toeverything/infra';
import { useCallback } from 'react';

import TopSvg from './top-svg';

export const HistoryTipsModal = ({
  open,
  setOpen,
}: {
  open: boolean;
  setOpen: (open: boolean) => void;
}) => {
  const t = useI18n();
  const currentWorkspace = useService(WorkspaceService).workspace;
  const confirmEnableCloud = useEnableCloud();

  const handleConfirm = useCallback(() => {
    setOpen(false);
    confirmEnableCloud(currentWorkspace);
  }, [confirmEnableCloud, currentWorkspace, setOpen]);

  return (
    <OverlayModal
      open={open}
      topImage={<TopSvg />}
      title={t['com.cloud-notes.history-vision.tips-modal.title']()}
      onOpenChange={setOpen}
      description={t['com.cloud-notes.history-vision.tips-modal.description']()}
      cancelText={t['com.cloud-notes.history-vision.tips-modal.cancel']()}
      confirmButtonOptions={{
        variant: 'primary',
      }}
      onConfirm={handleConfirm}
      confirmText={t['com.cloud-notes.history-vision.tips-modal.confirm']()}
    />
  );
};
