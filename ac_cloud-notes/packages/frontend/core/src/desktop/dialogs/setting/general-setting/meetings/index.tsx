import {
  Button,
  IconButton,
  Menu,
  MenuItem,
  MenuTrigger,
  Switch,
} from '@cloud-notes/component';
import {
  SettingHeader,
  SettingRow,
  SettingWrapper,
} from '@cloud-notes/component/setting-components';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { DesktopApiService } from '@cloud-notes/core/modules/desktop-api';
import { MeetingSettingsService } from '@cloud-notes/core/modules/media/services/meeting-settings';
import type { MeetingSettingsSchema } from '@cloud-notes/electron/main/shared-state-schema';
import { Trans, useI18n } from '@cloud-notes/i18n';
import {
  ArrowRightSmallIcon,
  DoneIcon,
  InformationFillDuotoneIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useEffect, useMemo, useState } from 'react';

import * as styles from './styles.css';
import { useEnableRecording } from './use-enable-recording';
import { MeetingsWelcomePage } from './welcome-page';

const RecordingModes: MeetingSettingsSchema['recordingMode'][] = [
  'prompt',
  'auto-start',
  'none',
];

const RecordingModeMenu = () => {
  const meetingSettingsService = useService(MeetingSettingsService);
  const settings = useLiveData(meetingSettingsService.settings$);
  const t = useI18n();

  const options = useMemo(() => {
    return RecordingModes.map(mode => ({
      label: t[`com.cloud-notes.settings.meetings.record.recording-mode.${mode}`](),
      value: mode,
    }));
  }, [t]);

  const currentMode = settings.recordingMode;

  const handleRecordingModeChange = useCallback(
    (mode: MeetingSettingsSchema['recordingMode']) => {
      meetingSettingsService.setRecordingMode(mode);
    },
    [meetingSettingsService]
  );

  return (
    <Menu
      items={options.map(option => {
        return (
          <MenuItem
            key={option.value}
            title={option.label}
            onSelect={() => handleRecordingModeChange(option.value)}
            data-selected={currentMode === option.value}
          >
            {option.label}
          </MenuItem>
        );
      })}
    >
      <MenuTrigger style={{ fontWeight: 600, width: '250px' }} block={true}>
        {options.find(option => option.value === currentMode)?.label}
      </MenuTrigger>
    </Menu>
  );
};

// Add the PermissionSettingRow component
interface PermissionSettingRowProps {
  nameKey: string;
  descriptionKey: string;
  permissionSettingKey: string;
  hasPermission: boolean;
  onOpenPermissionSetting: () => void | Promise<void>;
}

const PermissionSettingRow = ({
  nameKey,
  descriptionKey,
  permissionSettingKey,
  hasPermission,
  onOpenPermissionSetting,
}: PermissionSettingRowProps) => {
  const t = useI18n();

  const handleClick = () => {
    const result = onOpenPermissionSetting();
    if (result instanceof Promise) {
      result.catch(error => {
        console.error('Error opening permission setting:', error);
      });
    }
  };

  return (
    <SettingRow
      name={t[nameKey]()}
      desc={
        <>
          {t[descriptionKey]()}
          {!hasPermission && (
            <span onClick={handleClick} className={styles.permissionSetting}>
              {t[permissionSettingKey]()}
            </span>
          )}
        </>
      }
    >
      <IconButton
        icon={
          hasPermission ? (
            <DoneIcon />
          ) : (
            <InformationFillDuotoneIcon className={styles.noPermissionIcon} />
          )
        }
        onClick={handleClick}
      />
    </SettingRow>
  );
};

const MeetingsSettingsMain = () => {
  const t = useI18n();
  const meetingSettingsService = useService(MeetingSettingsService);
  const desktopApiService = useService(DesktopApiService);
  const settings = useLiveData(meetingSettingsService.settings$);
  const [recordingFeatureAvailable, setRecordingFeatureAvailable] =
    useState(false);

  const [permissions, setPermissions] = useState<{
    screen: boolean;
    microphone: boolean;
  }>();

  useEffect(() => {
    meetingSettingsService
      .isRecordingFeatureAvailable()
      .then(available => {
        setRecordingFeatureAvailable(available ?? false);
      })
      .catch(() => {
        setRecordingFeatureAvailable(false);
      });
    meetingSettingsService
      .checkMeetingPermissions()
      .then(permission => {
        setPermissions(permission);
      })
      .catch(err => console.log(err));
  }, [meetingSettingsService]);

  const handleEnabledChange = useEnableRecording();

  const handleAutoSummaryChange = useCallback(
    (checked: boolean) => {
      meetingSettingsService.setAutoSummary(checked);
    },
    [meetingSettingsService]
  );

  const handleAutoTodoChange = useCallback(
    (checked: boolean) => {
      meetingSettingsService.setAutoTodo(checked);
    },
    [meetingSettingsService]
  );

  const handleOpenPermissionSetting = useAsyncCallback(
    async (type: 'screen' | 'microphone') => {
      await meetingSettingsService.askForMeetingPermission(type);
      await meetingSettingsService.showRecordingPermissionSetting(type);
    },
    [meetingSettingsService]
  );

  const handleOpenSavedRecordings = useAsyncCallback(async () => {
    await meetingSettingsService.openSavedRecordings();
  }, [meetingSettingsService]);

  const handleRestartApp = useAsyncCallback(async () => {
    await desktopApiService.handler.ui.restartApp();
  }, [desktopApiService]);

  return (
    <div className={styles.meetingWrapper}>
      <SettingHeader
        beta
        title={t['com.cloud-notes.settings.meetings']()}
        subtitle={
          <>
            {t['com.cloud-notes.settings.meetings.setting.prompt']()}
            <br />
            <Trans
              i18nKey="com.cloud-notes.settings.meetings.setting.prompt.2"
              components={{
                strong: <strong />,
              }}
            />
          </>
        }
      />

      <SettingRow
        name={t['com.cloud-notes.settings.meetings.enable.title']()}
        desc={
          <Trans
            i18nKey="com.cloud-notes.settings.meetings.enable.description"
            components={{
              1: (
                <a
                  className={styles.link}
                  href="https://discord.com/channels/959027316334407691/1358384103925350542"
                  target="_blank"
                  rel="noreferrer"
                />
              ),
            }}
          />
        }
      >
        <Switch
          checked={settings.enabled}
          onChange={handleEnabledChange}
          data-testid="meetings-enable-switch"
        />
      </SettingRow>

      {recordingFeatureAvailable && (
        <>
          <SettingWrapper
            disabled={!settings.enabled}
            title={t['com.cloud-notes.settings.meetings.record.header']()}
          >
            <SettingRow
              name={t['com.cloud-notes.settings.meetings.record.recording-mode']()}
              desc={t[
                'com.cloud-notes.settings.meetings.record.recording-mode.description'
              ]()}
            >
              <RecordingModeMenu />
            </SettingRow>
            <SettingRow
              name={t['com.cloud-notes.settings.meetings.record.open-saved-file']()}
              desc={t[
                'com.cloud-notes.settings.meetings.record.open-saved-file.description'
              ]()}
            >
              <IconButton
                icon={<ArrowRightSmallIcon />}
                onClick={handleOpenSavedRecordings}
              />
            </SettingRow>
          </SettingWrapper>
          <SettingWrapper
            disabled={!settings.enabled}
            title={t['com.cloud-notes.settings.meetings.transcription.header']()}
          >
            <SettingRow
              name={t[
                'com.cloud-notes.settings.meetings.transcription.auto-summary'
              ]()}
              desc={t[
                'com.cloud-notes.settings.meetings.transcription.auto-summary.description'
              ]()}
            >
              <Switch
                checked={settings.autoTranscriptionSummary}
                onChange={handleAutoSummaryChange}
                data-testid="meetings-auto-summary-switch"
              />
            </SettingRow>
            <SettingRow
              name={t['com.cloud-notes.settings.meetings.transcription.auto-todo']()}
              desc={t[
                'com.cloud-notes.settings.meetings.transcription.auto-todo.description'
              ]()}
            >
              <Switch
                checked={settings.autoTranscriptionTodo}
                onChange={handleAutoTodoChange}
                data-testid="meetings-auto-todo-switch"
              />
            </SettingRow>
          </SettingWrapper>
          {environment.isMacOs && (
            <SettingWrapper
              title={t['com.cloud-notes.settings.meetings.privacy.header']()}
            >
              <PermissionSettingRow
                nameKey="com.cloud-notes.settings.meetings.privacy.screen-system-audio-recording"
                descriptionKey="com.cloud-notes.settings.meetings.privacy.screen-system-audio-recording.description"
                permissionSettingKey="com.cloud-notes.settings.meetings.privacy.screen-system-audio-recording.permission-setting"
                hasPermission={permissions?.screen || false}
                onOpenPermissionSetting={() =>
                  handleOpenPermissionSetting('screen')
                }
              />
              <PermissionSettingRow
                nameKey="com.cloud-notes.settings.meetings.privacy.microphone"
                descriptionKey="com.cloud-notes.settings.meetings.privacy.microphone.description"
                permissionSettingKey="com.cloud-notes.settings.meetings.privacy.microphone.permission-setting"
                hasPermission={permissions?.microphone || false}
                onOpenPermissionSetting={() =>
                  handleOpenPermissionSetting('microphone')
                }
              />
            </SettingWrapper>
          )}

          <SettingWrapper>
            <SettingRow
              name={t['com.cloud-notes.settings.meetings.privacy.issues']()}
              desc={t[
                'com.cloud-notes.settings.meetings.privacy.issues.description'
              ]()}
            >
              <Button onClick={handleRestartApp}>
                {t['com.cloud-notes.settings.meetings.privacy.issues.restart']()}
              </Button>
            </SettingRow>
          </SettingWrapper>
        </>
      )}
    </div>
  );
};

export const MeetingsSettings = () => {
  const meetingSettingsService = useService(MeetingSettingsService);
  const settings = useLiveData(meetingSettingsService.settings$);
  const accepted = settings.betaDisclaimerAccepted || settings.enabled;

  if (!accepted) {
    return <MeetingsWelcomePage />;
  }

  return <MeetingsSettingsMain />;
};
