import { CLOUD_NOTES_SLASH_MENU_WIDGET } from './consts';
import { InnerSlashMenu, SlashMenu } from './slash-menu-popover';
import { CloudNotesSlashMenuWidget } from './widget';

export function effects() {
  customElements.define(CLOUD_NOTES_SLASH_MENU_WIDGET, CloudNotesSlashMenuWidget);
  customElements.define('cloud-notes-slash-menu', SlashMenu);
  customElements.define('inner-slash-menu', InnerSlashMenu);
}

declare global {
  interface HTMLElementTagNameMap {
    [CLOUD_NOTES_SLASH_MENU_WIDGET]: CloudNotesSlashMenuWidget;
  }
}
