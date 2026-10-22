import type { NoteBlockModel } from '@blocksuite/cloud-notes-model';
import { BlockComponent } from '@blocksuite/std';
import { css, html } from 'lit';

export class NoteBlockComponent extends BlockComponent<NoteBlockModel> {
  static override styles = css`
    .cloud-notes-note-block-container {
      display: flow-root;
    }
    .cloud-notes-note-block-container.selected {
      background-color: var(--cloud-notes-hover-color);
    }
  `;

  override connectedCallback() {
    super.connectedCallback();
  }

  override renderBlock() {
    return html`
      <div class="cloud-notes-note-block-container">
        <div class="cloud-notes-block-children-container">
          ${this.renderChildren(this.model)}
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-note': NoteBlockComponent;
  }
}
