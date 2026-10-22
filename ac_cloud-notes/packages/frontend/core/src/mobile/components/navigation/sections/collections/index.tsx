import { NavigationPanelTreeRoot } from '@cloud-notes/core/desktop/components/navigation-panel';
import { CollectionService } from '@cloud-notes/core/modules/collection';
import { NavigationPanelService } from '@cloud-notes/core/modules/navigation-panel';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { AddCollectionIcon } from '@blocksuite/icons/rc';
import { useLiveData, useServices } from '@toeverything/infra';
import { useCallback, useMemo, useState } from 'react';

import { AddItemPlaceholder } from '../../layouts/add-item-placeholder';
import { CollapsibleSection } from '../../layouts/collapsible-section';
import { NavigationPanelCollectionNode } from '../../nodes/collection';
import { CollectionRenameDialog } from '../../nodes/collection/dialog';
import * as styles from './index.css';

export const NavigationPanelCollections = () => {
  const t = useI18n();
  const { collectionService, workbenchService, navigationPanelService } =
    useServices({
      CollectionService,
      WorkbenchService,
      NavigationPanelService,
    });
  const path = useMemo(() => ['collections'], []);
  const collectionMetas = useLiveData(collectionService.collectionMetas$);
  const [showNewCollectionDialog, setShowNewCollectionDialog] = useState(false);

  const handleCreateCollection = useCallback(
    (name: string) => {
      const id = collectionService.createCollection({ name });
      track.$.navigationPanel.organize.createOrganizeItem({
        type: 'collection',
      });
      workbenchService.workbench.openCollection(id);
      navigationPanelService.setCollapsed(path, false);
    },
    [
      collectionService,
      navigationPanelService,
      path,
      workbenchService.workbench,
    ]
  );

  return (
    <CollapsibleSection
      path={path}
      testId="navigation-panel-collections"
      title={t['com.cloud-notes.rootAppSidebar.collections']()}
    >
      <NavigationPanelTreeRoot>
        {collectionMetas.map(collection => (
          <NavigationPanelCollectionNode
            key={collection.id}
            collectionId={collection.id}
            parentPath={path}
          />
        ))}
        <AddItemPlaceholder
          icon={<AddCollectionIcon />}
          data-testid="navigation-panel-bar-add-collection-button"
          label={t['com.cloud-notes.rootAppSidebar.collection.new']()}
          onClick={() => setShowNewCollectionDialog(true)}
        />
        <CollectionRenameDialog
          open={showNewCollectionDialog}
          onOpenChange={setShowNewCollectionDialog}
          onConfirm={handleCreateCollection}
          title={t['com.cloud-notes.m.explorer.collection.new-dialog-title']()}
          confirmText={t['com.cloud-notes.editCollection.save']()}
          inputProps={{
            placeholder: t['com.cloud-notes.editCollectionName.name.placeholder'](),
          }}
          descRenderer={() => (
            <div className={styles.createTips}>
              {t['com.cloud-notes.editCollectionName.createTips']()}
            </div>
          )}
        />
      </NavigationPanelTreeRoot>
    </CollapsibleSection>
  );
};
