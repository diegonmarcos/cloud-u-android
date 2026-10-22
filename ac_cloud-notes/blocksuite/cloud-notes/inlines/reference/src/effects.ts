import { CloudNotesReference, ReferencePopup } from './reference-node';

export function effects() {
  customElements.define('reference-popup', ReferencePopup);
  customElements.define('cloud-notes-reference', CloudNotesReference);
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-reference': CloudNotesReference;
    'reference-popup': ReferencePopup;
  }
}
