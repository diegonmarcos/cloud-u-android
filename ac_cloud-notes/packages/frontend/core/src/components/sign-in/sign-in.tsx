import { Button, notify } from '@cloud-notes/component';
import {
  AuthContainer,
  AuthContent,
  AuthFooter,
  AuthHeader,
  AuthInput,
} from '@cloud-notes/component/auth-components';
import { OAuth } from '@cloud-notes/core/components/cloud-notes/auth/oauth';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import {
  AuthService,
  getSelfHostedServerName,
  ServerService,
} from '@cloud-notes/core/modules/cloud';
import type { AuthSessionStatus } from '@cloud-notes/core/modules/cloud/entities/session';
import { ServerDeploymentType } from '@cloud-notes/graphql';
import { Trans, useI18n } from '@cloud-notes/i18n';
import {
  ArrowRightBigIcon,
  LocalWorkspaceIcon,
  PublishIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { cssVar } from '@toeverything/theme';
import {
  type Dispatch,
  type SetStateAction,
  useCallback,
  useEffect,
  useState,
} from 'react';

import { useSelfhostLoginVersionGuard } from '../hooks/cloud-notes/use-selfhost-login-version-guard';
import type { SignInState } from '.';
import { Back } from './back';
import * as style from './style.css';

const emailRegex =
  /^(?:(?:[^<>()[\]\\.,;:\s@"]+(\.[^<>()[\]\\.,;:\s@"]+)*)|(".+"))@(?:(?:\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|((?:[a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;

function validateEmail(email: string) {
  return emailRegex.test(email);
}

export const SignInStep = ({
  state,
  changeState,
  onSkip,
  onAuthenticated,
}: {
  state: SignInState;
  changeState: Dispatch<SetStateAction<SignInState>>;
  onSkip: () => void;
  onAuthenticated?: (status: AuthSessionStatus) => void;
}) => {
  const t = useI18n();
  const serverService = useService(ServerService);
  const serverName = useLiveData(
    serverService.server.config$.selector(c => c.serverName)
  );
  const versionError = useSelfhostLoginVersionGuard(serverService.server);
  const isSelfhosted = useLiveData(
    serverService.server.config$.selector(
      c => c.type === ServerDeploymentType.Selfhosted
    )
  );
  const signInServerName = isSelfhosted
    ? getSelfHostedServerName(serverName)
    : serverName;
  const authService = useService(AuthService);
  const [isMutating, setIsMutating] = useState(false);

  const [email, setEmail] = useState('');

  const [isValidEmail, setIsValidEmail] = useState(true);

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

  const onContinue = useAsyncCallback(async () => {
    if (!validateEmail(email)) {
      setIsValidEmail(false);
      return;
    }

    setIsValidEmail(true);
    setIsMutating(true);

    try {
      const { methods } = await authService.checkUserByEmail(email);
      const hasPassword = methods.password.available;
      const canUseMagicLink = methods.magicLink.available;

      if (hasPassword) {
        changeState(prev => ({
          ...prev,
          email,
          step: 'signInWithPassword',
          hasPassword: true,
        }));
      } else if (canUseMagicLink) {
        changeState(prev => ({
          ...prev,
          email,
          step: 'signInWithEmail',
          hasPassword: false,
        }));
      } else {
        notify.error({
          title: 'Failed to sign in',
          message: 'This email is not available for sign in.',
        });
      }
    } catch (err: any) {
      console.error(err);

      // TODO(@eyhn): better error handling
      notify.error({
        title: 'Failed to sign in',
        message: err.message,
      });
    }

    setIsMutating(false);
  }, [authService, changeState, email]);

  const onAddSelfhosted = useCallback(() => {
    changeState(prev => ({
      ...prev,
      step: 'addSelfhosted',
    }));
  }, [changeState]);

  if (versionError && isSelfhosted) {
    return (
      <AuthContainer>
        <AuthHeader
          title={t['com.cloud-notes.auth.sign.in']()}
          subTitle={signInServerName}
        />
        <AuthContent>
          <div>{versionError}</div>
        </AuthContent>
      </AuthContainer>
    );
  }

  return (
    <AuthContainer>
      <AuthHeader
        title={t['com.cloud-notes.auth.sign.in']()}
        subTitle={signInServerName}
      />

      <AuthContent>
        <OAuth redirectUrl={state.redirectUrl} />

        <form
          onSubmit={event => {
            event.preventDefault();
            onContinue();
          }}
        >
          <AuthInput
            className={style.authInput}
            label={t['com.cloud-notes.settings.email']()}
            placeholder={t['com.cloud-notes.auth.sign.email.placeholder']()}
            onChange={setEmail}
            error={!isValidEmail}
            errorHint={
              isValidEmail ? '' : t['com.cloud-notes.auth.sign.email.error']()
            }
            onEnter={onContinue}
            type="email"
            name="username"
            autoComplete="username"
          />

          <Button
            className={style.signInButton}
            style={{ width: '100%' }}
            size="extraLarge"
            data-testid="continue-login-button"
            block
            loading={isMutating}
            disabled={isMutating}
            suffix={<ArrowRightBigIcon />}
            suffixStyle={{ width: 20, height: 20, color: cssVar('blue') }}
          >
            {t['com.cloud-notes.auth.sign.email.continue']()}
          </Button>
        </form>

        {!isSelfhosted && (
          <>
            <div className={style.authMessage}>
              {/*prettier-ignore*/}
              <Trans i18nKey="com.cloud-notes.auth.sign.message">
                By clicking &quot;Continue with Google/Email&quot; above, you acknowledge that
                you agree to Cloud Notes&apos;s <a href="https://notes.diegonmarcos.com/terms" target="_blank" rel="noreferrer">Terms of Conditions</a> and <a href="https://notes.diegonmarcos.com/privacy" target="_blank" rel="noreferrer">Privacy Policy</a>.
            </Trans>
            </div>
            <div className={style.skipDivider}>
              <div className={style.skipDividerLine} />
              <span className={style.skipDividerText}>or</span>
              <div className={style.skipDividerLine} />
            </div>
            <div className={style.skipSection}>
              {BUILD_CONFIG.isNative ? (
                <Button
                  variant="plain"
                  className={style.addSelfhostedButton}
                  prefix={
                    <PublishIcon className={style.addSelfhostedButtonPrefix} />
                  }
                  onClick={onAddSelfhosted}
                >
                  {t['com.cloud-notes.auth.sign.add-selfhosted']()}
                </Button>
              ) : (
                <div className={style.skipText}>
                  {t['com.cloud-notes.mobile.sign-in.skip.hint']()}
                </div>
              )}
              <Button
                variant="plain"
                onClick={onSkip}
                className={style.skipLink}
                prefix={<LocalWorkspaceIcon className={style.skipLinkIcon} />}
              >
                {t['com.cloud-notes.mobile.sign-in.skip.link']()}
              </Button>
            </div>
          </>
        )}
      </AuthContent>
      {isSelfhosted && (
        <AuthFooter>
          <Back changeState={changeState} />
        </AuthFooter>
      )}
    </AuthContainer>
  );
};
