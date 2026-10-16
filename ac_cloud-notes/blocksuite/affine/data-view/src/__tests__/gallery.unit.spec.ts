import { signal } from '@preact/signals-core';
import { describe, expect, it } from 'vitest';

import type { DataSource } from '../core/data-source/base.js';
import {
  GallerySingleView,
  type GalleryViewData,
  galleryViewModel,
} from '../view-presets/gallery/index.js';
import { viewConverts } from '../view-presets/convert.js';
import { tableViewModel } from '../view-presets/table/index.js';

const createGalleryView = (options?: {
  rowCount?: number;
  filterValue?: string;
  coverColumnId?: string;
  visiblePropertyIds?: string[];
}) => {
  const count = options?.rowCount ?? 3;
  const rows = signal(Array.from({ length: count }, (_, i) => `row-${i + 1}`));
  const columns = signal(['title', 'cover', 'status']);
  const viewData = signal<GalleryViewData>({
    id: 'view-1',
    name: 'Gallery',
    mode: 'gallery',
    filter: options?.filterValue
      ? {
          type: 'group',
          op: 'and',
          conditions: [
            {
              type: 'filter',
              left: { type: 'ref', name: 'status' },
              function: 'is',
              args: [{ type: 'literal', value: options.filterValue }],
            },
          ],
        }
      : { type: 'group', op: 'and', conditions: [] },
    card: {
      titleColumnId: 'title',
      coverColumnId: options?.coverColumnId,
      visiblePropertyIds: options?.visiblePropertyIds ?? [],
    },
  });
  const values = new Map<string, unknown>([
    ['row-1:title', 'Alpha'],
    ['row-1:cover', 'https://example.com/a.png'],
    ['row-1:status', 'Done'],
    ['row-2:title', '  '],
    ['row-2:cover', 'not a url'],
    ['row-2:status', 'Todo'],
    ['row-3:title', 'Gamma'],
    ['row-3:status', 'Done'],
  ]);
  const types = new Map<string, string>([
    ['title', 'title'],
    ['cover', 'image'],
    ['status', 'text'],
  ]);
  const dataSource = {
    rows$: rows,
    properties$: columns,
    readonly$: signal(false),
    featureFlags$: signal({ enable_table_virtual_scroll: false }),
    provider: { getAll: () => new Map() },
    viewDataGet: () => viewData.value,
    viewDataUpdate: (
      _id: string,
      updater: (data: GalleryViewData) => Partial<GalleryViewData>
    ) => {
      viewData.value = { ...viewData.value, ...updater(viewData.value) };
    },
    cellValueGet: (rowId: string, propertyId: string) =>
      values.get(`${rowId}:${propertyId}`),
    cellValueChange: (rowId: string, propertyId: string, value: unknown) => {
      values.set(`${rowId}:${propertyId}`, value);
    },
    rowAdd: () => {
      const rowId = `row-${rows.value.length + 1}`;
      rows.value = [...rows.value, rowId];
      return rowId;
    },
    propertyTypeGet: (propertyId: string) => types.get(propertyId),
    propertyNameGet: (propertyId: string) =>
      ({ title: 'Name', cover: 'Cover', status: 'Status' })[propertyId] ??
      propertyId,
    propertyDataGet: () => ({}),
    propertyReadonlyGet: () => false,
    serviceGet: () => null,
    propertyMetaGet: (type: string) => ({
      type,
      config: {
        rawValue: {
          toJson: ({ value }: { value: unknown }) => value,
          fromJson: ({ value }: { value: unknown }) => value,
          toString: ({ value }: { value: unknown }) =>
            typeof value === 'string' ? value : '',
        },
        jsonValue: {
          schema: {
            safeParse: (value: unknown) => ({ success: true, data: value }),
          },
          isEmpty: () => false,
          type: () => undefined,
        },
      },
      renderer: {},
    }),
    propertyCanDelete: () => true,
    propertyCanDuplicate: () => true,
    propertyTypeCanSet: () => true,
  } as unknown as DataSource;
  const manager = { dataSource, readonly$: signal(false) };
  return {
    view: new GallerySingleView(manager as any, 'view-1'),
    viewData,
    rows,
  };
};

describe('GallerySingleView', () => {
  it('A5.3 shows every row as a card, keeping the rows', () => {
    const { view } = createGalleryView();
    expect(view.cards$.value.map(c => c.rowId)).toEqual([
      'row-1',
      'row-2',
      'row-3',
    ]);
  });

  it('titles cards from the title column and names untitled ones', () => {
    const { view } = createGalleryView();
    expect(view.cards$.value.map(c => c.title)).toEqual([
      'Alpha',
      'Untitled',
      'Gamma',
    ]);
  });

  it('the cover is the first image property and only a real URL counts', () => {
    const { view } = createGalleryView();
    expect(view.coverColumn$.value).toBe('cover');
    expect(view.cards$.value.map(c => c.cover)).toEqual([
      'https://example.com/a.png',
      undefined,
      undefined,
    ]);
  });

  it('shows the chosen visible properties, skipping empty values', () => {
    const { view } = createGalleryView({ visiblePropertyIds: ['status', 'title', 'cover'] });
    const first = view.cards$.value[0];
    expect(first.properties).toEqual([
      { propertyId: 'status', name: 'Status', value: 'Done' },
    ]);
  });

  it('a filter hides rows from the gallery', () => {
    const { view } = createGalleryView({ filterValue: 'Done' });
    expect(view.cards$.value.map(c => c.rowId)).toEqual(['row-1', 'row-3']);
  });

  it('size and cover are stored in the view data', () => {
    const { view, viewData } = createGalleryView();
    expect(view.size$.value).toBe('medium');
    view.setCardSize('large');
    expect(viewData.value.card.size).toBe('large');
    view.setCoverColumn(undefined);
    view.setCardPropertyVisible('status', true);
    view.setCardPropertyVisible('status', true);
    expect(viewData.value.card.visiblePropertyIds).toEqual(['status']);
    view.setCardPropertyVisible('status', false);
    expect(viewData.value.card.visiblePropertyIds).toEqual([]);
  });

  it('adding a card adds a row', () => {
    const { view, rows } = createGalleryView();
    const id = view.rowAdd('end');
    expect(rows.value).toContain(id);
  });
});

describe('gallery view model and conversions', () => {
  it('default data picks the title and the first image property', () => {
    const data = galleryViewModel.model.defaultData({
      dataSource: {
        properties$: signal(['title', 'pic']),
        propertyTypeGet: (id: string) => (id === 'title' ? 'title' : 'image'),
      },
    } as any);
    expect(data.card).toMatchObject({ titleColumnId: 'title', coverColumnId: 'pic' });
  });

  it('table <-> gallery keep filter, sort and the title column', () => {
    const filter = { type: 'group', op: 'and', conditions: [] };
    const sort = { manuallySort: [], sortBy: [] };
    const toGallery = viewConverts.find(
      c => c.from === tableViewModel.type && c.to === galleryViewModel.type
    );
    expect(toGallery).toBeDefined();
    const g = toGallery!.convert({
      filter,
      sort,
      header: { titleColumn: 'title' },
    } as any) as any;
    expect(g.filter).toBe(filter);
    expect(g.sort).toBe(sort);
    expect(g.card).toMatchObject({ titleColumnId: 'title', visiblePropertyIds: [] });

    const toTable = viewConverts.find(
      c => c.from === galleryViewModel.type && c.to === tableViewModel.type
    );
    expect(toTable).toBeDefined();
    const t = toTable!.convert({ filter, sort, card: { titleColumnId: 'title', visiblePropertyIds: [] } } as any) as any;
    expect(t.header).toEqual({ titleColumn: 'title' });
    expect(t.filter).toBe(filter);
  });
});
