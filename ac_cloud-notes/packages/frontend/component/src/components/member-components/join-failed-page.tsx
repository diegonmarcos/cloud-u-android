import { AuthPageContainer } from '@cloud-notes/component/auth-components';
import { UserFriendlyError } from '@cloud-notes/error';
import { ErrorNames, type GetInviteInfoQuery } from '@cloud-notes/graphql';
import { Trans, useI18n } from '@cloud-notes/i18n';

import { Avatar } from '../../ui/avatar';
import * as styles from './styles.css';

export const JoinFailedPage = ({
  inviteInfo,
  error,
}: {
  inviteInfo?: GetInviteInfoQuery['getInviteInfo'];
  error?: any;
}) => {
  const userFriendlyError = UserFriendlyError.fromAny(error);
  const t = useI18n();
  return (
    <AuthPageContainer
      title={t['notes.diegonmarcos.com-to-join-workspace.title']()}
      subtitle={
        userFriendlyError.name === ErrorNames.MEMBER_QUOTA_EXCEEDED ? (
          <div className={styles.lineHeight}>
            <Trans
              i18nKey={'notes.diegonmarcos.com-to-join-workspace.description-1'}
              components={{
                1: (
                  <div className={styles.avatarWrapper}>
                    <Avatar
                      url={`data:image/png;base64,${inviteInfo?.workspace.avatar}`}
                      name={inviteInfo?.workspace.name}
                      size={20}
                      colorfulFallback
                    />
                  </div>
                ),
                2: <span className={styles.inviteName} />,
              }}
              values={{
                workspaceName: inviteInfo?.workspace.name,
              }}
            />
            <div>{t['notes.diegonmarcos.com-to-join-workspace.description-2']()}</div>
          </div>
        ) : (
          <div>
            {t['error.' + userFriendlyError.name]()}
            <br />
            {userFriendlyError.message}
          </div>
        )
      }
    />
  );
};
