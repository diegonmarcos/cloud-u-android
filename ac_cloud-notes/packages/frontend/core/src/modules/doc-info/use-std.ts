import { createBlockStdScope } from '@cloud-notes/core/blocksuite/manager/view';
import type { Store } from '@blocksuite/cloud-notes/store';
import { useMemo } from 'react';

export function useBlockStdScope(doc: Store) {
  return useMemo(() => createBlockStdScope(doc), [doc]);
}
