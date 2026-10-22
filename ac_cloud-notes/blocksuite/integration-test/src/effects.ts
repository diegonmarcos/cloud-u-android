import '@blocksuite/cloud-notes/effects';

import { TestCloudNotesEditorContainer } from './editors/index.js';

export function effects() {
  customElements.define('cloud-notes-editor-container', TestCloudNotesEditorContainer);
}
