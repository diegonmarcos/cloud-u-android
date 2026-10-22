import {
  AuthPageContainer,
  type User,
} from '@cloud-notes/component/auth-components';
import { useI18n } from '@cloud-notes/i18n';

import { Avatar } from '../../ui/avatar';
import { Button } from '../../ui/button';
import * as styles from './styles.css';

export const InvitationAccountMismatchPage = ({
  user,
  switchingAccount,
  onSwitchAccount,
  onOpenCloudNotes,
}: {
  user: User | null;
  switchingAccount: boolean;
  onSwitchAccount: () => void;
  onOpenCloudNotes: () => void;
}) => {
  const t = useI18n();

  return (
    <AuthPageContainer
      title={t['com.cloud-notes.invitation.account-mismatch.title']()}
      subtitle={t['com.cloud-notes.invitation.account-mismatch.description']()}
    >
      {user ? (
        <div className={styles.currentAccount}>
          <Avatar url={user.avatar ?? user.image} name={user.label} />
          <span>{user.email}</span>
        </div>
      ) : null}
      <div className={styles.accountMismatchActions}>
        <Button
          variant="primary"
          size="large"
          loading={switchingAccount}
          disabled={switchingAccount}
          onClick={onSwitchAccount}
          block
        >
          {t['com.cloud-notes.invitation.account-mismatch.switch-account']()}
        </Button>
        <Button size="large" onClick={onOpenCloudNotes} block>
          {t['com.cloud-notes.invitation.account-mismatch.back-to-cloud-notes']()}
        </Button>
      </div>
    </AuthPageContainer>
  );
};
