import { SeniorToolExtension } from '@blocksuite/cloud-notes-widget-edgeless-toolbar';
import { html } from 'lit';

export const templateSeniorTool = SeniorToolExtension(
  'template',
  ({ block }) => {
    return {
      name: 'Template',
      content: html`<edgeless-template-button .edgeless=${block}>
      </edgeless-template-button>`,
    };
  }
);
