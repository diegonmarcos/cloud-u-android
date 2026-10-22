import {
  Avatar,
  Button,
  Divider,
  IconButton,
  Input,
  Menu,
  MenuItem,
  MenuTrigger,
  Modal,
  notify,
  Scrollable,
} from '@cloud-notes/component';
import { AuthPageContainer } from '@cloud-notes/component/auth-components';
import { useSignOut } from '@cloud-notes/core/components/hooks/cloud-notes/use-sign-out';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { useNavigateHelper } from '@cloud-notes/core/components/hooks/use-navigate-helper';
import { useWorkspaceInfo } from '@cloud-notes/core/components/hooks/use-workspace-info';
import { PureWorkspaceCard } from '@cloud-notes/core/components/workspace-selector/workspace-card';
import { AuthService } from '@cloud-notes/core/modules/cloud';
import {
  type WorkspaceMetadata,
  WorkspacesService,
} from '@cloud-notes/core/modules/workspace';
import { buildShowcaseWorkspace } from '@cloud-notes/core/utils/first-app-data';
import { UNTITLED_WORKSPACE_NAME } from '@cloud-notes/env/constant';
import { SubscriptionPlan, SubscriptionRecurring } from '@cloud-notes/graphql';
import { type I18nString, Trans, useI18n } from '@cloud-notes/i18n';
import { DoneIcon, NewPageIcon, SignOutIcon } from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { Upgrade } from '../../dialogs/setting/general-setting/plans/plan-card';
import { PageNotFound } from '../404';
import * as styles from './styles.css';

const benefitList: I18nString[] = [
  'com.cloud-notes.upgrade-to-team-page.benefit.g1',
  'com.cloud-notes.upgrade-to-team-page.benefit.g2',
  'com.cloud-notes.upgrade-to-team-page.benefit.g3',
  'com.cloud-notes.upgrade-to-team-page.benefit.g4',
];

export const Component = () => {
  const authService = useService(AuthService);
  const authStatus = useLiveData(authService.session.status$);

  const [params] = useSearchParams();
  const recurring = params.get('recurring');

  const authIsRevalidating = useLiveData(authService.session.isRevalidating$);
  if (authStatus === 'unauthenticated' && !authIsRevalidating) {
    return <PageNotFound noPermission />;
  }
  return <UpgradeToTeam recurring={recurring} />;
};

export const UpgradeToTeam = ({ recurring }: { recurring: string | null }) => {
  const t = useI18n();

  const workspacesList = useService(WorkspacesService).list;
  const workspaces = useLiveData(workspacesList.workspaces$);
  const [openUpgrade, setOpenUpgrade] = useState(false);
  const [openCreate, setOpenCreate] = useState(false);

  const authService = useService(AuthService);
  const user = useLiveData(authService.session.account$);
  const onSignOut = useSignOut();

  const [selectedWorkspace, setSelectedWorkspace] =
    useState<WorkspaceMetadata | null>(null);

  const workspacesService = useService(WorkspacesService);
  const profile = selectedWorkspace
    ? workspacesService.getProfile(selectedWorkspace)
    : undefined;
  const workspaceInfo = useLiveData(profile?.profile$);
  const name = workspaceInfo?.name ?? UNTITLED_WORKSPACE_NAME;

  const menuTriggerText = useMemo(() => {
    if (selectedWorkspace) {
      return name;
    }
    return t[
      'com.cloud-notes.upgrade-to-team-page.workspace-selector.placeholder'
    ]();
  }, [name, selectedWorkspace, t]);

  const onUpgradeButtonClick = useCallback(() => {
    setOpenUpgrade(true);
  }, []);

  const onClickCreateWorkspace = useCallback(() => {
    setOpenCreate(true);
  }, []);

  const revalidate = useCallback(() => {
    profile?.revalidate();
  }, [profile]);

  const { jumpToPage, jumpToOpenInApp } = useNavigateHelper();
  const [params] = useSearchParams();
  const isTeam = workspaceInfo?.isTeam;

  const openCloudNotes = useCallback(() => {
    if (params.get('client')) {
      jumpToOpenInApp(`/workspace/${selectedWorkspace?.id}/all`);
    } else if (selectedWorkspace) {
      jumpToPage(selectedWorkspace.id, 'all');
    }
  }, [jumpToOpenInApp, jumpToPage, params, selectedWorkspace]);

  useEffect(() => {
    revalidate();
  }, [selectedWorkspace, revalidate]);

  useEffect(() => {
    window.addEventListener('focus', revalidate);
    return () => {
      window.removeEventListener('focus', revalidate);
    };
  }, [revalidate]);

  useEffect(() => {
    if (isTeam && selectedWorkspace) {
      return openCloudNotes();
    }
  }, [isTeam, jumpToPage, openCloudNotes, selectedWorkspace]);

  return (
    <AuthPageContainer title={t['com.cloud-notes.upgrade-to-team-page.title']()}>
      <div className={styles.root}>
        <Menu
          items={
            <WorkspaceSelector
              metas={workspaces}
              onSelect={setSelectedWorkspace}
              onClickCreateWorkspace={onClickCreateWorkspace}
            />
          }
          contentOptions={{
            style: {
              width: '410px',
            },
          }}
        >
          <MenuTrigger
            className={styles.menuTrigger}
            data-selected={!!selectedWorkspace}
          >
            {menuTriggerText}
          </MenuTrigger>
        </Menu>
        <div className={styles.upgradeButton}>
          <Button
            variant="primary"
            size="extraLarge"
            onClick={onUpgradeButtonClick}
            disabled={!selectedWorkspace}
          >
            {t['com.cloud-notes.upgrade-to-team-page.upgrade-button']()}
          </Button>
        </div>
        <div className={styles.contentContainer}>
          <div>{t['com.cloud-notes.upgrade-to-team-page.benefit.title']()}</div>
          <ul>
            {benefitList.map((benefit, index) => (
              <li key={`${benefit}:${index}`} className={styles.liStyle}>
                <DoneIcon className={styles.doneIcon} />
                {t.t(benefit)}
              </li>
            ))}
          </ul>
          <div>
            {t['com.cloud-notes.upgrade-to-team-page.benefit.description']()}
          </div>
          {selectedWorkspace && (
            <UpgradeDialog
              recurring={recurring}
              open={openUpgrade}
              onOpenChange={setOpenUpgrade}
              workspaceId={selectedWorkspace.id}
              workspaceName={name}
            />
          )}
          <CreateWorkspaceDialog
            open={openCreate}
            onOpenChange={setOpenCreate}
            onSelect={setSelectedWorkspace}
          />
        </div>
        {user ? (
          <div className={styles.userContainer}>
            <Avatar url={user.avatar} name={user.label} />
            <span className={styles.email}>{user.email}</span>
            <IconButton
              onClick={onSignOut}
              size="20"
              tooltip={t['404.signOut']()}
            >
              <SignOutIcon />
            </IconButton>
          </div>
        ) : null}
      </div>
    </AuthPageContainer>
  );
};

const UpgradeDialog = ({
  open,
  onOpenChange,
  workspaceId,
  workspaceName,
  recurring,
}: {
  open: boolean;
  workspaceId: string;
  workspaceName: string;
  recurring: string | null;
  onOpenChange: (open: boolean) => void;
}) => {
  const t = useI18n();

  const onClose = useCallback(() => {
    onOpenChange(false);
  }, [onOpenChange]);

  const currentRecurring =
    recurring &&
    recurring.toLowerCase() === SubscriptionRecurring.Yearly.toLowerCase()
      ? SubscriptionRecurring.Yearly
      : SubscriptionRecurring.Monthly;

  return (
    <Modal width={480} open={open} onOpenChange={onOpenChange}>
      <div className={styles.dialogTitle}>
        {t['com.cloud-notes.upgrade-to-team-page.upgrade-confirm.title']()}
      </div>
      <div className={styles.dialogMessage}>
        <Trans
          i18nKey="com.cloud-notes.upgrade-to-team-page.upgrade-confirm.description"
          components={{
            1: <span style={{ fontWeight: 600 }} />,
          }}
          values={{
            workspaceName,
          }}
        />
      </div>

      <div className={styles.dialogFooter}>
        <Button onClick={onClose}>{t['Cancel']()}</Button>
        <Upgrade
          className={styles.upgradeButtonInDialog}
          recurring={currentRecurring}
          plan={SubscriptionPlan.Team}
          workspaceId={workspaceId}
          onCheckoutSuccess={onClose}
          checkoutInput={{
            args: {
              workspaceId,
            },
          }}
        >
          {t['com.cloud-notes.payment.upgrade']()}
        </Upgrade>
      </div>
    </Modal>
  );
};
const WorkspaceSelector = ({
  metas,
  onSelect,
  onClickCreateWorkspace,
}: {
  metas: WorkspaceMetadata[];
  onClickCreateWorkspace: () => void;
  onSelect: (meta: WorkspaceMetadata) => void;
}) => {
  const t = useI18n();

  const cloudWorkspaces = useMemo(
    () =>
      metas.filter(
        ({ flavour }) => flavour === 'cloud-notes-cloud'
      ) as WorkspaceMetadata[],
    [metas]
  );

  const handleSelect = useCallback(
    (workspace: WorkspaceMetadata) => {
      onSelect(workspace);
    },
    [onSelect]
  );

  return (
    <div>
      {cloudWorkspaces.length > 0 ? (
        <Scrollable.Root>
          <Scrollable.Viewport style={{ maxHeight: '40vh' }}>
            {cloudWorkspaces.map(workspace => (
              <WorkspaceItem
                key={workspace.id}
                meta={workspace}
                onSelect={handleSelect}
              />
            ))}
          </Scrollable.Viewport>
          <Scrollable.Scrollbar />
        </Scrollable.Root>
      ) : (
        <div className={styles.noWorkspaceItem}>
          {t['com.cloud-notes.upgrade-to-team-page.no-workspace-available']()}
        </div>
      )}
      <Divider size="thinner" />

      <MenuItem
        className={styles.createWorkspaceItem}
        prefix={<NewPageIcon className={styles.itemIcon} fontSize={28} />}
        onClick={onClickCreateWorkspace}
      >
        <div className={styles.itemContent}>
          {t[
            'com.cloud-notes.upgrade-to-team-page.workspace-selector.create-workspace'
          ]()}
        </div>
      </MenuItem>
    </div>
  );
};

const WorkspaceItem = ({
  meta,
  onSelect,
}: {
  meta: WorkspaceMetadata;
  onSelect: (meta: WorkspaceMetadata) => void;
}) => {
  const information = useWorkspaceInfo(meta);

  const onClick = useCallback(() => {
    onSelect(meta);
  }, [onSelect, meta]);
  if (information?.isTeam || !information?.isOwner) {
    return null;
  }

  return (
    <MenuItem className={styles.plainMenuItem} onClick={onClick}>
      <PureWorkspaceCard
        className={styles.workspaceItem}
        workspaceMetadata={meta}
        avatarSize={28}
      />
    </MenuItem>
  );
};

const CreateWorkspaceDialog = ({
  open,
  onOpenChange,
  onSelect,
}: {
  open: boolean;
  onSelect: (workspace: WorkspaceMetadata) => void;
  onOpenChange: (open: boolean) => void;
}) => {
  const t = useI18n();
  const onClose = useCallback(() => {
    onOpenChange(false);
  }, [onOpenChange]);
  const [name, setName] = useState('');
  const workspacesService = useService(WorkspacesService);

  const onCreate = useCallback(async () => {
    const newWorkspace = await buildShowcaseWorkspace(
      workspacesService,
      'cloud-notes-cloud',
      name
    );
    notify.success({
      title: 'Workspace Created',
    });
    onSelect(newWorkspace.meta);
    onOpenChange(false);
  }, [name, onOpenChange, onSelect, workspacesService]);

  const onBeforeCheckout = useAsyncCallback(async () => {
    await onCreate();
  }, [onCreate]);

  return (
    <Modal width={480} open={open} onOpenChange={onOpenChange}>
      <div className={styles.dialogTitle}>
        {t[
          'com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.title'
        ]()}
      </div>

      <div className={styles.createConfirmContent}>
        <div>
          {t[
            'com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.description'
          ]()}
        </div>
        <Input
          placeholder={t[
            'com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.placeholder'
          ]()}
          value={name}
          onChange={setName}
        />
      </div>

      <div className={styles.dialogFooter}>
        <Button onClick={onClose}>{t['Cancel']()}</Button>
        <Button
          variant="primary"
          className={styles.upgradeButtonInDialog}
          onClick={onBeforeCheckout}
        >
          {t[
            'com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.confirm'
          ]()}
        </Button>
      </div>
    </Modal>
  );
};
