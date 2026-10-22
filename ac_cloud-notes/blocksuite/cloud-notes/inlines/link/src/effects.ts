import { CloudNotesLink } from './link-node/cloud-notes-link';
import { LinkPopup } from './link-node/link-popup/link-popup';

export function effects() {
  customElements.define('link-popup', LinkPopup);
  customElements.define('cloud-notes-link', CloudNotesLink);
}
