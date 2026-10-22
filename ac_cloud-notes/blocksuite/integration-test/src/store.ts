import { StoreExtensionManager } from '@blocksuite/cloud-notes/ext-loader';
import { getInternalStoreExtensions } from '@blocksuite/cloud-notes/extensions/store';

const manager = new StoreExtensionManager(getInternalStoreExtensions());

export function getTestStoreManager() {
  return manager;
}
