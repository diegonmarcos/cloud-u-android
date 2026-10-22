import { Button, notify } from '@cloud-notes/component';
import { SettingRow } from '@cloud-notes/component/setting-components';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { useMutation } from '@cloud-notes/core/components/hooks/use-mutation';
import { UrlService } from '@cloud-notes/core/modules/url';
import { UserFriendlyError } from '@cloud-notes/error';
import { createCustomerPortalMutation } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { useService } from '@toeverything/infra';

import * as styles from './styles.css';

export const PaymentMethodUpdater = () => {
  const { isMutating, trigger } = useMutation({
    mutation: createCustomerPortalMutation,
  });
  const urlService = useService(UrlService);
  const t = useI18n();

  const update = useAsyncCallback(async () => {
    await trigger(null, {
      onSuccess: data => {
        urlService.openExternal(data.createCustomerPortal);
      },
    }).catch(e => {
      const userFriendlyError = UserFriendlyError.fromAny(e);
      notify.error(userFriendlyError);
    });
  }, [trigger, urlService]);

  return (
    <SettingRow
      className={styles.paymentMethod}
      name={t['com.cloud-notes.payment.billing-setting.payment-method']()}
      desc={t[
        'com.cloud-notes.payment.billing-setting.payment-method.description'
      ]()}
    >
      <Button onClick={update} loading={isMutating} disabled={isMutating}>
        {t['com.cloud-notes.payment.billing-setting.payment-method.go']()}
      </Button>
    </SettingRow>
  );
};
