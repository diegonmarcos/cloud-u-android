import { type DropTargetDropEvent, useDropTarget } from '@cloud-notes/component';
import type { CloudNotesDNDData } from '@cloud-notes/core/types/dnd';
import { useI18n } from '@cloud-notes/i18n';

import { EmptyNodeChildren } from '../../layouts/empty-node-children';

export const Empty = ({
  onDrop,
  noAccessible = false,
}: {
  onDrop: (data: DropTargetDropEvent<CloudNotesDNDData>) => void;
  noAccessible?: boolean;
}) => {
  const { dropTargetRef } = useDropTarget<CloudNotesDNDData>(
    () => ({
      onDrop,
    }),
    [onDrop]
  );
  const t = useI18n();

  return (
    <EmptyNodeChildren ref={dropTargetRef}>
      {noAccessible
        ? t['com.cloud-notes.share-menu.option.permission.no-access']()
        : t['com.cloud-notes.rootAppSidebar.docs.no-subdoc']()}
    </EmptyNodeChildren>
  );
};
