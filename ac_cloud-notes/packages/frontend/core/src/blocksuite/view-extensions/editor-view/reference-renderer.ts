import type { ReactToLit } from '@cloud-notes/component';
import type { CloudNotesReference } from '@blocksuite/cloud-notes/inlines/reference';
import { ReferenceNodeConfigExtension } from '@blocksuite/cloud-notes/inlines/reference';
import type { ExtensionType } from '@blocksuite/cloud-notes/store';

export type ReferenceReactRenderer = (
  reference: CloudNotesReference
) => React.ReactElement;

export function patchReferenceRenderer(
  reactToLit: ReactToLit,
  reactRenderer: ReferenceReactRenderer
): ExtensionType {
  const customContent = (reference: CloudNotesReference) => {
    const node = reactRenderer(reference);
    return reactToLit(node, true);
  };

  return ReferenceNodeConfigExtension({
    customContent,
    hidePopup: BUILD_CONFIG.isMobileEdition,
  });
}
