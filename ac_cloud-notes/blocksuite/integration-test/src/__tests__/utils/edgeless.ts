import type {
  EdgelessRootBlockComponent,
  PageRootBlockComponent,
} from '@blocksuite/cloud-notes/blocks/root';
import type { SurfaceBlockComponent } from '@blocksuite/cloud-notes/blocks/surface';
import type { Store } from '@blocksuite/store';

import type { TestCloudNotesEditorContainer } from '../../index.js';

export function getSurface(doc: Store, editor: TestCloudNotesEditorContainer) {
  const surfaceModel = doc.getModelsByFlavour('cloud-notes:surface');

  return editor.host!.view.getBlock(
    surfaceModel[0]!.id
  ) as SurfaceBlockComponent;
}

export function getDocRootBlock(
  doc: Store,
  editor: TestCloudNotesEditorContainer,
  mode: 'page'
): PageRootBlockComponent;
export function getDocRootBlock(
  doc: Store,
  editor: TestCloudNotesEditorContainer,
  mode: 'edgeless'
): EdgelessRootBlockComponent;
export function getDocRootBlock(
  doc: Store,
  editor: TestCloudNotesEditorContainer,
  _?: 'edgeless' | 'page'
) {
  return editor.host!.view.getBlock(doc.root!.id) as
    | EdgelessRootBlockComponent
    | PageRootBlockComponent;
}

export function addNote(doc: Store, props: Record<string, any> = {}) {
  const noteId = doc.addBlock(
    'cloud-notes:note',
    {
      xywh: '[0, 0, 800, 100]',
      ...props,
    },
    doc.root
  );

  doc.addBlock('cloud-notes:paragraph', {}, noteId);

  return noteId;
}
