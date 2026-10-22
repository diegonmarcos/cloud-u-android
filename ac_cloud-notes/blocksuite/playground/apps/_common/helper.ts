import { TestWorkspace } from '@blocksuite/cloud-notes/store/test';
import { getTestStoreManager } from '@blocksuite/integration-test/store';

export function createEmptyDoc() {
  const collection = new TestWorkspace();
  collection.storeExtensions = getTestStoreManager().get('store');
  collection.meta.initialize();
  const doc = collection.createDoc();
  const store = doc.getStore();

  return {
    doc,
    init() {
      doc.load();
      const rootId = store.addBlock('cloud-notes:page', {});
      store.addBlock('cloud-notes:surface', {}, rootId);
      const noteId = store.addBlock('cloud-notes:note', {}, rootId);
      store.addBlock('cloud-notes:paragraph', {}, noteId);
      return store;
    },
  };
}
