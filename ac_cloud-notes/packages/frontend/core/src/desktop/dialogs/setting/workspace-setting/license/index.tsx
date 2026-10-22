import { Button, notify } from '@cloud-notes/component';
import {
  SettingHeader,
  SettingRow,
} from '@cloud-notes/component/setting-components';
import { getUpgradeQuestionnaireLink } from '@cloud-notes/core/components/hooks/cloud-notes/use-subscription-notify';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { useMutation } from '@cloud-notes/core/components/hooks/use-mutation';
import {
  AuthService,
  SelfhostLicenseService,
  WorkspaceSubscriptionService,
} from '@cloud-notes/core/modules/cloud';
import { WorkspacePermissionService } from '@cloud-notes/core/modules/permissions';
import { UrlService } from '@cloud-notes/core/modules/url';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { UserFriendlyError } from '@cloud-notes/error';
import {
  createSelfhostCustomerPortalMutation,
  SubscriptionPlan,
  SubscriptionRecurring,
  SubscriptionVariant,
} from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { FrameworkScope, useLiveData, useService } from '@toeverything/infra';
import { useCallback, useEffect, useState } from 'react';

import { EnableCloudPanel } from '../preference/enable-cloud';
import { SelfHostTeamCard } from './self-host-team-card';
import { SelfHostTeamPlan } from './self-host-team-plan';
import * as styles from './styles.css';
import { UploadLicenseModal } from './upload-license-modal';

export const WorkspaceSettingLicense = ({
  onCloseSetting,
}: {
  onCloseSetting: () => void;
}) => {
  const workspace = useService(WorkspaceService).workspace;

  const t = useI18n();

  if (workspace === null) {
    return null;
  }

  return (
    <FrameworkScope scope={workspace.scope}>
      <SettingHeader
        title={t['com.cloud-notes.settings.workspace.license']()}
        subtitle={t['com.cloud-notes.settings.workspace.license.description']()}
      />
      <SelfHostTeamPlan />
      {workspace.flavour === 'local' ? (
        <EnableCloudPanel onCloseSetting={onCloseSetting} />
      ) : (
        <>
          <SelfHostTeamCard />
          <ReplaceLicenseModal />
          <TypeFormLink />
          <PaymentMethodUpdater />
        </>
      )}
    </FrameworkScope>
  );
};

const ReplaceLicenseModal = () => {
  const t = useI18n();
  const selfhostLicenseService = useService(SelfhostLicenseService);
  const license = useLiveData(selfhostLicenseService.license$);
  const isOneTimePurchase = license?.variant === SubscriptionVariant.Onetime;
  const permission = useService(WorkspacePermissionService).permission;
  const isTeam = useLiveData(permission.isTeam$);
  const [openUploadModal, setOpenUploadModal] = useState(false);

  const handleClick = useCallback(() => {
    setOpenUploadModal(true);
  }, []);

  useEffect(() => {
    selfhostLicenseService.revalidate();
  }, [selfhostLicenseService]);

  if (!isTeam || !isOneTimePurchase) {
    return null;
  }

  return (
    <>
      <SettingRow
        className={styles.paymentMethod}
        name={t[
          'com.cloud-notes.settings.workspace.license.self-host-team.replace-license.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.workspace.license.self-host-team.replace-license.description'
        ]()}
      >
        <Button onClick={handleClick}>
          {t[
            'com.cloud-notes.settings.workspace.license.self-host-team.replace-license.upload'
          ]()}
        </Button>
      </SettingRow>
      <UploadLicenseModal
        open={openUploadModal}
        onOpenChange={setOpenUploadModal}
      />
    </>
  );
};

const TypeFormLink = () => {
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
    plan: SubscriptionPlan.SelfHostedTeam,
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

const PaymentMethodUpdater = () => {
  const workspace = useService(WorkspaceService).workspace;

  const permission = useService(WorkspacePermissionService).permission;
  const isTeam = useLiveData(permission.isTeam$);

  const { isMutating, trigger } = useMutation({
    mutation: createSelfhostCustomerPortalMutation,
  });
  const urlService = useService(UrlService);
  const t = useI18n();

  const update = useAsyncCallback(async () => {
    await trigger(
      {
        workspaceId: workspace.id,
      },
      {
        onSuccess: data => {
          urlService.openExternal(data.createSelfhostWorkspaceCustomerPortal);
        },
      }
    ).catch(e => {
      const userFriendlyError = UserFriendlyError.fromAny(e);
      notify.error(userFriendlyError);
    });
  }, [trigger, urlService, workspace.id]);

  if (!isTeam) {
    return null;
  }

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
