import { BlockViewExtension } from '@blocksuite/cloud-notes/std';
import type { ExtensionType } from '@blocksuite/cloud-notes/store';
import { literal } from 'lit/static-html.js';

export const AIChatBlockSpec: ExtensionType[] = [
  BlockViewExtension('cloud-notes:embed-ai-chat', model => {
    const parent = model.store.getParent(model.id);

    if (parent?.flavour === 'cloud-notes:surface') {
      return literal`cloud-notes-edgeless-ai-chat`;
    }

    return literal`cloud-notes-ai-chat`;
  }),
];
