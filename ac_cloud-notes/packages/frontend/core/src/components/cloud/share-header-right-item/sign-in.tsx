import { Button } from '@cloud-notes/component/ui/button';
import { GlobalDialogService } from '@cloud-notes/core/modules/dialogs';
import { useI18n } from '@cloud-notes/i18n';
import { useService } from '@toeverything/infra';
import { useCallback } from 'react';

import * as styles from './styles.css';

export const SignIn = () => {
  const globalDialogService = useService(GlobalDialogService);

  const t = useI18n();

  const onClickSignIn = useCallback(() => {
    globalDialogService.open('sign-in', {});
  }, [globalDialogService]);

  return (
    <Button
      className={styles.editButton}
      onClick={onClickSignIn}
      data-testid="share-page-sign-in-button"
    >
      {t['com.cloud-notes.share-page.header.login']()}
    </Button>
  );
};
