import { Button } from '@cloud-notes/component';
import { AuthPageContainer } from '@cloud-notes/component/auth-components';
import { useNavigateHelper } from '@cloud-notes/core/components/hooks/use-navigate-helper';
import { GraphQLService } from '@cloud-notes/core/modules/cloud';
import { UserFriendlyError } from '@cloud-notes/error';
import { changeEmailMutation } from '@cloud-notes/graphql';
import { useI18n } from '@cloud-notes/i18n';
import { useService } from '@toeverything/infra';
import { type FC, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { AppContainer } from '../../components/app-container';

export const ConfirmChangeEmail: FC<{
  onOpenCloudNotes: () => void;
}> = ({ onOpenCloudNotes }) => {
  const t = useI18n();
  const [searchParams] = useSearchParams();
  const navigateHelper = useNavigateHelper();
  const graphqlService = useService(GraphQLService);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    (async () => {
      const token = searchParams.get('token') ?? '';
      const email = decodeURIComponent(searchParams.get('email') ?? '');
      setIsLoading(true);
      await graphqlService
        .gql({
          query: changeEmailMutation,
          variables: {
            token: token,
            email: email,
          },
        })
        .catch(err => {
          if (UserFriendlyError.fromAny(err).is('INVALID_EMAIL_TOKEN')) {
            return navigateHelper.jumpToExpired();
          }
          throw err;
        })
        .finally(() => {
          setIsLoading(false);
        });
    })().catch(err => {
      // TODO(@eyhn): Add error handling
      console.error(err);
    });
  }, [graphqlService, navigateHelper, searchParams]);

  if (isLoading) {
    return <AppContainer fallback />;
  }

  return (
    <AuthPageContainer
      title={t['com.cloud-notes.auth.change.email.page.success.title']()}
      subtitle={t['com.cloud-notes.auth.change.email.page.success.subtitle']()}
    >
      <Button variant="primary" size="large" onClick={onOpenCloudNotes}>
        {t['com.cloud-notes.auth.open.cloud-notes']()}
      </Button>
    </AuthPageContainer>
  );
};
