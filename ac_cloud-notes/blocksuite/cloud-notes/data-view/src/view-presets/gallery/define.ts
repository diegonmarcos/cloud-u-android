import { viewType } from '../../core/view/data-view.js';
import { GallerySingleView } from './gallery-view-manager.js';
import type { GalleryViewData } from './types.js';

export const galleryViewType = viewType('gallery');

export const galleryViewModel = galleryViewType.createModel<GalleryViewData>({
  defaultName: 'Gallery View',
  dataViewManager: GallerySingleView,
  defaultData: viewManager => {
    const properties = viewManager.dataSource.properties$.value;
    return {
      filter: { type: 'group', op: 'and', conditions: [] },
      card: {
        titleColumnId: properties.find(
          id => viewManager.dataSource.propertyTypeGet(id) === 'title'
        ),
        coverColumnId: properties.find(
          id => viewManager.dataSource.propertyTypeGet(id) === 'image'
        ),
        visiblePropertyIds: [],
        size: 'medium',
      },
    };
  },
});
