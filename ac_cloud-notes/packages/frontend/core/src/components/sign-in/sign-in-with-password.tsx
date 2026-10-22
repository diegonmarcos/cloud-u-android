import { notify } from '@cloud-notes/component';
import {
  AuthContainer,
  AuthContent,
  AuthFooter,
  AuthHeader,
  AuthInput,
} from '@cloud-notes/component/auth-components';
import { Button } from '@cloud-notes/component/ui/button';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import {
  AuthService,
  CaptchaService,
  getSelfHostedServerName,
  ServerService,
} from '@cloud-notes/core/modules/cloud';
import type { AuthSessionStatus } from '@cloud-notes/core/modules/cloud/entities/session';
import { Unreachable } from '@cloud-notes/env/constant';
import { UserFriendlyError } from '@cloud-notes/error';
import { ServerDeploymentType } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import type { Dispatch, SetStateAction } from 'react';
import { useCallback, useEffect, useState } from 'react';

import type { SignInState } from '.';
import { Back } from './back';
import { Captcha } from './captcha';
import * as styles from './style.css';

export const SignInWithPasswordStep = ({
  state,
  changeState,
  onAuthenticated,
}: {
  state: SignInState;
  changeState: Dispatch<SetStateAction<SignInState>>;
  onAuthenticated?: (status: AuthSessionStatus) => void;
}) => {
  const t = useI18n();
  const authService = useService(AuthService);

  const email = state.email;

  if (!email) {
    throw new Unreachable();
  }

  const [password, setPassword] = useState('');
  const [passwordError, setPasswordError] = useState(false);
  const [passwordErrorHint, setPasswordErrorHint] = useState('');
  const captchaService = useService(CaptchaService);
  const serverService = useService(ServerService);
  const isSelfhosted = useLiveData(
    serverService.server.config$.selector(
      c => c.type === ServerDeploymentType.Selfhosted
    )
  );
  const serverName = useLiveData(
    serverService.server.config$.selector(c => c.serverName)
  );
  const signInServerName = isSelfhosted
    ? getSelfHostedServerName(serverName)
    : serverName;

  const verifyToken = useLiveData(captchaService.verifyToken$);
  const needCaptcha = useLiveData(captchaService.needCaptcha$);
  const challenge = useLiveData(captchaService.challenge$);
  const [isLoading, setIsLoading] = useState(false);

  const loginStatus = useLiveData(authService.session.status$);

  useEffect(() => {
    if (loginStatus === 'authenticated') {
      notify.success({
        title: t['com.cloud-notes.auth.toast.title.signed-in'](),
        message: t['com.cloud-notes.auth.toast.message.signed-in'](),
      });
    }
    onAuthenticated?.(loginStatus);
  }, [loginStatus, onAuthenticated, t]);

  useEffect(() => {
    setPasswordErrorHint(t['com.cloud-notes.auth.password.error']());
  }, [t]);

  const onSignIn = useAsyncCallback(async () => {
    if (isLoading || (!verifyToken && needCaptcha)) return;
    setIsLoading(true);

    try {
      await authService.signInPassword({
        email,
        password,
        verifyToken,
        challenge,
      });
    } catch (err) {
      console.error(err);
      const error = UserFriendlyError.fromAny(err);

      if (
        error.is('WRONG_SIGN_IN_CREDENTIALS') ||
        error.is('PASSWORD_REQUIRED')
      ) {
        setPasswordError(true);
        setPasswordErrorHint(t['com.cloud-notes.auth.password.error']());
      } else {
        setPasswordError(false);
        notify.error({
          title: t['com.cloud-notes.auth.toast.title.failed'](),
          message: error.is('REQUEST_ABORTED')
            ? t['error.NETWORK_ERROR']()
            : t[`error.${error.name}`](error.data),
        });
      }
      captchaService.revalidate();
    } finally {
      setIsLoading(false);
    }
  }, [
    isLoading,
    verifyToken,
    needCaptcha,
    captchaService,
    authService,
    email,
    password,
    challenge,
    t,
  ]);

  const sendMagicLink = useCallback(() => {
    changeState(prev => ({ ...prev, step: 'signInWithEmail' }));
  }, [changeState]);

  return (
    <AuthContainer>
      <AuthHeader
        title={t['com.cloud-notes.auth.sign.in']()}
        subTitle={signInServerName}
      />

      <AuthContent>
        <form
          onSubmit={event => {
            event.preventDefault();
            onSignIn();
          }}
        >
          <AuthInput
            label={t['com.cloud-notes.settings.email']()}
            readOnly={true}
            value={email}
            type="email"
            name="username"
            autoComplete="username"
          />
          <AuthInput
            autoFocus
            data-testid="password-input"
            label={t['com.cloud-notes.auth.password']()}
            value={password}
            type="password"
            name="password"
            autoComplete="current-password"
            onChange={(value: string) => {
              setPassword(value);
              if (passwordError) {
                setPasswordError(false);
                setPasswordErrorHint(t['com.cloud-notes.auth.password.error']());
              }
            }}
            error={passwordError}
            errorHint={passwordErrorHint}
            onEnter={onSignIn}
          />
          {!verifyToken && needCaptcha && <Captcha />}
          <Button
            data-testid="sign-in-button"
            variant="primary"
            size="extraLarge"
            style={{ width: '100%' }}
            disabled={isLoading || (!verifyToken && needCaptcha)}
          >
            {t['com.cloud-notes.auth.sign.in']()}
          </Button>
        </form>
        {!isSelfhosted && (
          <div className={styles.passwordButtonRow}>
            <a
              data-testid="send-magic-link-button"
              className={styles.linkButton}
              onClick={sendMagicLink}
            >
              {t['com.cloud-notes.auth.sign.auth.code.send-email.sign-in']()}
            </a>
          </div>
        )}
      </AuthContent>
      <AuthFooter>
        <Back changeState={changeState} />
      </AuthFooter>
    </AuthContainer>
  );
};
