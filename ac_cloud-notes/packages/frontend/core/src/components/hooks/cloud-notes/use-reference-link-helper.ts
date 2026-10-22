import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes/shared/types';
import {
  type DeltaInsert,
  Text,
  type Workspace,
} from '@blocksuite/cloud-notes/store';
import { useCallback } from 'react';

export function useReferenceLinkHelper(docCollection: Workspace) {
  const addReferenceLink = useCallback(
    (pageId: string, referenceId: string) => {
      const page = docCollection?.getDoc(pageId)?.getStore();
      if (!page) {
        return;
      }
      const text = new Text([
        {
          insert: ' ',
          attributes: {
            reference: {
              type: 'Subpage',
              pageId: referenceId,
            },
          },
        },
      ] as DeltaInsert<CloudNotesTextAttributes>[]);
      const [frame] = page.getModelsByFlavour('cloud-notes:note');

      frame && page.addBlock('cloud-notes:paragraph', { text }, frame.id);
    },
    [docCollection]
  );

  return {
    addReferenceLink,
  };
}
