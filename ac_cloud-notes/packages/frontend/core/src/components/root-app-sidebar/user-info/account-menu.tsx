import { MenuItem } from '@cloud-notes/component';
import { ServerService, UserFeatureService } from '@cloud-notes/core/modules/cloud';
import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { AccountIcon, AdminIcon, SignOutIcon } from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useEffect } from 'react';

import { useSignOut } from '../../hooks/cloud-notes/use-sign-out';

export const AccountMenu = () => {
  const workspaceDialogService = useService(WorkspaceDialogService);
  const openSignOutModal = useSignOut();
  const serverService = useService(ServerService);
  const userFeatureService = useService(UserFeatureService);
  const isCloudNotesAdmin = useLiveData(userFeatureService.userFeature.isAdmin$);

  const onOpenAccountSetting = useCallback(() => {
    track.$.navigationPanel.profileAndBadge.openSettings({ to: 'account' });
    workspaceDialogService.open('setting', {
      activeTab: 'account',
    });
  }, [workspaceDialogService]);

  const onOpenAdminPanel = useCallback(() => {
    window.open(`${serverService.server.baseUrl}/admin`, '_blank');
  }, [serverService.server.baseUrl]);

  const t = useI18n();

  useEffect(() => {
    userFeatureService.userFeature.revalidate();
  }, [userFeatureService]);

  return (
    <>
      <MenuItem
        prefixIcon={<AccountIcon />}
        data-testid="workspace-modal-account-settings-option"
        onClick={onOpenAccountSetting}
      >
        {t['com.cloud-notes.workspace.cloud.account.settings']()}
      </MenuItem>
      {isCloudNotesAdmin ? (
        <MenuItem
          prefixIcon={<AdminIcon />}
          data-testid="workspace-modal-account-admin-option"
          onClick={onOpenAdminPanel}
        >
          {t['com.cloud-notes.workspace.cloud.account.admin']()}
        </MenuItem>
      ) : null}
      <MenuItem
        prefixIcon={<SignOutIcon />}
        data-testid="workspace-modal-sign-out-option"
        onClick={openSignOutModal}
      >
        {t['com.cloud-notes.workspace.cloud.account.logout']()}
      </MenuItem>
    </>
  );
};
