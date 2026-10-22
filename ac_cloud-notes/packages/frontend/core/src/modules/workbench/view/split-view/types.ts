import type { CloudNotesDNDEntity } from '@cloud-notes/core/types/dnd';

export const allowedSplitViewEntityTypes: Set<CloudNotesDNDEntity['type']> =
  new Set(['doc', 'collection', 'tag']);

export const inferToFromEntity = (entity: CloudNotesDNDEntity) => {
  if (entity.type === 'doc') {
    return `/${entity.id}`;
  } else if (entity.type === 'collection') {
    return `/collection/${entity.id}`;
  } else if (entity.type === 'tag') {
    return `/tag/${entity.id}`;
  }
  return null;
};
