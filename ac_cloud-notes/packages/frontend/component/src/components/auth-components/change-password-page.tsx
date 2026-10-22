import type { PasswordLimitsFragment } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import type { FC } from 'react';
import { useCallback, useState } from 'react';

import { Button } from '../../ui/button';
import { notify } from '../../ui/notification';
import { AuthPageContainer } from './auth-page-container';
import { SetPassword } from './set-password';

export const ChangePasswordPage: FC<{
  passwordLimits: PasswordLimitsFragment;
  onSetPassword: (password: string) => Promise<void>;
  onOpenCloudNotes: () => void;
}> = ({ passwordLimits, onSetPassword: propsOnSetPassword, onOpenCloudNotes }) => {
  const t = useI18n();
  const [hasSetUp, setHasSetUp] = useState(false);

  const onSetPassword = useCallback(
    (passWord: string) => {
      propsOnSetPassword(passWord)
        .then(() => setHasSetUp(true))
        .catch(e =>
          notify.error({
            title: t['com.cloud-notes.auth.password.set-failed'](),
            message: String(e),
          })
        );
    },
    [propsOnSetPassword, t]
  );

  return (
    <AuthPageContainer
      title={
        hasSetUp
          ? t['com.cloud-notes.auth.reset.password.page.success']()
          : t['com.cloud-notes.auth.reset.password.page.title']()
      }
      subtitle={
        hasSetUp
          ? t['com.cloud-notes.auth.sent.reset.password.success.message']()
          : t['com.cloud-notes.auth.page.sent.email.subtitle']({
              min: String(passwordLimits.minLength),
              max: String(passwordLimits.maxLength),
            })
      }
    >
      {hasSetUp ? (
        <Button variant="primary" size="large" onClick={onOpenCloudNotes}>
          {t['com.cloud-notes.auth.open.cloud-notes']()}
        </Button>
      ) : (
        <SetPassword
          passwordLimits={passwordLimits}
          onSetPassword={onSetPassword}
        />
      )}
    </AuthPageContainer>
  );
};
