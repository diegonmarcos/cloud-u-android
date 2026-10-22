import { useI18n } from '@cloud-notes/i18n';
import { SignOutIcon } from '@blocksuite/icons/rc';
import type { JSX } from 'react';

import { Avatar } from '../../ui/avatar';
import { Button, IconButton } from '../../ui/button';
import { ThemedImg } from '../../ui/themed-img';
import { CloudNotesOtherPageLayout } from '../cloud-notes-other-page-layout';
import illustrationDark from '../cloud-notes-other-page-layout/assets/other-page.dark.png';
import illustrationLight from '../cloud-notes-other-page-layout/assets/other-page.light.png';
import type { User } from '../auth-components';
import {
  illustration,
  info,
  largeButtonEffect,
  notFoundPageContainer,
  wrapper,
} from './styles.css';

export interface NotFoundPageProps {
  user?: User | null;
  signInComponent?: JSX.Element;
  onBack: () => void;
  onSignOut: () => void;
}
export const NoPermissionOrNotFound = ({
  user,
  onBack,
  onSignOut,
  signInComponent,
}: NotFoundPageProps) => {
  const t = useI18n();

  return (
    <CloudNotesOtherPageLayout>
      <div className={notFoundPageContainer} data-testid="not-found">
        {user ? (
          <>
            <div className={info}>
              <p className={wrapper}>{t['404.hint']()}</p>
              <div className={wrapper}>
                <Button
                  variant="primary"
                  size="extraLarge"
                  onClick={onBack}
                  className={largeButtonEffect}
                >
                  {t['404.back']()}
                </Button>
              </div>
              <div className={wrapper}>
                <Avatar url={user.avatar ?? user.image} name={user.label} />
                <span style={{ margin: '0 12px' }}>{user.email}</span>
                <IconButton
                  onClick={onSignOut}
                  size="20"
                  tooltip={t['404.signOut']()}
                >
                  <SignOutIcon />
                </IconButton>
              </div>
            </div>
            <div className={wrapper}>
              <ThemedImg
                draggable={false}
                className={illustration}
                lightSrc={illustrationLight}
                darkSrc={illustrationDark}
              />
            </div>
          </>
        ) : (
          signInComponent
        )}
      </div>
    </CloudNotesOtherPageLayout>
  );
};

export const NotFoundPage = ({
  user,
  onBack,
  onSignOut,
}: NotFoundPageProps) => {
  const t = useI18n();

  return (
    <CloudNotesOtherPageLayout>
      <div className={notFoundPageContainer} data-testid="not-found">
        <div className={info}>
          <p className={wrapper}>{t['404.hint']()}</p>
          <div className={wrapper}>
            <Button
              variant="primary"
              size="extraLarge"
              onClick={onBack}
              className={largeButtonEffect}
            >
              {t['404.back']()}
            </Button>
          </div>
          {user ? (
            <div className={wrapper}>
              <Avatar url={user.avatar ?? user.image} name={user.label} />
              <span style={{ margin: '0 12px' }}>{user.email}</span>
              <IconButton
                onClick={onSignOut}
                size="20"
                tooltip={t['404.signOut']()}
              >
                <SignOutIcon />
              </IconButton>
            </div>
          ) : null}
        </div>
        <div className={wrapper}>
          <ThemedImg
            draggable={false}
            className={illustration}
            lightSrc={illustrationLight}
            darkSrc={illustrationDark}
          />
        </div>
      </div>
    </CloudNotesOtherPageLayout>
  );
};
