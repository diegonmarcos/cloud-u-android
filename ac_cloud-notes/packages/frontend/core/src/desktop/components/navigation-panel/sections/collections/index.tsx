import { IconButton, usePromptModal } from '@cloud-notes/component';
import { CollectionService } from '@cloud-notes/core/modules/collection';
import { NavigationPanelService } from '@cloud-notes/core/modules/navigation-panel';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { AddCollectionIcon } from '@blocksuite/icons/rc';
import { useLiveData, useServices } from '@toeverything/infra';
import { useCallback, useMemo } from 'react';

import { CollapsibleSection } from '../../layouts/collapsible-section';
import { NavigationPanelCollectionNode } from '../../nodes/collection';
import { NavigationPanelTreeRoot } from '../../tree';
import { RootEmpty } from './empty';
import * as styles from './index.css';

export const NavigationPanelCollections = () => {
  const t = useI18n();
  const { collectionService, workbenchService, navigationPanelService } =
    useServices({
      CollectionService,
      WorkbenchService,
      NavigationPanelService,
    });
  const collections = useLiveData(collectionService.collections$);
  const { openPromptModal } = usePromptModal();
  const path = useMemo(() => ['collections'], []);
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
        track.$.navigationPanel.organize.createOrganizeItem({
          type: 'collection',
        });
        workbenchService.workbench.openCollection(id);
        navigationPanelService.setCollapsed(path, false);
      },
    });
  }, [
    collectionService,
    navigationPanelService,
    openPromptModal,
    path,
    t,
    workbenchService.workbench,
  ]);

  return (
    <CollapsibleSection
      path={path}
      testId="navigation-panel-collections"
      title={t['com.cloud-notes.rootAppSidebar.collections']()}
      actions={
        <IconButton
          data-testid="navigation-panel-bar-add-collection-button"
          onClick={handleCreateCollection}
          size="16"
          tooltip={t[
            'com.cloud-notes.rootAppSidebar.explorer.collection-section-add-tooltip'
          ]()}
        >
          <AddCollectionIcon />
        </IconButton>
      }
    >
      <NavigationPanelTreeRoot
        placeholder={<RootEmpty onClickCreate={handleCreateCollection} />}
      >
        {Array.from(collections.values()).map(collection => (
          <NavigationPanelCollectionNode
            key={collection.id}
            collectionId={collection.id}
            reorderable={false}
            location={{
              at: 'navigation-panel:collection:list',
            }}
            parentPath={path}
          />
        ))}
      </NavigationPanelTreeRoot>
    </CollapsibleSection>
  );
};
