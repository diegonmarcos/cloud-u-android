import { CodeBlockComponent } from './code-block';
import {
  CLOUD_NOTES_CODE_TOOLBAR_WIDGET,
  CloudNotesCodeToolbarWidget,
} from './code-toolbar';
import { CloudNotesCodeToolbar } from './code-toolbar/components/code-toolbar';
import { LanguageListButton } from './code-toolbar/components/lang-button';
import { CloudNotesCodeMoreMenu } from './code-toolbar/components/more-menu';
import { PreviewButton } from './code-toolbar/components/preview-button';
import { CloudNotesCodeUnit } from './highlight/cloud-notes-code-unit';

export function effects() {
  customElements.define('language-list-button', LanguageListButton);
  customElements.define('cloud-notes-code-toolbar', CloudNotesCodeToolbar);
  customElements.define('cloud-notes-code-more-menu', CloudNotesCodeMoreMenu);
  customElements.define(CLOUD_NOTES_CODE_TOOLBAR_WIDGET, CloudNotesCodeToolbarWidget);
  customElements.define('cloud-notes-code-unit', CloudNotesCodeUnit);
  customElements.define('cloud-notes-code', CodeBlockComponent);
  customElements.define('preview-button', PreviewButton);
}

declare global {
  interface HTMLElementTagNameMap {
    'language-list-button': LanguageListButton;
    'cloud-notes-code-toolbar': CloudNotesCodeToolbar;
    'cloud-notes-code-more-menu': CloudNotesCodeMoreMenu;
    'preview-button': PreviewButton;
    [CLOUD_NOTES_CODE_TOOLBAR_WIDGET]: CloudNotesCodeToolbarWidget;
  }
}
