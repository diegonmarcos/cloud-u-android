import {
  type ConfirmModalProps,
  notify,
  useConfirmModal,
} from '@cloud-notes/component';
import { AuthService, DefaultServerService } from '@cloud-notes/core/modules/cloud';
import { UserFriendlyError } from '@cloud-notes/error';
import { ServerFeature } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { useService, useServices } from '@toeverything/infra';
import { useCallback } from 'react';

import { useNavigateHelper } from '../use-navigate-helper';

type SignOutConfirmModalI18NKeys =
  | 'title'
  | 'description'
  | 'cancel'
  | 'confirm';

export const useSignOut = ({
  onConfirm,
  confirmButtonOptions,
  contentOptions,
  ...props
}: ConfirmModalProps = {}) => {
  const t = useI18n();
  const { openConfirmModal } = useConfirmModal();
  const { jumpToSignIn, jumpToIndex } = useNavigateHelper();

  const authService = useService(AuthService);
  const { defaultServerService } = useServices({ DefaultServerService });

  const signOut = useCallback(async () => {
    onConfirm?.()?.catch(console.error);
    const enableLocalWorkspace =
      BUILD_CONFIG.isNative ||
      defaultServerService.server.config$.value.features.includes(
        ServerFeature.LocalWorkspace
      );

    try {
      await authService.signOut();
      if (enableLocalWorkspace) {
        jumpToIndex();
      } else {
        jumpToSignIn();
      }
    } catch (err) {
      console.error(err);
      const error = UserFriendlyError.fromAny(err);
      notify.error(error);
    }
  }, [authService, jumpToIndex, jumpToSignIn, defaultServerService, onConfirm]);

  const getDefaultText = useCallback(
    (key: SignOutConfirmModalI18NKeys) => {
      return t[`com.cloud-notes.auth.sign-out.confirm-modal.${key}`]();
    },
    [t]
  );

  const confirmSignOut = useCallback(() => {
    openConfirmModal({
      title: getDefaultText('title'),
      description: getDefaultText('description'),
      cancelText: getDefaultText('cancel'),
      confirmText: getDefaultText('confirm'),
      confirmButtonOptions: {
        ...confirmButtonOptions,
        variant: 'error',
        ['data-testid' as string]: 'confirm-sign-out-button',
      },
      contentOptions: {
        ...contentOptions,
        ['data-testid' as string]: 'confirm-sign-out-modal',
      },
      onConfirm: signOut,
      ...props,
    });
  }, [
    confirmButtonOptions,
    contentOptions,
    getDefaultText,
    openConfirmModal,
    props,
    signOut,
  ]);

  return confirmSignOut;
};
