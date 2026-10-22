import { SizeDropdownMenu } from './dropdown-menu';

export * from './dropdown-menu';

export function effects() {
  customElements.define('cloud-notes-size-dropdown-menu', SizeDropdownMenu);
}
