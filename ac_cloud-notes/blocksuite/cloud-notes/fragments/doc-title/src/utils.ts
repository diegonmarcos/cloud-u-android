import type { EditorHost } from '@blocksuite/std';

import type { DocTitle } from './doc-title';

export function getDocTitleByEditorHost(
  editorHost: EditorHost
): DocTitle | null {
  const docViewport = editorHost.closest('.cloud-notes-page-viewport');
  if (!docViewport) return null;
  return docViewport.querySelector('doc-title');
}
