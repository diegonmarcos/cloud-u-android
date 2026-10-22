import { usePromptModal } from '@cloud-notes/component';
import { useNavigateHelper } from '@cloud-notes/core/components/hooks/use-navigate-helper';
import {
  CollectionListHeader,
  VirtualizedCollectionList,
} from '@cloud-notes/core/components/page-list';
import {
  ViewIcon,
  ViewTitle,
} from '@cloud-notes/core/modules/workbench/view/view-meta';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useState } from 'react';

import { CollectionService } from '../../../../modules/collection';
import { ViewBody, ViewHeader } from '../../../../modules/workbench';
import { AllDocSidebarTabs } from '../layouts/all-doc-sidebar-tabs';
import { EmptyCollectionList } from '../page-list-empty';
import { AllCollectionHeader } from './header';
import * as styles from './index.css';

export const AllCollection = () => {
  const t = useI18n();
  const currentWorkspace = useService(WorkspaceService).workspace;
  const [hideHeaderCreateNew, setHideHeaderCreateNew] = useState(true);

  const collectionService = useService(CollectionService);
  const collections = useLiveData(collectionService.collections$);

  const navigateHelper = useNavigateHelper();
  const { openPromptModal } = usePromptModal();

  const handleCreateCollection = useCallback(() => {
    openPromptModal({
      title: t['com.cloud-notes.editCollection.saveCollection'](),
      label: t['com.cloud-notes.editCollectionName.name'](),
      inputOptions: {
        placeholder: t['com.cloud-notes.editCollectionName.name.placeholder'](),
      },
      children: (
        <div className={styles.createTips}>
          {t['com.cloud-notes.editCollectionName.createTips']()}
        </div>
      ),
      confirmText: t['com.cloud-notes.editCollection.save'](),
      cancelText: t['com.cloud-notes.editCollection.button.cancel'](),
      confirmButtonOptions: {
        variant: 'primary',
      },
      onConfirm(name) {
        const id = collectionService.createCollection({ name });
        navigateHelper.jumpToCollection(currentWorkspace.id, id);
      },
    });
  }, [
    collectionService,
    currentWorkspace.id,
    navigateHelper,
    openPromptModal,
    t,
  ]);

  return (
    <>
      <ViewTitle title={t['Collections']()} />
      <ViewIcon icon="collection" />
      <ViewHeader>
        <AllCollectionHeader
          showCreateNew={!hideHeaderCreateNew}
          onCreateCollection={handleCreateCollection}
        />
      </ViewHeader>
      <ViewBody>
        <div className={styles.body}>
          {collections.size > 0 ? (
            <VirtualizedCollectionList
              setHideHeaderCreateNewCollection={setHideHeaderCreateNew}
              handleCreateCollection={handleCreateCollection}
            />
          ) : (
            <EmptyCollectionList
              heading={
                <CollectionListHeader onCreate={handleCreateCollection} />
              }
            />
          )}
        </div>
      </ViewBody>
      <AllDocSidebarTabs />
    </>
  );
};

export const Component = () => {
  return <AllCollection />;
};
