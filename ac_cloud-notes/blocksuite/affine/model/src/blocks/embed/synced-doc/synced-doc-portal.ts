import type { GetStoreOptions, Query } from '@blocksuite/store';

/**
 * Store options for the doc shown inside a synced-doc embed. A live portal is
 * writable (edits land in the source doc) unless the host doc is read-only;
 * every other embed stays a read-only preview.
 */
export function syncedDocStoreOptions(opts: {
  portal?: boolean;
  parentReadonly?: boolean;
  query?: Query;
}): GetStoreOptions {
  const options: GetStoreOptions = {
    readonly: opts.portal !== true || opts.parentReadonly === true,
  };
  if (opts.query) options.query = opts.query;
  return options;
}
