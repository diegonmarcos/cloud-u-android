import { Switch } from '@cloud-notes/component';
import {
  SettingHeader,
  SettingRow,
  SettingWrapper,
} from '@cloud-notes/component/setting-components';
import { useAppUpdater } from '@cloud-notes/core/components/hooks/use-app-updater';
import { UrlService } from '@cloud-notes/core/modules/url';
import { appIconMap, appNames } from '@cloud-notes/core/utils/channel';
import { useI18n } from '@cloud-notes/i18n';
import { ArrowRightSmallIcon, OpenInNewIcon } from '@blocksuite/icons/rc';
import { useServices } from '@toeverything/infra';
import { useCallback } from 'react';

import { useAppSettingHelper } from '../../../../../components/hooks/cloud-notes/use-app-setting-helper';
import { relatedLinks } from './config';
import * as styles from './style.css';
import { UpdateCheckSection } from './update-check-section';

export const AboutCloudNotes = () => {
  const t = useI18n();
  const { appSettings, updateSettings } = useAppSettingHelper();
  const { toggleAutoCheck, toggleAutoDownload } = useAppUpdater();
  const channel = BUILD_CONFIG.appBuildType;
  const appIcon = appIconMap[channel];
  const appName = appNames[channel];
  const { urlService } = useServices({
    UrlService,
  });

  const onSwitchAutoCheck = useCallback(
    (checked: boolean) => {
      toggleAutoCheck(checked);
      updateSettings('autoCheckUpdate', checked);
    },
    [toggleAutoCheck, updateSettings]
  );

  const onSwitchAutoDownload = useCallback(
    (checked: boolean) => {
      toggleAutoDownload(checked);
      updateSettings('autoDownloadUpdate', checked);
    },
    [toggleAutoDownload, updateSettings]
  );

  const onSwitchTelemetry = useCallback(
    (checked: boolean) => {
      updateSettings('enableTelemetry', checked);
    },
    [updateSettings]
  );

  return (
    <>
      <SettingHeader
        title={t['com.cloud-notes.aboutCloudNotes.title']()}
        subtitle={t['com.cloud-notes.aboutCloudNotes.subtitle']()}
        data-testid="about-title"
      />
      <SettingWrapper title={t['com.cloud-notes.aboutCloudNotes.version.title']()}>
        <SettingRow
          name={appName}
          desc={BUILD_CONFIG.appVersion}
          className={styles.appImageRow}
        >
          <img src={appIcon} alt={appName} width={56} height={56} />
        </SettingRow>
        <SettingRow
          name={t['com.cloud-notes.aboutCloudNotes.version.editor.title']()}
          desc={BUILD_CONFIG.editorVersion}
        />
        {BUILD_CONFIG.isElectron ? (
          <>
            <UpdateCheckSection />
            <SettingRow
              name={t['com.cloud-notes.aboutCloudNotes.autoCheckUpdate.title']()}
              desc={t['com.cloud-notes.aboutCloudNotes.autoCheckUpdate.description']()}
            >
              <Switch
                checked={appSettings.autoCheckUpdate}
                onChange={onSwitchAutoCheck}
              />
            </SettingRow>
            <SettingRow
              name={t['com.cloud-notes.aboutCloudNotes.autoDownloadUpdate.title']()}
              desc={t[
                'com.cloud-notes.aboutCloudNotes.autoDownloadUpdate.description'
              ]()}
            >
              <Switch
                checked={appSettings.autoDownloadUpdate}
                onChange={onSwitchAutoDownload}
              />
            </SettingRow>
            <SettingRow
              name={t['com.cloud-notes.aboutCloudNotes.changelog.title']()}
              desc={t['com.cloud-notes.aboutCloudNotes.changelog.description']()}
              style={{ cursor: 'pointer' }}
              onClick={() => {
                urlService.openPopupWindow(BUILD_CONFIG.changelogUrl);
              }}
            >
              <ArrowRightSmallIcon />
            </SettingRow>
          </>
        ) : null}
        <SettingRow
          name={t['com.cloud-notes.telemetry.enable']()}
          desc={t['com.cloud-notes.telemetry.enable.desc']()}
        >
          <Switch
            checked={appSettings.enableTelemetry !== false}
            onChange={onSwitchTelemetry}
          />
        </SettingRow>
      </SettingWrapper>
      <SettingWrapper title={t['com.cloud-notes.aboutCloudNotes.contact.title']()}>
        <a
          className={styles.link}
          rel="noreferrer"
          href="https://notes.diegonmarcos.com"
          target="_blank"
        >
          {t['com.cloud-notes.aboutCloudNotes.contact.website']()}
          <OpenInNewIcon className="icon" />
        </a>
        <a
          className={styles.link}
          rel="noreferrer"
          href="https://notes.diegonmarcos.com/redirect/discord"
          target="_blank"
        >
          {t['com.cloud-notes.aboutCloudNotes.contact.community']()}
          <OpenInNewIcon className="icon" />
        </a>
      </SettingWrapper>
      <SettingWrapper title={t['com.cloud-notes.aboutCloudNotes.community.title']()}>
        <div className={styles.communityWrapper}>
          {relatedLinks.map(({ icon, title, link }) => {
            return (
              <div
                className={styles.communityItem}
                onClick={() => {
                  urlService.openPopupWindow(link);
                }}
                key={title}
              >
                {icon}
                <p>{title}</p>
              </div>
            );
          })}
        </div>
      </SettingWrapper>
      <SettingWrapper title={t['com.cloud-notes.aboutCloudNotes.legal.title']()}>
        <a
          className={styles.link}
          rel="noreferrer"
          href="https://notes.diegonmarcos.com/privacy"
          target="_blank"
        >
          {t['com.cloud-notes.aboutCloudNotes.legal.privacy']()}
          <OpenInNewIcon className="icon" />
        </a>
        <a
          className={styles.link}
          rel="noreferrer"
          href="https://notes.diegonmarcos.com/terms"
          target="_blank"
        >
          {t['com.cloud-notes.aboutCloudNotes.legal.tos']()}
          <OpenInNewIcon className="icon" />
        </a>
      </SettingWrapper>
    </>
  );
};
