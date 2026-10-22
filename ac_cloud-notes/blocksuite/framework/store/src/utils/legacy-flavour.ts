/**
 * Documents written before the rename stored every block type under the old
 * flavour prefix. These helpers read that prefix as the current one, and
 * rewrite a stored block the first time it is loaded, so old notes keep
 * opening and the stored data converges on the current ids.
 */
export const FLAVOUR_PREFIX = 'cloud-notes:';
export const LEGACY_FLAVOUR_PREFIX = 'affine:';

export function normalizeFlavour<T extends string | undefined>(flavour: T): T {
  if (typeof flavour === 'string' && flavour.startsWith(LEGACY_FLAVOUR_PREFIX)) {
    return (FLAVOUR_PREFIX + flavour.slice(LEGACY_FLAVOUR_PREFIX.length)) as T;
  }
  return flavour;
}

type FlavourHolder = {
  get(key: 'sys:flavour'): unknown;
  set(key: 'sys:flavour', value: string): unknown;
  doc?: { transact(fn: () => void, origin?: unknown): void } | null;
};

/** Origin of the one-time rewrite, so listeners can tell it from an edit. */
export const LEGACY_FLAVOUR_MIGRATION_ORIGIN = 'legacy-flavour-migration';

/**
 * Returns the block's flavour under the current prefix and, when the stored
 * value still carries the old one, rewrites it in place.
 */
export function migrateStoredFlavour(yBlock: FlavourHolder): string {
  const stored = yBlock.get('sys:flavour') as string;
  const current = normalizeFlavour(stored);
  if (current !== stored) {
    const write = () => yBlock.set('sys:flavour', current);
    if (yBlock.doc) {
      yBlock.doc.transact(write, LEGACY_FLAVOUR_MIGRATION_ORIGIN);
    } else {
      write();
    }
  }
  return current;
}

/** A Map whose string keys are read under the current flavour prefix. */
export class FlavourKeyedMap<V> extends Map<string, V> {
  override get(key: string) {
    return super.get(normalizeFlavour(key));
  }
  override has(key: string) {
    return super.has(normalizeFlavour(key));
  }
}
