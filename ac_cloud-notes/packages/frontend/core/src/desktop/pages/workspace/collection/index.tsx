import { FlexWrapper } from '@cloud-notes/component';
import { EmptyCollectionDetail } from '@cloud-notes/core/components/cloud-notes/empty/collection-detail';
import {
  createDocExplorerContext,
  DocExplorerContext,
} from '@cloud-notes/core/components/explorer/context';
import { DocsExplorer } from '@cloud-notes/core/components/explorer/docs-view/docs-list';
import type { ExplorerDisplayPreference } from '@cloud-notes/core/components/explorer/types';
import {
  type Collection,
  CollectionService,
} from '@cloud-notes/core/modules/collection';
import { CollectionRulesService } from '@cloud-notes/core/modules/collection-rules';
import { GlobalContextService } from '@cloud-notes/core/modules/global-context';
import { WorkspacePermissionService } from '@cloud-notes/core/modules/permissions';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { ViewLayersIcon } from '@blocksuite/icons/rc';
import { useLiveData, useService, useServices } from '@toeverything/infra';
import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

import { useNavigateHelper } from '../../../../components/hooks/use-navigate-helper';
import {
  useIsActiveView,
  ViewBody,
  ViewHeader,
  ViewIcon,
  ViewTitle,
} from '../../../../modules/workbench';
import { PageNotFound } from '../../404';
import { AllDocSidebarTabs } from '../layouts/all-doc-sidebar-tabs';
import { CollectionDetailHeader } from './header';
import * as styles from './index.css';
import { CollectionListHeader } from './list-header';

export const CollectionDetail = ({
  collection,
}: {
  collection: Collection;
}) => {
  const [explorerContextValue] = useState(createDocExplorerContext);
  const collectionRulesService = useService(CollectionRulesService);

  const permissionService = useService(WorkspacePermissionService);
  const isAdmin = useLiveData(permissionService.permission.isAdmin$);
  const isOwner = useLiveData(permissionService.permission.isOwner$);

  const displayPreference = useLiveData(
    explorerContextValue.displayPreference$
  );
  const groupBy = useLiveData(explorerContextValue.groupBy$);
  const orderBy = useLiveData(explorerContextValue.orderBy$);
  const rules = useLiveData(collection.rules$);
  const allowList = useLiveData(collection.allowList$);

  const handleDisplayPreferenceChange = useCallback(
    (displayPreference: ExplorerDisplayPreference) => {
      explorerContextValue.displayPreference$.next(displayPreference);
    },
    [explorerContextValue]
  );

  useEffect(() => {
    const subscription = collectionRulesService
      .watch({
        filters: rules.filters,
        groupBy,
        orderBy,
        extraAllowList: allowList,
        extraFilters: [
          {
            type: 'system',
            key: 'empty-journal',
            method: 'is',
            value: 'false',
          },
          {
            type: 'system',
            key: 'trash',
            method: 'is',
            value: 'false',
          },
        ],
      })
      .subscribe({
        next: result => {
          explorerContextValue.groups$.next(result.groups);
        },
        error: error => {
          console.error(error);
        },
      });
    return () => {
      subscription.unsubscribe();
    };
  }, [
    allowList,
    collectionRulesService,
    explorerContextValue.groups$,
    groupBy,
    orderBy,
    rules.filters,
  ]);

  return (
    <DocExplorerContext.Provider value={explorerContextValue}>
      <ViewHeader>
        <CollectionDetailHeader
          displayPreference={displayPreference}
          onDisplayPreferenceChange={handleDisplayPreferenceChange}
        />
      </ViewHeader>
      <ViewBody>
        <FlexWrapper flexDirection="column" alignItems="stretch" width="100%">
          <CollectionListHeader collection={collection} />
          <div className={styles.scrollArea}>
            <DocsExplorer disableMultiDelete={!isAdmin && !isOwner} />
          </div>
        </FlexWrapper>
      </ViewBody>
    </DocExplorerContext.Provider>
  );
};

export const Component = function CollectionPage() {
  const { collectionService, globalContextService } = useServices({
    CollectionService,
    GlobalContextService,
  });
  const globalContext = globalContextService.globalContext;
  const t = useI18n();
  const params = useParams();
  const collection = useLiveData(
    params.collectionId
      ? collectionService.collection$(params.collectionId)
      : null
  );
  const name = useLiveData(collection?.name$);
  const isActiveView = useIsActiveView();

  useEffect(() => {
    if (isActiveView && collection) {
      globalContext.collectionId.set(collection.id);
      globalContext.isCollection.set(true);

      return () => {
        globalContext.collectionId.set(null);
        globalContext.isCollection.set(false);
      };
    }
    return;
  }, [collection, globalContext, isActiveView]);

  const info = useLiveData(collection?.info$);

  if (!collection) {
    return <PageNotFound />;
  }
  const inner =
    info?.allowList.length === 0 && info?.rules.filters.length === 0 ? (
      <Placeholder collection={collection} />
    ) : (
      <CollectionDetail collection={collection} />
    );

  return (
    <>
      <ViewIcon icon="collection" />
      <ViewTitle title={name ?? t['Untitled']()} />
      <AllDocSidebarTabs />
      {inner}
    </>
  );
};

const Placeholder = ({ collection }: { collection: Collection }) => {
  const workspace = useService(WorkspaceService).workspace;
  const { jumpToCollections } = useNavigateHelper();
  const t = useI18n();
  const name = useLiveData(collection?.name$);

  const handleJumpToCollections = useCallback(() => {
    jumpToCollections(workspace.id);
  }, [jumpToCollections, workspace]);

  return (
    <>
      <ViewHeader>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            fontSize: 'var(--cloud-notes-font-xs)',
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              cursor: 'pointer',
              color: 'var(--cloud-notes-text-secondary-color)',
              ['WebkitAppRegion' as string]: 'no-drag',
            }}
            onClick={handleJumpToCollections}
          >
            <ViewLayersIcon
              style={{ color: 'var(--cloud-notes-icon-color)' }}
              fontSize={14}
            />
            {t['com.cloud-notes.collection.allCollections']()}
            <div>/</div>
          </div>
          <div
            data-testid="collection-name"
            style={{
              fontWeight: 600,
              color: 'var(--cloud-notes-text-primary-color)',
              ['WebkitAppRegion' as string]: 'no-drag',
            }}
          >
            {name ?? t['Untitled']()}
          </div>
          <div style={{ flex: 1 }} />
        </div>
      </ViewHeader>
      <ViewBody>
        <EmptyCollectionDetail
          collection={collection}
          style={{ height: '100%' }}
        />
      </ViewBody>
    </>
  );
};
