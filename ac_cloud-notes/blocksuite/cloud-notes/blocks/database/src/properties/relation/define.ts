import { propertyType, t } from '@blocksuite/data-view';
import zod from 'zod';

/**
 * Relation: a cell holds the ids of rows of ANOTHER database of the same doc (Notion relations).
 * The cell value is computed through RelationSpace (core/relation): forward cells are stored,
 * the back side of a two-way relation is a mirror that stores nothing.
 */
export const relationPropertyType = propertyType('relation');

export const relationPropertyModelConfig = relationPropertyType.modelConfig({
  name: 'Relation',
  propertyData: {
    schema: zod.object({
      targetDatabaseId: zod.string().optional(),
      mirrorOf: zod
        .object({ databaseId: zod.string(), propertyId: zod.string() })
        .optional(),
    }),
    default: () => ({}),
  },
  jsonValue: {
    schema: zod.array(zod.string()),
    isEmpty: ({ value }) => value.length === 0,
    type: () => t.array.instance(t.string.instance()),
  },
  rawValue: {
    schema: zod.array(zod.string()),
    default: () => [],
    toString: ({ value }) => value.join(', '),
    fromString: () => ({ value: [] }),
    toJson: ({ value }) => value,
    fromJson: ({ value }) =>
      Array.isArray(value) ? value.filter(v => typeof v === 'string') : [],
  },
});

/** Rollup: a computed number over the rows a relation property of this database points to. */
export const rollupPropertyType = propertyType('rollup');

export const rollupPropertyModelConfig = rollupPropertyType.modelConfig({
  name: 'Rollup',
  propertyData: {
    schema: zod.object({
      relationPropertyId: zod.string().optional(),
      targetPropertyId: zod.string().optional(),
      fn: zod.string().optional(),
    }),
    default: () => ({ fn: 'count' }),
  },
  jsonValue: {
    schema: zod.number().nullable(),
    isEmpty: ({ value }) => value == null,
    type: () => t.number.instance(),
  },
  rawValue: {
    schema: zod.number().nullable(),
    default: () => null,
    toString: ({ value }) => (value == null ? '' : String(value)),
    fromString: () => ({ value: null }),
    toJson: ({ value }) => value,
    setValue: () => {},
  },
});
