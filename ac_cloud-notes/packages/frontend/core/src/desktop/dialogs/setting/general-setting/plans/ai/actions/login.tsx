import { Button, type ButtonProps } from '@cloud-notes/component';
import { GlobalDialogService } from '@cloud-notes/core/modules/dialogs';
import { useI18n } from '@cloud-notes/i18n';
import { useService } from '@toeverything/infra';
import { useCallback } from 'react';

export const AILogin = (btnProps: ButtonProps) => {
  const t = useI18n();
  const globalDialogService = useService(GlobalDialogService);

  const onClickSignIn = useCallback(() => {
    globalDialogService.open('sign-in', {});
  }, [globalDialogService]);

  return (
    <Button onClick={onClickSignIn} variant="primary" {...btnProps}>
      {t['com.cloud-notes.payment.ai.action.login.button-label']()}
    </Button>
  );
};
