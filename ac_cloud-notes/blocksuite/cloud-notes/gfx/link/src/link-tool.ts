import { QuickToolExtension } from '@blocksuite/cloud-notes-widget-edgeless-toolbar';
import { html } from 'lit';

import { buildLinkDenseMenu } from './toolbar/link-dense-menu';

export const linkQuickTool = QuickToolExtension('link', ({ block, gfx }) => {
  return {
    content: html`<edgeless-link-tool-button
      .edgeless=${block}
    ></edgeless-link-tool-button>`,
    menu: buildLinkDenseMenu(block, gfx),
  };
});
