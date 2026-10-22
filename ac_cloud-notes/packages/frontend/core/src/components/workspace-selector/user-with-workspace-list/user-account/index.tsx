import { UserPlanButton } from '@cloud-notes/core/components/cloud-notes/auth/user-plan-button';
import type { SyntheticEvent } from 'react';

import * as styles from './index.css';

export const UserAccountItem = ({
  email,
  onClick,
}: {
  email: string;
  onClick: (e: SyntheticEvent<Element, Event>) => void;
  onEventEnd?: () => void;
}) => {
  return (
    <div className={styles.userAccountContainer}>
      <div className={styles.leftContainer}>
        <div className={styles.userEmail}>{email}</div>
      </div>
      <UserPlanButton onClick={onClick} />
    </div>
  );
};
