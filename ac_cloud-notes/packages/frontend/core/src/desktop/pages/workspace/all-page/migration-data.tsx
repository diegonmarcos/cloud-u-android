import { Button } from '@cloud-notes/component';
import { DocCreatedByUpdatedBySyncService } from '@cloud-notes/core/modules/cloud';
import { UserFriendlyError } from '@cloud-notes/error';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useTheme } from 'next-themes';
import { useCallback, useMemo } from 'react';

import darkMigration from './dark-migration.png';
import lightMigration from './light-migration.png';
import * as styles from './migration-data.css';

const MigrationBackgroundCover = () => {
  const { theme } = useTheme();
  return (
    <img
      src={theme === 'light' ? lightMigration : darkMigration}
      className={styles.migrationBackgroundCover}
    />
  );
};

export const MigrationAllDocsDataNotification = () => {
  const t = useI18n();
  const docCreatedByUpdatedBySyncService = useService(
    DocCreatedByUpdatedBySyncService
  );
  const needSync = useLiveData(docCreatedByUpdatedBySyncService.needSync$);
  const syncing = useLiveData(docCreatedByUpdatedBySyncService.syncing$);
  const error = useLiveData(docCreatedByUpdatedBySyncService.error$);
  const errorMessage = useMemo(() => {
    if (error) {
      const userFriendlyError = UserFriendlyError.fromAny(error);
      return t[`error.${userFriendlyError.name}`](userFriendlyError.data);
    }
    return null;
  }, [error, t]);
  const progress = useLiveData(docCreatedByUpdatedBySyncService.progress$);

  const handleSync = useCallback(() => {
    docCreatedByUpdatedBySyncService.sync();
  }, [docCreatedByUpdatedBySyncService]);

  if (!needSync) {
    return null;
  }

  return (
    <div className={styles.migrationDataNotificationContainer}>
      <div className={styles.migrationDataNotificationTitle}>
        {t['com.cloud-notes.migration-all-docs-notification.header']()}
      </div>
      <div className={styles.migrationDataNotificationContent}>
        <MigrationBackgroundCover />
        {t['com.cloud-notes.migration-all-docs-notification.desc']()}
      </div>

      {error && (
        <div className={styles.migrationDataNotificationError}>
          {t['com.cloud-notes.migration-all-docs-notification.error']({
            errorMessage: errorMessage ?? '',
          })}
        </div>
      )}

      <Button loading={syncing} onClick={handleSync}>
        {t['com.cloud-notes.migration-all-docs-notification.button']()}
        {syncing ? ` (${Math.floor(progress * 100)}%)...` : ''}
      </Button>
    </div>
  );
};
