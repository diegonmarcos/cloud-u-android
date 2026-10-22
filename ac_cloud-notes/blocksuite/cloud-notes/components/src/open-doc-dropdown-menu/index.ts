import { OpenDocDropdownMenu } from './dropdown-menu';

export * from './dropdown-menu';

export function effects() {
  customElements.define('cloud-notes-open-doc-dropdown-menu', OpenDocDropdownMenu);
}
