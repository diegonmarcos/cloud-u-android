import { ViewExtensionManager } from '@blocksuite/cloud-notes/ext-loader';
import { getInternalViewExtensions } from '@blocksuite/cloud-notes/extensions/view';

const manager = new ViewExtensionManager(getInternalViewExtensions());

export function getTestViewManager() {
  return manager;
}
