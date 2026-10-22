import { useI18n } from '@cloud-notes/i18n';
import type { FC } from 'react';

import { Button } from '../../ui/button';
import { AuthPageContainer } from './auth-page-container';

export const SignInSuccessPage: FC<{
  onOpenCloudNotes: () => void;
}> = ({ onOpenCloudNotes }) => {
  const t = useI18n();
  return (
    <AuthPageContainer
      title={t['com.cloud-notes.auth.signed.success.title']()}
      subtitle={t['com.cloud-notes.auth.signed.success.subtitle']()}
    >
      <Button variant="primary" size="large" onClick={onOpenCloudNotes}>
        {t['com.cloud-notes.auth.open.cloud-notes']()}
      </Button>
    </AuthPageContainer>
  );
};
