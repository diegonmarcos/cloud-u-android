import { EmptyDocs, EmptyTags } from '@cloud-notes/core/components/cloud-notes/empty';
import { EmptyCollections } from '@cloud-notes/core/components/cloud-notes/empty/collections';
import type { ReactNode } from 'react';

import * as styles from './page-list-empty.css';

export const EmptyPageList = ({
  type,
  heading,
  tagId,
}: {
  type: 'all' | 'trash';
  heading?: ReactNode;
  tagId?: string;
}) => {
  return (
    <div className={styles.pageListEmptyStyle}>
      {heading && <div>{heading}</div>}
      <EmptyDocs
        tagId={tagId}
        type={type}
        className={styles.pageListEmptyBody}
      />
    </div>
  );
};

export const EmptyCollectionList = ({ heading }: { heading: ReactNode }) => {
  return (
    <div className={styles.pageListEmptyStyle}>
      {heading && <div>{heading}</div>}
      <EmptyCollections className={styles.pageListEmptyBody} />
    </div>
  );
};

export const EmptyTagList = ({ heading }: { heading: ReactNode }) => {
  return (
    <div className={styles.pageListEmptyStyle}>
      {heading && <div>{heading}</div>}
      <EmptyTags className={styles.pageListEmptyBody} />
    </div>
  );
};
