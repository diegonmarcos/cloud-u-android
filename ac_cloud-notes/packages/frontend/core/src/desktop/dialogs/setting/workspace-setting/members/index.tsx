import { Button, Tooltip } from '@cloud-notes/component';
import { SettingRow } from '@cloud-notes/component/setting-components';
import { CloudNotesErrorBoundary } from '@cloud-notes/core/components/cloud-notes/cloud-notes-error-boundary';
import { useWorkspaceInfo } from '@cloud-notes/core/components/hooks/use-workspace-info';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { useService } from '@toeverything/infra';
import type { ReactElement } from 'react';

import type { SettingState } from '../../types';
import { EnableCloudPanel } from '../preference/enable-cloud';
import { CloudWorkspaceMembersPanel } from './cloud-members-panel';
import * as styles from './styles.css';

export const MembersPanel = ({
  onChangeSettingState,
  onCloseSetting,
}: {
  onChangeSettingState: (settingState: SettingState) => void;
  onCloseSetting: () => void;
}): ReactElement | null => {
  const workspace = useService(WorkspaceService).workspace;
  const isTeam = useWorkspaceInfo(workspace.meta)?.isTeam;
  if (workspace.flavour === 'local') {
    return <MembersPanelLocal onCloseSetting={onCloseSetting} />;
  }
  return (
    <CloudNotesErrorBoundary>
      <CloudWorkspaceMembersPanel
        onChangeSettingState={onChangeSettingState}
        isTeam={isTeam}
      />
    </CloudNotesErrorBoundary>
  );
};

const MembersPanelLocal = ({
  onCloseSetting,
}: {
  onCloseSetting: () => void;
}) => {
  const t = useI18n();
  return (
    <div className={styles.localMembersPanel}>
      <Tooltip content={t['com.cloud-notes.settings.member-tooltip']()}>
        <div className={styles.fakeWrapper}>
          <SettingRow name={`${t['Members']()} (0)`} desc={t['Members hint']()}>
            <Button>{t['Invite Members']()}</Button>
          </SettingRow>
        </div>
      </Tooltip>
      <EnableCloudPanel onCloseSetting={onCloseSetting} />
    </div>
  );
};
