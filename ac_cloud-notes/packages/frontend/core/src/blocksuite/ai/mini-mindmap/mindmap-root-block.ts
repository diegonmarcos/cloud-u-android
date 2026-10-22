import type { RootBlockModel } from '@blocksuite/cloud-notes/model';
import { BlockComponent } from '@blocksuite/cloud-notes/std';
import { html } from 'lit';

export class MindmapRootBlock extends BlockComponent<RootBlockModel> {
  override render() {
    return html`
      <style>
        .cloud-notes-mini-mindmap-root {
          display: block;
          width: 100%;
          height: 100%;

          background-size: 20px 20px;
          background-color: var(--cloud-notes-background-primary-color);
          background-image: radial-gradient(
            var(--cloud-notes-edgeless-grid-color) 1px,
            var(--cloud-notes-background-primary-color) 1px
          );
        }
      </style>
      <div class="cloud-notes-mini-mindmap-root">
        ${this.host.renderChildren(this.model)}
      </div>
    `;
  }
}
