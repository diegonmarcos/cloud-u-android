import type { DropTargetOptions } from '@cloud-notes/component';
import { isFavoriteSupportType } from '@cloud-notes/core/modules/favorite';
import type { CloudNotesDNDData } from '@cloud-notes/core/types/dnd';

import type { NavigationPanelTreeNodeDropEffect } from '../../tree';

export const favoriteChildrenDropEffect: NavigationPanelTreeNodeDropEffect =
  data => {
    if (
      data.treeInstruction?.type === 'reorder-above' ||
      data.treeInstruction?.type === 'reorder-below'
    ) {
      if (
        data.source.data.from?.at === 'navigation-panel:favorite:list' &&
        data.source.data.entity?.type &&
        isFavoriteSupportType(data.source.data.entity.type)
      ) {
        return 'move';
      } else if (
        data.source.data.entity?.type &&
        isFavoriteSupportType(data.source.data.entity.type)
      ) {
        return 'link';
      }
    }
    return; // not supported
  };

export const favoriteRootDropEffect: NavigationPanelTreeNodeDropEffect =
  data => {
    const sourceType = data.source.data.entity?.type;
    if (sourceType && isFavoriteSupportType(sourceType)) {
      return 'link';
    }
    return;
  };

export const favoriteRootCanDrop: DropTargetOptions<CloudNotesDNDData>['canDrop'] =
  data => {
    return data.source.data.entity?.type
      ? isFavoriteSupportType(data.source.data.entity.type)
      : false;
  };

export const favoriteChildrenCanDrop: DropTargetOptions<CloudNotesDNDData>['canDrop'] =
  // Same as favoriteRootCanDrop
  data => favoriteRootCanDrop(data);
