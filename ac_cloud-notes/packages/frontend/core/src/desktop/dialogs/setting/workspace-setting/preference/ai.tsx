import { Switch } from '@cloud-notes/component';
import {
  SettingRow,
  SettingWrapper,
} from '@cloud-notes/component/setting-components';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { ServerService } from '@cloud-notes/core/modules/cloud';
import { WorkspacePermissionService } from '@cloud-notes/core/modules/permissions';
import { WorkspaceShareSettingService } from '@cloud-notes/core/modules/share-setting';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';

export const AiSetting = () => {
  const t = useI18n();
  const shareSetting = useService(WorkspaceShareSettingService).sharePreview;
  const serverService = useService(ServerService);
  const serverEnableAi = useLiveData(
    serverService.server.features$.map(f => f?.copilot)
  );
  const workspaceEnableAi = useLiveData(shareSetting.enableAi$);
  const loading = useLiveData(shareSetting.isLoading$);
  const permissionService = useService(WorkspacePermissionService);
  const isOwner = useLiveData(permissionService.permission.isOwner$);

  const toggleAi = useAsyncCallback(
    async (checked: boolean) => {
      await shareSetting.setEnableAi(checked);
    },
    [shareSetting]
  );

  if (!isOwner || !serverEnableAi) {
    return null;
  }

  return (
    <SettingWrapper
      title={t['com.cloud-notes.settings.workspace.cloud-notes-ai.title']()}
    >
      <SettingRow
        name={t['com.cloud-notes.settings.workspace.cloud-notes-ai.label']()}
        desc={t['com.cloud-notes.settings.workspace.cloud-notes-ai.description']()}
      >
        <Switch
          checked={!!workspaceEnableAi}
          onChange={toggleAi}
          disabled={loading}
        />
      </SettingRow>
    </SettingWrapper>
  );
};
