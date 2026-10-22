import { ListBlockComponent } from './list-block.js';

export function effects() {
  customElements.define('cloud-notes-list', ListBlockComponent);
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-list': ListBlockComponent;
  }
}
