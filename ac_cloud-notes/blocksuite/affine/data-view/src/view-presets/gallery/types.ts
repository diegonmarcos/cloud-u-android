import type { FilterGroup } from '../../core/filter/types.js';
import type { Sort } from '../../core/sort/types.js';
import type { BasicViewDataType } from '../../core/view/data-view.js';

export type GalleryCardSize = 'small' | 'medium' | 'large';

type GalleryViewDataShape = {
  filter: FilterGroup;
  sort?: Sort;
  card: {
    titleColumnId?: string;
    /** an `image` property (its value is the image URL) used as the card cover */
    coverColumnId?: string;
    visiblePropertyIds: string[];
    size?: GalleryCardSize;
  };
};

export type GalleryViewData = BasicViewDataType<
  'gallery',
  GalleryViewDataShape
>;

export type GalleryCardProperty = {
  propertyId: string;
  name: string;
  value: string;
};

export type GalleryCard = {
  rowId: string;
  title: string;
  cover?: string;
  properties: GalleryCardProperty[];
};
