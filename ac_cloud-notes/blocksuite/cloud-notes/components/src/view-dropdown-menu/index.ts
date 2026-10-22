import { ViewDropdownMenu } from './dropdown-menu';

export * from './dropdown-menu';

export function effects() {
  customElements.define('cloud-notes-view-dropdown-menu', ViewDropdownMenu);
}
