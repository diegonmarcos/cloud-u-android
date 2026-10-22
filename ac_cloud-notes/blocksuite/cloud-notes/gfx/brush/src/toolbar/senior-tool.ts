import { SeniorToolExtension } from '@blocksuite/cloud-notes-widget-edgeless-toolbar';
import { html } from 'lit';

export const penSeniorTool = SeniorToolExtension('pen', ({ block }) => {
  return {
    name: 'Pen',
    content: html`<div class="pen-and-eraser">
      <edgeless-pen-tool-button .edgeless=${block}></edgeless-pen-tool-button>

      <edgeless-eraser-tool-button
        .edgeless=${block}
      ></edgeless-eraser-tool-button>
    </div> `,
  };
});
