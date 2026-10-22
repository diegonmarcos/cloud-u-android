import { Permission } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { useCallback, useEffect, useState } from 'react';

import { ConfirmModal } from '../../ui/modal';
import { AuthInput } from '..//auth-components';
import { emailRegex } from '..//auth-components/utils';

export interface InviteModalProps {
  open: boolean;
  setOpen: (value: boolean) => void;
  onConfirm: (params: { email: string; permission: Permission }) => void;
  isMutating: boolean;
}

export const InviteModal = ({
  open,
  setOpen,
  onConfirm,
  isMutating,
}: InviteModalProps) => {
  const t = useI18n();
  const [inviteEmail, setInviteEmail] = useState('');
  const [permission] = useState(Permission.Collaborator);
  const [isValidEmail, setIsValidEmail] = useState(true);

  const handleConfirm = useCallback(() => {
    if (!emailRegex.test(inviteEmail)) {
      setIsValidEmail(false);
      return;
    }
    setIsValidEmail(true);

    onConfirm({
      email: inviteEmail,
      permission,
    });
  }, [inviteEmail, onConfirm, permission]);

  useEffect(() => {
    if (!open) {
      setInviteEmail('');
      setIsValidEmail(true);
    }
  }, [open]);

  return (
    <ConfirmModal
      open={open}
      onOpenChange={setOpen}
      title={t['Invite Members']()}
      description={t['Invite Members Message']()}
      cancelText={t['com.cloud-notes.inviteModal.button.cancel']()}
      contentOptions={{
        ['data-testid' as string]: 'invite-modal',
        style: {
          padding: '20px 26px',
        },
      }}
      confirmText={t['Invite']()}
      confirmButtonOptions={{
        loading: isMutating,
        variant: 'primary',
        'data-testid': 'confirm-enable-cloud-notes-cloud-button',
      }}
      onConfirm={handleConfirm}
    >
      <AuthInput
        disabled={isMutating}
        placeholder="email@example.com"
        value={inviteEmail}
        onChange={setInviteEmail}
        error={!isValidEmail}
        errorHint={isValidEmail ? '' : t['com.cloud-notes.auth.sign.email.error']()}
        onEnter={handleConfirm}
        size="large"
      />
    </ConfirmModal>
  );
};
