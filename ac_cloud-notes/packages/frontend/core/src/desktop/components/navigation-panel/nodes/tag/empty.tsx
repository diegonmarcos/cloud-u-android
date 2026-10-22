import { type DropTargetDropEvent, useDropTarget } from '@cloud-notes/component';
import type { CloudNotesDNDData } from '@cloud-notes/core/types/dnd';
import { useI18n } from '@cloud-notes/i18n';

import { EmptyNodeChildren } from '../../layouts/empty-node-children';

export const Empty = ({
  onDrop,
}: {
  onDrop: (data: DropTargetDropEvent<CloudNotesDNDData>) => void;
}) => {
  const { dropTargetRef } = useDropTarget(
    () => ({
      onDrop,
    }),
    [onDrop]
  );
  const t = useI18n();
  return (
    <EmptyNodeChildren ref={dropTargetRef}>
      {t['com.cloud-notes.rootAppSidebar.tags.no-doc']()}
    </EmptyNodeChildren>
  );
};
