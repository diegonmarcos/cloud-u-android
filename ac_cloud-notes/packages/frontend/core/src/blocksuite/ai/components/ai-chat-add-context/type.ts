import type {
  SearchCollectionMenuAction,
  SearchDocMenuAction,
  SearchTagMenuAction,
} from '@cloud-notes/core/modules/search-menu/services';
import type { LinkedMenuGroup } from '@blocksuite/cloud-notes/widgets/linked-doc';

export interface SearchMenuConfig {
  addContextAvailable: boolean;
  getDocMenuGroup: (
    query: string,
    action: SearchDocMenuAction,
    abortSignal: AbortSignal
  ) => LinkedMenuGroup;
  getTagMenuGroup: (
    query: string,
    action: SearchTagMenuAction,
    abortSignal: AbortSignal
  ) => LinkedMenuGroup;
  getCollectionMenuGroup: (
    query: string,
    action: SearchCollectionMenuAction,
    abortSignal: AbortSignal
  ) => LinkedMenuGroup;
}
