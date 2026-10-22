import { useI18n } from '@cloud-notes/i18n';
import { ArrowRightBigIcon } from '@blocksuite/icons/rc';

import * as styles from './share-footer.css';

export const ShareFooter = () => {
  const t = useI18n();
  return (
    <div className={styles.footerContainer}>
      <div className={styles.footer}>
        <div className={styles.description}>
          {t['com.cloud-notes.share-page.footer.description']()}
        </div>
        <a
          className={styles.getStartLink}
          href="https://notes.diegonmarcos.com/"
          target="_blank"
          rel="noreferrer"
        >
          {t['com.cloud-notes.share-page.footer.get-started']()}
          <ArrowRightBigIcon fontSize={16} />
        </a>
      </div>
    </div>
  );
};
