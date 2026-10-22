import { CloudNotesMention } from './cloud-notes-mention';

export function effects() {
  customElements.define('cloud-notes-mention', CloudNotesMention);
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-mention': CloudNotesMention;
  }
}
