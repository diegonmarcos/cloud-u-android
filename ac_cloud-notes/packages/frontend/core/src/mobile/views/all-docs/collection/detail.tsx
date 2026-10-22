import { Wrapper } from '@cloud-notes/component';
import {
  EmptyCollectionDetail,
  EmptyDocs,
} from '@cloud-notes/core/components/cloud-notes/empty';
import {
  createDocExplorerContext,
  DocExplorerContext,
} from '@cloud-notes/core/components/explorer/context';
import { DocsExplorer } from '@cloud-notes/core/components/explorer/docs-view/docs-list';
import { PageHeader } from '@cloud-notes/core/mobile/components';
import { Page } from '@cloud-notes/core/mobile/components/page';
import type { Collection } from '@cloud-notes/core/modules/collection';
import { ViewLayersIcon } from '@blocksuite/icons/rc';
import { useLiveData } from '@toeverything/infra';
import { useEffect, useState } from 'react';

import * as styles from './detail.css';

export const DetailHeader = ({ collection }: { collection: Collection }) => {
  const name = useLiveData(collection.name$);
  return (
    <PageHeader className={styles.header} back>
      <div className={styles.headerContent}>
        <ViewLayersIcon className={styles.headerIcon} />
        {name}
      </div>
    </PageHeader>
  );
};

const CollectionDocs = ({ collection }: { collection: Collection }) => {
  const [explorerContextValue] = useState(() =>
    createDocExplorerContext({
      quickFavorite: true,
      displayProperties: ['createdAt', 'updatedAt', 'tags'],
      view: 'masonry',
      showDragHandle: false,
      groupBy: undefined,
      orderBy: undefined,
    })
  );
  const groups = useLiveData(explorerContextValue.groups$);
  const isEmpty =
    groups.length === 0 ||
    (groups.length && groups.every(group => !group.items.length));

  useEffect(() => {
    const subscription = collection.watch().subscribe({
      next: result => {
        explorerContextValue.groups$.next([
          {
            key: 'collection',
            items: result,
          },
        ]);
      },
      error: console.error,
    });
    return () => subscription.unsubscribe();
  }, [collection, explorerContextValue.groups$]);

  if (isEmpty) {
    return (
      <>
        <EmptyDocs absoluteCenter />
        <Wrapper height={0} flexGrow={1} />
      </>
    );
  }

  return (
    <DocExplorerContext.Provider value={explorerContextValue}>
      <DocsExplorer masonryItemWidthMin={150} />
    </DocExplorerContext.Provider>
  );
};

export const CollectionDetail = ({
  collection,
}: {
  collection: Collection;
}) => {
  const info = useLiveData(collection.info$);
  if (info.allowList.length === 0 && info.rules.filters.length === 0) {
    return (
      <Page header={<DetailHeader collection={collection} />}>
        <div style={{ flexGrow: 1 }}>
          <EmptyCollectionDetail collection={collection} absoluteCenter />
        </div>
      </Page>
    );
  }

  return (
    <Page header={<DetailHeader collection={collection} />}>
      <CollectionDocs collection={collection} />
    </Page>
  );
};
