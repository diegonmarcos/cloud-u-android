import { createIdentifier } from '@blocksuite/global/di';
import type { ExtensionType } from '@blocksuite/store';
import type { ReadonlySignal } from '@preact/signals-core';

import type { CloudNotesUserInfo } from './types';

export interface UserListService {
  users$: ReadonlySignal<CloudNotesUserInfo[]>;
  isLoading$: ReadonlySignal<boolean>;
  searchText$: ReadonlySignal<string>;
  hasMore$: ReadonlySignal<boolean>;
  loadMore(): void;
  search(keyword: string): void;
}

export const UserListProvider = createIdentifier<UserListService>(
  'cloud-notes-user-list-service'
);

export function UserListServiceExtension(
  service: UserListService
): ExtensionType {
  return {
    setup(di) {
      di.addImpl(UserListProvider, () => service);
    },
  };
}
