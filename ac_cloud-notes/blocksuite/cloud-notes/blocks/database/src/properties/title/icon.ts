import { BaseCellRenderer } from '@blocksuite/data-view';
import { css, html } from 'lit';

export class IconCell extends BaseCellRenderer<string> {
  static override styles = css`
    cloud-notes-database-image-cell {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
    }
    cloud-notes-database-image-cell img {
      width: 20px;
      height: 20px;
    }
  `;

  override render() {
    return html`<img src=${this.value ?? ''}></img>`;
  }
}
