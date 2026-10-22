import { CloudNotesText } from './nodes/cloud-notes-text';

export function effects() {
  customElements.define('cloud-notes-text', CloudNotesText);
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-text': CloudNotesText;
  }
}
