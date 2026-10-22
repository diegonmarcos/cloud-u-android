import type { InsertToPosition } from '@blocksuite/cloud-notes-shared/utils';
import { computed } from '@preact/signals-core';

import { evalFilter } from '../../core/filter/eval.js';
import { FilterTrait, filterTraitKey } from '../../core/filter/trait.js';
import type { FilterGroup } from '../../core/filter/types.js';
import { emptyFilterGroup } from '../../core/filter/utils.js';
import { SortManager, sortTraitKey } from '../../core/sort/manager.js';
import { PropertyBase } from '../../core/view-manager/property.js';
import { type Row, RowBase } from '../../core/view-manager/row.js';
import {
  type SingleView,
  SingleViewBase,
} from '../../core/view-manager/single-view.js';
import type { ViewManager } from '../../core/view-manager/view-manager.js';
import type {
  GalleryCard,
  GalleryCardSize,
  GalleryViewData,
} from './types.js';

const isImageUrl = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.length > 0 &&
  /^(https?:|data:image\/|blob:|\/)/i.test(value);

/**
 * Notion-style Gallery: every row is a card (cover image, title, a few properties).
 * Same data model as the other views (filter, sort, card layout); only the presentation differs,
 * so converting table <-> gallery keeps rows, filter, sort and the title column.
 */
export class GallerySingleView extends SingleViewBase<GalleryViewData> {
  propertiesRaw$ = computed(() =>
    this.dataSource.properties$.value.map(id => this.propertyGetOrCreate(id))
  );

  properties$ = this.propertiesRaw$;

  detailProperties$ = computed(() =>
    this.propertiesRaw$.value.filter(p => p.type$.value !== 'title')
  );

  private readonly filter$ = computed(
    () => this.data$.value?.filter ?? emptyFilterGroup
  );

  private readonly sortList$ = computed(() => this.data$.value?.sort);

  private readonly sortManager = this.traitSet(
    sortTraitKey,
    new SortManager(this.sortList$, this, {
      setSortList: sortList => {
        this.dataUpdate(data => ({ sort: { ...data.sort, ...sortList } }));
      },
    })
  );

  filterTrait = this.traitSet(
    filterTraitKey,
    new FilterTrait(this.filter$, this, {
      filterSet: (filter: FilterGroup) => {
        this.dataUpdate(() => ({ filter }));
      },
    })
  );

  mainProperties$ = computed(() => ({
    titleColumn:
      this.data$.value?.card?.titleColumnId ??
      this.propertiesRaw$.value.find(p => p.type$.value === 'title')?.id,
  }));

  readonly$ = computed(() => this.manager.readonly$.value);

  size$ = computed<GalleryCardSize>(
    () => this.data$.value?.card?.size ?? 'medium'
  );

  /** `image` properties a cover can come from */
  imageProperties$ = computed(() =>
    this.propertiesRaw$.value.filter(p => p.type$.value === 'image')
  );

  /** The cover property: the chosen one, else the first `image` property (none = no cover). */
  coverColumn$ = computed(() => {
    const chosen = this.data$.value?.card?.coverColumnId;
    const images = this.imageProperties$.value;
    return images.find(p => p.id === chosen)?.id ?? images[0]?.id;
  });

  private readonly visibleCardProperties$ = computed(() => {
    const card = this.data$.value?.card;
    const title = this.mainProperties$.value.titleColumn;
    const cover = this.coverColumn$.value;
    return (card?.visiblePropertyIds ?? [])
      .filter(id => id !== title && id !== cover)
      .map(id => this.propertyGetOrCreate(id));
  });

  cards$ = computed<GalleryCard[]>(() => {
    const titleColumn = this.mainProperties$.value.titleColumn;
    const coverColumn = this.coverColumn$.value;
    return this.rows$.value.map(row => {
      const title = titleColumn
        ? (this.cellGetOrCreate(row.rowId, titleColumn).stringValue$.value ??
          '')
        : '';
      const coverValue = coverColumn
        ? this.cellGetOrCreate(row.rowId, coverColumn).jsonValue$.value
        : undefined;
      return {
        rowId: row.rowId,
        title: title.trim() || 'Untitled',
        cover: isImageUrl(coverValue) ? coverValue : undefined,
        properties: this.visibleCardProperties$.value.flatMap(property => {
          const value = this.cellGetOrCreate(row.rowId, property.id)
            .stringValue$.value;
          return value
            ? [
                {
                  propertyId: property.id,
                  name: property.name$.value || '',
                  value,
                },
              ]
            : [];
        }),
      };
    });
  });

  get type(): string {
    return this.data$.value?.mode ?? 'gallery';
  }

  constructor(viewManager: ViewManager, viewId: string) {
    super(viewManager, viewId);
  }

  isShow(rowId: string): boolean {
    if (this.filter$.value.conditions.length) {
      const rowMap = Object.fromEntries(
        this.propertiesRaw$.value.map(column => [
          column.id,
          column.cellGetOrCreate(rowId).jsonValue$.value,
        ])
      );
      return evalFilter(this.filter$.value, rowMap);
    }
    return true;
  }

  override rowsMapping(rows: Row[]) {
    return this.sortManager.sort(super.rowsMapping(rows));
  }

  propertyGetOrCreate(propertyId: string): GalleryProperty {
    return new GalleryProperty(this, propertyId);
  }

  override rowGetOrCreate(rowId: string): GalleryRow {
    return new GalleryRow(this, rowId);
  }

  setCoverColumn(propertyId: string | undefined) {
    this.dataUpdate(data => ({
      card: { ...data.card, coverColumnId: propertyId },
    }));
  }

  setCardSize(size: GalleryCardSize) {
    this.dataUpdate(data => ({ card: { ...data.card, size } }));
  }

  setCardPropertyVisible(propertyId: string, visible: boolean) {
    this.dataUpdate(data => {
      const current = data.card?.visiblePropertyIds ?? [];
      const next = visible
        ? current.includes(propertyId)
          ? current
          : [...current, propertyId]
        : current.filter(id => id !== propertyId);
      return { card: { ...data.card, visiblePropertyIds: next } };
    });
  }
}

export class GalleryProperty extends PropertyBase {
  hide$ = computed(() => false);

  constructor(view: GallerySingleView, propertyId: string) {
    super(view as SingleView, propertyId);
  }

  hideSet(_hide: boolean): void {}

  move(_position: InsertToPosition): void {}
}

export class GalleryRow extends RowBase {
  constructor(
    readonly galleryView: GallerySingleView,
    rowId: string
  ) {
    super(galleryView, rowId);
  }
}
