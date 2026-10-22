import { Button } from '@cloud-notes/component';
import { SettingRow } from '@cloud-notes/component/setting-components';
import { getUpgradeQuestionnaireLink } from '@cloud-notes/core/components/hooks/cloud-notes/use-subscription-notify';
import {
  AuthService,
  WorkspaceSubscriptionService,
} from '@cloud-notes/core/modules/cloud';
import { SubscriptionPlan, SubscriptionRecurring } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';

import * as styles from './styles.css';

export const TypeformLink = () => {
  const t = useI18n();
  const workspaceSubscriptionService = useService(WorkspaceSubscriptionService);
  const authService = useService(AuthService);

  const workspaceSubscription = useLiveData(
    workspaceSubscriptionService.subscription.subscription$
  );
  const account = useLiveData(authService.session.account$);

  if (!account) return null;

  const link = getUpgradeQuestionnaireLink({
    name: account.info?.name,
    id: account.id,
    email: account.email,
    recurring: workspaceSubscription?.recurring ?? SubscriptionRecurring.Yearly,
    plan: SubscriptionPlan.Team,
  });

  return (
    <SettingRow
      className={styles.paymentMethod}
      name={t['com.cloud-notes.payment.billing-type-form.title']()}
      desc={t['com.cloud-notes.payment.billing-type-form.description']()}
    >
      <a target="_blank" href={link} rel="noreferrer">
        <Button>{t['com.cloud-notes.payment.billing-type-form.go']()}</Button>
      </a>
    </SettingRow>
  );
};
