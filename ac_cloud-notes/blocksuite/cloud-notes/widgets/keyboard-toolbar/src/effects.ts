import {
  CLOUD_NOTES_KEYBOARD_TOOLBAR_WIDGET,
  CloudNotesKeyboardToolbarWidget,
} from './index.js';
import {
  CLOUD_NOTES_KEYBOARD_TOOL_PANEL,
  CloudNotesKeyboardToolPanel,
} from './keyboard-tool-panel.js';
import {
  CLOUD_NOTES_KEYBOARD_TOOLBAR,
  CloudNotesKeyboardToolbar,
} from './keyboard-toolbar.js';

export function effects() {
  customElements.define(
    CLOUD_NOTES_KEYBOARD_TOOLBAR_WIDGET,
    CloudNotesKeyboardToolbarWidget
  );
  customElements.define(CLOUD_NOTES_KEYBOARD_TOOLBAR, CloudNotesKeyboardToolbar);
  customElements.define(CLOUD_NOTES_KEYBOARD_TOOL_PANEL, CloudNotesKeyboardToolPanel);
}

declare global {
  interface HTMLElementTagNameMap {
    [CLOUD_NOTES_KEYBOARD_TOOLBAR]: CloudNotesKeyboardToolbar;
    [CLOUD_NOTES_KEYBOARD_TOOL_PANEL]: CloudNotesKeyboardToolPanel;
  }
}
