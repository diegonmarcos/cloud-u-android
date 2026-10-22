import { CardStyleDropdownMenu } from './dropdown-menu';

export * from './dropdown-menu';

export function effects() {
  customElements.define(
    'cloud-notes-card-style-dropdown-menu',
    CardStyleDropdownMenu
  );
}
