import { DocModeProvider } from '@blocksuite/cloud-notes/shared/services';
import type { Workspace } from '@blocksuite/cloud-notes/store';
import { TestCloudNotesEditorContainer } from '@blocksuite/integration-test';

import {
  getDocFromUrlParams,
  listenHashChange,
  setDocModeFromUrlParams,
} from '../../_common/history.js';
import { createTestApp } from './app.js';

export async function mountDefaultDocEditor(collection: Workspace) {
  const app = document.getElementById('app');
  if (!app) return;

  const url = new URL(location.toString());
  const doc = getDocFromUrlParams(collection, url);

  const editor = await createTestApp(doc, collection);

  const modeService = editor.std.provider.get(DocModeProvider);
  editor.mode = modeService.getPrimaryMode(doc.id);
  setDocModeFromUrlParams(modeService, url.searchParams, doc.id);

  // for multiple editor
  const params = new URLSearchParams(location.search);
  const init = params.get('init');
  if (init && init.startsWith('multiple-editor')) {
    app.childNodes.forEach(node => {
      if (node instanceof TestCloudNotesEditorContainer) {
        node.style.flex = '1';
        if (init === 'multiple-editor-vertical') {
          node.style.overflow = 'auto';
        }
      }
    });
  }

  listenHashChange(collection, editor);

  return editor;
}
