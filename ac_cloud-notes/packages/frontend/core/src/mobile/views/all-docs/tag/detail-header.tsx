import { PageHeader } from '@cloud-notes/core/mobile/components';
import type { Tag } from '@cloud-notes/core/modules/tag';
import { useLiveData } from '@toeverything/infra';

import * as styles from './detail.css';

export const TagDetailHeader = ({ tag }: { tag: Tag }) => {
  const name = useLiveData(tag.value$);
  const color = useLiveData(tag.color$);
  return (
    <PageHeader className={styles.header} back>
      <div className={styles.headerContent}>
        <div className={styles.headerIcon} style={{ color }} />
        {name}
      </div>
    </PageHeader>
  );
};
