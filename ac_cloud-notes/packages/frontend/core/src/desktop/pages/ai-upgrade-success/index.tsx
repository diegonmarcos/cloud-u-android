import { Button } from '@cloud-notes/component';
import { AuthPageContainer } from '@cloud-notes/component/auth-components';
import { useNavigateHelper } from '@cloud-notes/core/components/hooks/use-navigate-helper';
import { Trans, useI18n } from '@cloud-notes/i18n';
import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

import * as styles from './styles.css';

/**
 * /ai-upgrade-success page
 *
 * only on web
 */
export const Component = () => {
  const t = useI18n();
  const [params] = useSearchParams();

  const { jumpToIndex, jumpToOpenInApp } = useNavigateHelper();
  const openCloudNotes = useCallback(() => {
    if (params.get('client')) {
      return jumpToOpenInApp('bring-to-front');
    } else {
      jumpToIndex();
    }
  }, [jumpToIndex, jumpToOpenInApp, params]);

  const subtitle = (
    <div className={styles.leftContentText}>
      {t['com.cloud-notes.payment.ai-upgrade-success-page.text']()}
      <div>
        <Trans
          i18nKey={'com.cloud-notes.payment.upgrade-success-page.support'}
          components={{
            1: (
              <a
                href="mailto:support@toeverything.info"
                className={styles.mail}
              />
            ),
          }}
        />
      </div>
    </div>
  );

  return (
    <AuthPageContainer
      title={t['com.cloud-notes.payment.ai-upgrade-success-page.title']()}
      subtitle={subtitle}
    >
      <Button variant="primary" size="extraLarge" onClick={openCloudNotes}>
        {t['com.cloud-notes.other-page.nav.open-cloud-notes']()}
      </Button>
    </AuthPageContainer>
  );
};
