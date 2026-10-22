import { Button } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';

import * as styles from './collection-list-header.css';

export const CollectionListHeader = ({
  onCreate,
}: {
  onCreate: () => void;
}) => {
  const t = useI18n();

  return (
    <div className={styles.collectionListHeader}>
      <div className={styles.collectionListHeaderTitle}>
        {t['com.cloud-notes.collections.header']()}
      </div>
      <Button
        className={styles.newCollectionButton}
        onClick={onCreate}
        data-testid="all-collection-new-button"
      >
        {t['com.cloud-notes.collections.empty.new-collection-button']()}
      </Button>
    </div>
  );
};
