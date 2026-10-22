import { Divider, Skeleton } from '@cloud-notes/component';
import { Button } from '@cloud-notes/component/ui/button';
import { useGuard } from '@cloud-notes/core/components/guard';
import { ServerService } from '@cloud-notes/core/modules/cloud';
import { DocService } from '@cloud-notes/core/modules/doc';
import { ShareInfoService } from '@cloud-notes/core/modules/share-doc';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { Suspense, useEffect } from 'react';
import { ErrorBoundary } from 'react-error-boundary';

import { CloudSvg } from '../cloud-svg';
import { CopyLinkButton } from './copy-link-button';
import { MembersPermission, PublicDoc } from './general-access';
import * as styles from './index.css';
import { InviteInput } from './invite-member-editor';
import { MembersRow } from './member-management';
import type { ShareMenuProps } from './share-menu';

export const LocalSharePage = (props: ShareMenuProps) => {
  const t = useI18n();
  const {
    workspaceMetadata: { id: workspaceId },
  } = props;
  return (
    <>
      <div className={styles.localSharePage}>
        <div className={styles.columnContainerStyle} style={{ gap: '12px' }}>
          <div
            className={styles.descriptionStyle}
            style={{ maxWidth: '230px' }}
          >
            {t['com.cloud-notes.share-menu.EnableCloudDescription']()}
          </div>
          <div>
            <Button
              onClick={props.onEnableCloudNotesCloud}
              variant="primary"
              data-testid="share-menu-enable-cloud-notes-cloud-button"
            >
              {t['Enable Cloud Notes']()}
            </Button>
          </div>
        </div>
        <div className={styles.cloudSvgContainer}>
          <CloudSvg />
        </div>
      </div>
      <CopyLinkButton workspaceId={workspaceId} secondary />
    </>
  );
};

export const CloudNotesSharePage = (
  props: ShareMenuProps & {
    onClickInvite: () => void;
    onClickMembers: () => void;
  }
) => {
  const t = useI18n();
  const {
    workspaceMetadata: { id: workspaceId },
  } = props;
  const shareInfoService = useService(ShareInfoService);
  const serverService = useService(ServerService);
  const docService = useService(DocService);

  const canManageUsers = useGuard('Doc_Users_Manage', docService.doc.id);

  const canPublish = useGuard('Doc_Publish', docService.doc.id);

  useEffect(() => {
    shareInfoService.shareInfo.revalidate();
  }, [shareInfoService]);

  const isSharedPage = useLiveData(shareInfoService.shareInfo.isShared$);
  const sharedMode = useLiveData(shareInfoService.shareInfo.sharedMode$);
  const baseUrl = serverService.server.baseUrl;
  const isLoading =
    isSharedPage === null || sharedMode === null || baseUrl === null;

  if (isLoading) {
    // TODO(@eyhn): loading and error UI
    return (
      <>
        <Skeleton height={100} />
        <Skeleton height={40} />
      </>
    );
  }

  return (
    <div className={styles.content}>
      <div className={styles.columnContainerStyle}>
        <div className={styles.memberRowsStyle}>
          {canManageUsers && <InviteInput onFocus={props.onClickInvite} />}
          <MembersRow onClick={props.onClickMembers} />
        </div>

        <div className={styles.generalAccessStyle}>
          {t['com.cloud-notes.share-menu.generalAccess']()}
        </div>
        <MembersPermission
          openPaywallModal={props.openPaywallModal}
          hittingPaywall={!!props.hittingPaywall}
          disabled={!canManageUsers}
        />
        <PublicDoc disabled={!canPublish} />
      </div>
      <Divider className={styles.divider} />
      <CopyLinkButton workspaceId={workspaceId} />
    </div>
  );
};

export const SharePage = (
  props: ShareMenuProps & {
    onClickInvite: () => void;
    onClickMembers: () => void;
  }
) => {
  if (props.workspaceMetadata.flavour === 'local') {
    return <LocalSharePage {...props} />;
  } else {
    return (
      // TODO(@eyhn): refactor this part
      <ErrorBoundary fallback={null}>
        <Suspense>
          <CloudNotesSharePage {...props} />
        </Suspense>
      </ErrorBoundary>
    );
  }
};
