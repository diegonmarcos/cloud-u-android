import { DocTitle } from './doc-title';

export * from './doc-title';

export function effects() {
  customElements.define('cloud-notes-linked-doc-title', DocTitle);
}
