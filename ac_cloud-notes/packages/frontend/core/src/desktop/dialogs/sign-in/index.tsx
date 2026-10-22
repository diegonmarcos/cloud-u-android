import { Modal } from '@cloud-notes/component';
import { SignInPanel, type SignInStep } from '@cloud-notes/core/components/sign-in';
import type { AuthSessionStatus } from '@cloud-notes/core/modules/cloud/entities/session';
import type {
  DialogComponentProps,
  GLOBAL_DIALOG_SCHEMA,
} from '@cloud-notes/core/modules/dialogs';
import { useCallback } from 'react';
export const SignInDialog = ({
  close,
  server: initialServerBaseUrl,
  step,
}: DialogComponentProps<GLOBAL_DIALOG_SCHEMA['sign-in']>) => {
  const onAuthenticated = useCallback(
    (status: AuthSessionStatus) => {
      if (status === 'authenticated') {
        close();
      }
    },
    [close]
  );
  return (
    <Modal
      open
      persistent
      onOpenChange={() => close()}
      width={400}
      contentOptions={{
        ['data-testid' as string]: 'auth-modal',
        style: {
          padding: '44px 40px 20px',
          minHeight: 550,
          maxHeight: 650,
        },
      }}
    >
      <SignInPanel
        onSkip={close}
        onAuthenticated={onAuthenticated}
        server={initialServerBaseUrl}
        initStep={step as SignInStep}
      />
    </Modal>
  );
};
