import type { Store, Workspace } from '@blocksuite/cloud-notes/store';
import { useMemo } from 'react';

export function useDocCollectionHelper(docCollection: Workspace) {
  return useMemo(
    () => ({
      createDoc: (pageId?: string): Store => {
        return docCollection.createDoc(pageId).getStore();
      },
    }),
    [docCollection]
  );
}
