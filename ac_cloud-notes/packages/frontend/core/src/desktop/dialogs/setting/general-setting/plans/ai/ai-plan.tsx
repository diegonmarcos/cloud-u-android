import { Button } from '@cloud-notes/component';
import { AuthService, SubscriptionService } from '@cloud-notes/core/modules/cloud';
import { i18nTime, useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useEffect } from 'react';

import { AICancel, AILogin, AIResume, AISubscribe } from './actions';
import * as styles from './ai-plan.css';
import { AIPlanLayout } from './layout';

export const AIPlan = () => {
  const t = useI18n();

  const authService = useService(AuthService);
  const subscriptionService = useService(SubscriptionService);
  const subscription = useLiveData(subscriptionService.subscription.ai$);
  const price = useLiveData(subscriptionService.prices.aiPrice$);
  const isLoggedIn =
    useLiveData(authService.session.status$) === 'authenticated';

  useEffect(() => {
    subscriptionService.subscription.revalidate();
    subscriptionService.prices.revalidate();
  }, [subscriptionService]);

  // yearly subscription should always be available
  if (!price?.yearlyAmount) {
    return null;
  }

  const billingTip = subscription?.nextBillAt
    ? t['com.cloud-notes.payment.ai.billing-tip.next-bill-at']({
        due: i18nTime(subscription.nextBillAt, {
          absolute: { accuracy: 'day' },
        }),
      })
    : subscription?.canceledAt && subscription.end
      ? t['com.cloud-notes.payment.ai.billing-tip.end-at']({
          end: i18nTime(subscription.end, {
            absolute: { accuracy: 'day' },
          }),
        })
      : null;

  return (
    <AIPlanLayout
      caption={
        subscription
          ? t['com.cloud-notes.payment.ai.pricing-plan.caption-purchased']()
          : t['com.cloud-notes.payment.ai.pricing-plan.caption-free']()
      }
      actionButtons={
        isLoggedIn ? (
          subscription ? (
            subscription.canceledAt ? (
              <AIResume className={styles.purchaseButton} />
            ) : (
              <AICancel className={styles.purchaseButton} />
            )
          ) : (
            <>
              <AISubscribe
                className={styles.purchaseButton}
                displayedFrequency="monthly"
              />
              <a href="https://notes.diegonmarcos.com" target="_blank" rel="noreferrer">
                <Button className={styles.learnAIButton}>
                  {t['com.cloud-notes.payment.ai.pricing-plan.learn']()}
                </Button>
              </a>
            </>
          )
        ) : (
          <AILogin className={styles.purchaseButton} />
        )
      }
      billingTip={billingTip}
    />
  );
};
