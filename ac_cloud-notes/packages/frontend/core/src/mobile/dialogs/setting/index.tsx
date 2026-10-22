import { notify } from '@cloud-notes/component';
import { AuthService } from '@cloud-notes/core/modules/cloud';
import type {
  DialogComponentProps,
  WORKSPACE_DIALOG_SCHEMA,
} from '@cloud-notes/core/modules/dialogs';
import { UrlService } from '@cloud-notes/core/modules/url';
import { copyTextToClipboard } from '@cloud-notes/core/utils/clipboard';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useEffect } from 'react';

import { AboutGroup } from './about';
import { AppearanceGroup } from './appearance';
import { DevicesGroup } from './devices';
import { ExperimentalFeatureSetting } from './experimental';
import { SettingGroup } from './group';
import { OthersGroup } from './others';
import { DeleteAccount } from './others/delete-account';
import { RowLayout } from './row.layout';
import * as styles from './style.css';
import { PlansGroup } from './subscription';
import { SwipeDialog } from './swipe-dialog';
import { UserProfile } from './user-profile';
import { UserUsage } from './user-usage';

const CLOUD_NOTES_MOBILE_STORE_URL = BUILD_CONFIG.isIOS
  ? 'https://apps.apple.com/app/notes-whiteboard-ai-cloud-notes/id6736937980'
  : BUILD_CONFIG.isAndroid
    ? 'https://play.google.com/store/apps/details?id=app.cloudnotes.pro'
    : undefined;
const CLOUD_NOTES_DOWNLOAD_URL = 'https://notes.diegonmarcos.com/download';

const SupportGroup = () => {
  const t = useI18n();
  const urlService = useService(UrlService);

  const shareApp = useCallback(async () => {
    const shareData = {
      title: 'Cloud Notes',
      text: t['com.cloud-notes.mobile.setting.support.invite-message'](),
      url: CLOUD_NOTES_DOWNLOAD_URL,
    };

    if ('share' in navigator && typeof navigator.share === 'function') {
      try {
        await navigator.share(shareData);
        return;
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return;
        }
      }
    }

    const copied = await copyTextToClipboard(CLOUD_NOTES_DOWNLOAD_URL);
    if (copied) {
      notify.success({ title: t['Copied link to clipboard']() });
      return;
    }

    urlService.openExternal(CLOUD_NOTES_DOWNLOAD_URL);
  }, [t, urlService]);

  return (
    <SettingGroup title={t['com.cloud-notes.mobile.setting.support.title']()}>
      {CLOUD_NOTES_MOBILE_STORE_URL ? (
        <RowLayout
          label={t['com.cloud-notes.mobile.setting.support.rate']()}
          onClick={() => urlService.openExternal(CLOUD_NOTES_MOBILE_STORE_URL)}
        />
      ) : null}
      <RowLayout
        label={t['com.cloud-notes.mobile.setting.support.invite']()}
        onClick={() => void shareApp()}
      />
    </SettingGroup>
  );
};

const DangerZoneGroup = ({
  onDeleteFinished,
}: {
  onDeleteFinished?: () => void;
}) => {
  const t = useI18n();
  const authService = useService(AuthService);
  const account = useLiveData(authService.session.account$);

  if (!account) {
    return null;
  }

  return (
    <SettingGroup
      title={
        <span className={styles.dangerZoneTitle}>
          {t['com.cloud-notes.mobile.setting.danger-zone.title']()}
        </span>
      }
    >
      <DeleteAccount onDeleteFinished={onDeleteFinished} />
    </SettingGroup>
  );
};

const MobileSetting = ({
  onDeleteFinished,
}: {
  onDeleteFinished?: () => void;
}) => {
  const session = useService(AuthService).session;
  const status = useLiveData(session.status$);

  useEffect(() => {
    session.revalidate();
  }, [session]);

  return (
    <div className={styles.root}>
      <UserProfile />
      <UserUsage />
      <PlansGroup />
      {status === 'authenticated' ? <DevicesGroup /> : null}
      <AppearanceGroup />
      <AboutGroup />
      <ExperimentalFeatureSetting />
      <SupportGroup />
      <OthersGroup />
      <DangerZoneGroup onDeleteFinished={onDeleteFinished} />
    </div>
  );
};

export const SettingDialog = ({
  close,
}: DialogComponentProps<WORKSPACE_DIALOG_SCHEMA['setting']>) => {
  const t = useI18n();

  return (
    <SwipeDialog
      title={t['com.cloud-notes.mobile.setting.header-title']()}
      open
      onOpenChange={() => close()}
    >
      <MobileSetting onDeleteFinished={close} />
    </SwipeDialog>
  );
};
