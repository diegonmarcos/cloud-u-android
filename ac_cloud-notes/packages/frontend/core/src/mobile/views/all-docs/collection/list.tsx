import { useNavigateHelper } from '@cloud-notes/core/components/hooks/use-navigate-helper';
import { CollectionService } from '@cloud-notes/core/modules/collection';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useState } from 'react';

import { CollectionRenameDialog } from '../../../components/navigation/nodes/collection/dialog';
import { MobileAllDocsEmptyState } from '../empty-state';
import { CollectionListItem } from './item';
import { list } from './styles.css';

export const CollectionList = () => {
  const t = useI18n();
  const collectionService = useService(CollectionService);
  const workspace = useService(WorkspaceService).workspace;
  const collectionMetas = useLiveData(collectionService.collectionMetas$);
  const navigateHelper = useNavigateHelper();
  const [showNewCollectionDialog, setShowNewCollectionDialog] = useState(false);

  const handleCreateCollection = useCallback(
    (name: string) => {
      const id = collectionService.createCollection({ name });
      navigateHelper.jumpToCollection(workspace.id, id);
    },
    [collectionService, navigateHelper, workspace.id]
  );

  if (!collectionMetas.length) {
    return (
      <>
        <MobileAllDocsEmptyState
          type="collections"
          onAction={() => setShowNewCollectionDialog(true)}
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
          descRenderer={() => t['com.cloud-notes.editCollectionName.createTips']()}
        />
      </>
    );
  }

  return (
    <ul className={list}>
      {collectionMetas.map(meta => (
        <CollectionListItem key={meta.id} meta={meta} />
      ))}
    </ul>
  );
};
