import { CLOUD_NOTES_TOOLBAR_WIDGET, CloudNotesToolbarWidget } from './toolbar';

export function effects() {
  customElements.define(CLOUD_NOTES_TOOLBAR_WIDGET, CloudNotesToolbarWidget);
}

declare global {
  interface HTMLElementTagNameMap {
    [CLOUD_NOTES_TOOLBAR_WIDGET]: CloudNotesToolbarWidget;
  }
}
