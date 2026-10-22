import { SettingRow } from '@cloud-notes/component/setting-components';
import { Button } from '@cloud-notes/component/ui/button';
import { getUpgradeQuestionnaireLink } from '@cloud-notes/core/components/hooks/cloud-notes/use-subscription-notify';
import { AuthService, SubscriptionService } from '@cloud-notes/core/modules/cloud';
import { SubscriptionPlan, SubscriptionRecurring } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';

import * as styles from './style.css';

export const TypeformLink = () => {
  const t = useI18n();
  const subscriptionService = useService(SubscriptionService);
  const authService = useService(AuthService);

  const pro = useLiveData(subscriptionService.subscription.pro$);
  const ai = useLiveData(subscriptionService.subscription.ai$);
  const account = useLiveData(authService.session.account$);

  if (!account) return null;
  if (!pro && !ai) return null;

  const plan = [];
  if (pro) plan.push(SubscriptionPlan.Pro);
  if (ai) plan.push(SubscriptionPlan.AI);

  const link = getUpgradeQuestionnaireLink({
    name: account.info?.name,
    id: account.id,
    email: account.email,
    recurring: pro?.recurring ?? ai?.recurring ?? SubscriptionRecurring.Yearly,
    plan,
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
