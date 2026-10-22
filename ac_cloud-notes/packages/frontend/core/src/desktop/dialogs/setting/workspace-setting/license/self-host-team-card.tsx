import { Button, ConfirmModal, Input, Modal, notify } from '@cloud-notes/component';
import { SettingRow } from '@cloud-notes/component/setting-components';
import { useEnableCloud } from '@cloud-notes/core/components/hooks/cloud-notes/use-enable-cloud';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { useMutation } from '@cloud-notes/core/components/hooks/use-mutation';
import {
  SelfhostLicenseService,
  WorkspaceSubscriptionService,
} from '@cloud-notes/core/modules/cloud';
import { WorkspacePermissionService } from '@cloud-notes/core/modules/permissions';
import { WorkspaceQuotaService } from '@cloud-notes/core/modules/quota';
import { UrlService } from '@cloud-notes/core/modules/url';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { UserFriendlyError } from '@cloud-notes/error';
import {
  createSelfhostCustomerPortalMutation,
  SubscriptionVariant,
} from '@cloud-notes/graphql';
import { Trans, useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { cssVarV2 } from '@toeverything/theme/v2';
import clsx from 'clsx';
import { useCallback, useEffect, useMemo, useState } from 'react';

import * as styles from './styles.css';
import { UploadLicenseModal } from './upload-license-modal';

export const SelfHostTeamCard = () => {
  const t = useI18n();

  const workspace = useService(WorkspaceService).workspace;
  const workspaceQuotaService = useService(WorkspaceQuotaService);
  const workspaceSubscriptionService = useService(WorkspaceSubscriptionService);

  const permission = useService(WorkspacePermissionService).permission;
  const isTeam = useLiveData(permission.isTeam$);
  const workspaceQuota = useLiveData(workspaceQuotaService.quota.quota$);
  const confirmEnableCloud = useEnableCloud();
  const isLocalWorkspace = workspace.flavour === 'local';

  const [openModal, setOpenModal] = useState(false);
  const [openUploadModal, setOpenUploadModal] = useState(false);
  const [loading, setLoading] = useState(false);
  const selfhostLicenseService = useService(SelfhostLicenseService);
  const license = useLiveData(selfhostLicenseService.license$);
  const hasLicense = !!license;
  const isOneTimePurchase = license?.variant === SubscriptionVariant.Onetime;

  const revalidate = useCallback(() => {
    permission.revalidate();
    workspaceQuotaService.quota.revalidate();
    workspaceSubscriptionService.subscription.revalidate();
    selfhostLicenseService.revalidate();
  }, [
    permission,
    selfhostLicenseService,
    workspaceQuotaService,
    workspaceSubscriptionService,
  ]);

  useEffect(() => {
    revalidate();
  }, [revalidate]);

  const description = useMemo(() => {
    if (isTeam) {
      return (
        <div>
          <p>
            {t[
              'com.cloud-notes.settings.workspace.license.self-host-team.team.description'
            ]({
              expirationDate: new Date(
                license?.expiredAt || 0
              ).toLocaleDateString(),
              leftDays: Math.floor(
                // oxlint-disable-next-line react-hooks-js/purity
                (new Date(license?.expiredAt || 0).getTime() - Date.now()) /
                  (1000 * 60 * 60 * 24)
              ).toLocaleString(),
            })}
          </p>
          {isOneTimePurchase ? (
            <p>
              <Trans
                i18nKey="com.cloud-notes.settings.workspace.license.self-host-team.team.license"
                components={{ 1: <strong /> }}
              />
            </p>
          ) : null}
        </div>
      );
    }
    return t[
      'com.cloud-notes.settings.workspace.license.self-host-team.free.description'
    ]({
      memberCount: workspaceQuota?.humanReadable.memberLimit || '10',
    });
  }, [isOneTimePurchase, isTeam, license, t, workspaceQuota]);
  const handleClick = useCallback(() => {
    if (isLocalWorkspace) {
      confirmEnableCloud(workspace);
      return;
    }
    setOpenModal(true);
  }, [confirmEnableCloud, isLocalWorkspace, workspace]);

  const handleOpenUploadModal = useCallback(() => {
    setOpenUploadModal(true);
  }, []);

  const onActivate = useCallback(
    (license: string) => {
      setLoading(true);
      selfhostLicenseService
        .activateLicense(workspace.id, license)
        .then(() => {
          setLoading(false);
          setOpenModal(false);
          revalidate();
          notify.success({
            title:
              t['com.cloud-notes.settings.workspace.license.activate-success'](),
          });
        })
        .catch(e => {
          setLoading(false);

          console.error(e);
          const error = UserFriendlyError.fromAny(e);

          notify.error({
            title: error.name,
            message: error.message,
          });
        });
    },
    [revalidate, selfhostLicenseService, t, workspace.id]
  );

  const onDeactivate = useCallback(() => {
    setLoading(true);
    selfhostLicenseService
      .deactivateLicense(workspace.id)
      .then(() => {
        setLoading(false);
        setOpenModal(false);
        revalidate();
        notify.success({
          title:
            t['com.cloud-notes.settings.workspace.license.deactivate-success'](),
        });
      })
      .catch(e => {
        setLoading(false);

        console.error(e);
        const error = UserFriendlyError.fromAny(e);

        notify.error({
          title: error.name,
          message: error.message,
        });
      });
  }, [revalidate, selfhostLicenseService, t, workspace.id]);

  const handleConfirm = useCallback(
    (license: string) => {
      if (hasLicense) {
        onDeactivate();
      } else {
        onActivate(license);
      }
    },
    [hasLicense, onActivate, onDeactivate]
  );

  return (
    <>
      <div className={styles.planCard}>
        <div className={styles.container}>
          <div className={styles.currentPlan}>
            <SettingRow
              spreadCol={false}
              name={t[
                `com.cloud-notes.settings.workspace.license.self-host${isTeam ? '-team' : ''}`
              ]()}
              desc={description}
            />
          </div>
          <div
            className={clsx(styles.planPrice, {
              hidden: isLocalWorkspace,
            })}
          >
            <span className={styles.seat}>
              {t[
                'com.cloud-notes.settings.workspace.license.self-host-team.seats'
              ]()}
            </span>
            <span>
              {isTeam && !isOneTimePurchase
                ? license?.quantity || ''
                : `${workspaceQuota?.memberCount}/${workspaceQuota?.memberLimit}`}
            </span>
          </div>
        </div>
        <div
          className={clsx(styles.buttonContainer, {
            left: hasLicense || isLocalWorkspace,
          })}
        >
          {!hasLicense && !isLocalWorkspace ? (
            <Button
              variant="plain"
              className={styles.uploadButton}
              onClick={handleOpenUploadModal}
            >
              {t[
                'com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file'
              ]()}
            </Button>
          ) : null}
          <Button
            variant="primary"
            className={styles.activeButton}
            onClick={handleClick}
          >
            {t[
              `com.cloud-notes.settings.workspace.license.self-host-team.${hasLicense ? 'deactivate-license' : 'use-purchased-key'}`
            ]()}
          </Button>
        </div>
      </div>
      <ActionModal
        open={openModal}
        onOpenChange={setOpenModal}
        hasLicense={hasLicense}
        loading={loading}
        onConfirm={handleConfirm}
        isOneTimePurchase={isOneTimePurchase}
      />
      <UploadLicenseModal
        open={openUploadModal}
        onOpenChange={setOpenUploadModal}
      />
    </>
  );
};

const ActionModal = ({
  open,
  onOpenChange,
  hasLicense,
  onConfirm,
  loading,
  isOneTimePurchase,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  hasLicense: boolean;
  loading: boolean;
  isOneTimePurchase: boolean;
  onConfirm: (key: string) => void;
}) => {
  const t = useI18n();
  const [key, setKey] = useState('');

  const workspace = useService(WorkspaceService).workspace;

  const { isMutating, trigger } = useMutation({
    mutation: createSelfhostCustomerPortalMutation,
  });
  const urlService = useService(UrlService);

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

  const handleConfirm = useCallback(() => {
    onConfirm(key);
    setKey('');
  }, [key, onConfirm]);

  const handleOpenChange = useCallback(
    (open: boolean) => {
      setKey('');
      onOpenChange(open);
    },
    [onOpenChange]
  );

  const handleCancel = useCallback(() => {
    setKey('');
    onOpenChange(false);
  }, [onOpenChange]);

  if (hasLicense && isOneTimePurchase) {
    return (
      <ConfirmModal
        width={480}
        open={open}
        onOpenChange={handleOpenChange}
        title={t[
          `com.cloud-notes.settings.workspace.license.deactivate-modal.title`
        ]()}
        description={t[
          'com.cloud-notes.settings.workspace.license.deactivate-modal.description-license'
        ]()}
        cancelText={t['Cancel']()}
        cancelButtonOptions={{
          variant: 'secondary',
        }}
        confirmText={t['Confirm']()}
        confirmButtonOptions={{
          loading: loading,
          disabled: loading,
          variant: 'primary',
        }}
        onConfirm={handleConfirm}
        childrenContentClassName={styles.activateModalContent}
      />
    );
  }

  if (hasLicense) {
    return (
      <Modal
        width={480}
        open={open}
        onOpenChange={handleOpenChange}
        title={t[
          `com.cloud-notes.settings.workspace.license.deactivate-modal.title`
        ]()}
        description={
          <Trans
            i18nKey="com.cloud-notes.settings.workspace.license.deactivate-modal.description"
            components={{
              1: <strong />,
            }}
          />
        }
      >
        <div className={styles.footer}>
          <Button
            variant="secondary"
            onClick={update}
            loading={isMutating}
            disabled={isMutating}
          >
            {t[
              'com.cloud-notes.settings.workspace.license.deactivate-modal.manage-payment'
            ]()}
          </Button>
          <div className={styles.rightActions}>
            <Button variant="secondary" onClick={handleCancel}>
              {t['Cancel']()}
            </Button>
            <Button variant="primary" onClick={handleConfirm}>
              {t['Confirm']()}
            </Button>
          </div>
        </div>
      </Modal>
    );
  }

  return (
    <ConfirmModal
      width={480}
      open={open}
      onOpenChange={handleOpenChange}
      title={t['com.cloud-notes.settings.workspace.license.activate-modal.title']()}
      description={t[
        'com.cloud-notes.settings.workspace.license.activate-modal.description'
      ]()}
      cancelText={t['Cancel']()}
      cancelButtonOptions={{
        variant: 'secondary',
      }}
      contentOptions={{
        ['data-testid' as string]: 'license-modal',
        style: {
          padding: '20px 24px',
        },
      }}
      confirmText={t['Confirm']()}
      confirmButtonOptions={{
        loading: loading,
        variant: 'primary',
        disabled: loading || !key,
      }}
      onConfirm={handleConfirm}
      childrenContentClassName={styles.activateModalContent}
    >
      <Input
        value={key}
        onChange={setKey}
        placeholder="AAAA-AAAA-AAAA-AAAA-AAAA"
      />
      <span className={styles.tips}>
        <Trans
          i18nKey="com.cloud-notes.settings.workspace.license.activate-modal.tips"
          components={{
            1: (
              <a
                href="https://notes.diegonmarcos.com/pricing/?type=selfhost#table"
                target="_blank"
                rel="noreferrer"
                style={{ color: cssVarV2('text/link') }}
              />
            ),
          }}
        />
      </span>
    </ConfirmModal>
  );
};
