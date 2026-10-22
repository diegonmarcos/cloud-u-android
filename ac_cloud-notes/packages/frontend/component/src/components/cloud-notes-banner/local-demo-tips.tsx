import { Button, IconButton } from '@cloud-notes/component/ui/button';
import { useI18n } from '@cloud-notes/i18n';
import { CloseIcon } from '@blocksuite/icons/rc';
import { cssVar } from '@toeverything/theme';
import { useCallback } from 'react';

import * as styles from './index.css';

type LocalDemoTipsProps = {
  isLoggedIn: boolean;
  onLogin: () => void;
  onEnableCloud: () => void;
  onClose: () => void;
};

export const LocalDemoTips = ({
  onClose,
  isLoggedIn,
  onLogin,
  onEnableCloud,
}: LocalDemoTipsProps) => {
  const t = useI18n();
  const buttonLabel = isLoggedIn
    ? t['Enable Cloud Notes']()
    : t['Sign in and Enable']();

  const handleClick = useCallback(() => {
    if (isLoggedIn) {
      return onEnableCloud();
    }
    return onLogin();
  }, [isLoggedIn, onEnableCloud, onLogin]);

  return (
    <div className={styles.tipsContainer} data-testid="local-demo-tips">
      <div className={styles.tipsMessage}>
        {t['com.cloud-notes.banner.local-warning']()}
      </div>

      <div className={styles.tipsRightItem}>
        <Button style={{ background: cssVar('white') }} onClick={handleClick}>
          {buttonLabel}
        </Button>
        <IconButton
          onClick={onClose}
          size="20"
          data-testid="local-demo-tips-close-button"
        >
          <CloseIcon />
        </IconButton>
      </div>
    </div>
  );
};

export default LocalDemoTips;
