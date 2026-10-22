import type * as Effect from '@blocksuite/cloud-notes/effects';
import type { EditorHost } from '@blocksuite/cloud-notes/std';
import type { Store, Transformer, Workspace } from '@blocksuite/cloud-notes/store';
import type { TestCloudNotesEditorContainer } from '@blocksuite/integration-test';

declare type _GLOBAL_ = typeof Effect;

declare global {
  interface Window {
    /** Available on playground window
     * the following instance are initialized in `packages/playground/apps/starter/main.ts`
     */
    $blocksuite: {
      store: typeof import('@blocksuite/cloud-notes/store');
      blocks: {
        database: typeof import('@blocksuite/cloud-notes/blocks/database');
        note: typeof import('@blocksuite/cloud-notes/blocks/note');
      };
      global: {
        utils: typeof import('@blocksuite/cloud-notes/global/utils');
      };
      services: typeof import('@blocksuite/cloud-notes/shared/services');
      editor: typeof import('@blocksuite/integration-test');
      blockStd: typeof import('@blocksuite/cloud-notes/std');
      cloudNotesModel: typeof import('@blocksuite/cloud-notes-model');
    };
    collection: Workspace;
    doc: Store;
    editor: TestCloudNotesEditorContainer;
    host: EditorHost;
    job: Transformer;
  }
}
