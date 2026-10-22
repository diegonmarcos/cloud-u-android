import { useI18n } from '@cloud-notes/i18n';

import { Button } from '../../ui/button';
import { AuthPageContainer } from '../auth-components';

export const ExpiredPage = ({ onOpenCloudNotes }: { onOpenCloudNotes: () => void }) => {
  const t = useI18n();
  return (
    <AuthPageContainer
      title={t['com.cloud-notes.expired.page.title']()}
      subtitle={t['com.cloud-notes.expired.page.new-subtitle']()}
    >
      <Button variant="primary" size="large" onClick={onOpenCloudNotes}>
        {t['com.cloud-notes.auth.open.cloud-notes']()}
      </Button>
    </AuthPageContainer>
  );
};
