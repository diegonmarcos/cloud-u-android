import { CLOUD_NOTES_OUTLINE_NOTICE, OutlineNotice } from './body/outline-notice';
import {
  CLOUD_NOTES_OUTLINE_PANEL_BODY,
  OutlinePanelBody,
} from './body/outline-panel-body';
import { CLOUD_NOTES_OUTLINE_NOTE_CARD, OutlineNoteCard } from './card/outline-card';
import {
  CLOUD_NOTES_OUTLINE_BLOCK_PREVIEW,
  OutlineBlockPreview,
} from './card/outline-preview';
import {
  CLOUD_NOTES_OUTLINE_PANEL_HEADER,
  OutlinePanelHeader,
} from './header/outline-panel-header';
import {
  CLOUD_NOTES_OUTLINE_NOTE_PREVIEW_SETTING_MENU,
  OutlineNotePreviewSettingMenu,
} from './header/outline-setting-menu';
import {
  CLOUD_NOTES_MOBILE_OUTLINE_MENU,
  MobileOutlineMenu,
} from './mobile-outline-panel';
import { CLOUD_NOTES_OUTLINE_PANEL, OutlinePanel } from './outline-panel';
import { CLOUD_NOTES_OUTLINE_VIEWER, OutlineViewer } from './outline-viewer';

export function effects() {
  customElements.define(
    CLOUD_NOTES_OUTLINE_NOTE_PREVIEW_SETTING_MENU,
    OutlineNotePreviewSettingMenu
  );
  customElements.define(CLOUD_NOTES_OUTLINE_NOTICE, OutlineNotice);
  customElements.define(CLOUD_NOTES_OUTLINE_PANEL, OutlinePanel);
  customElements.define(CLOUD_NOTES_OUTLINE_PANEL_HEADER, OutlinePanelHeader);
  customElements.define(CLOUD_NOTES_OUTLINE_NOTE_CARD, OutlineNoteCard);
  customElements.define(CLOUD_NOTES_OUTLINE_VIEWER, OutlineViewer);
  customElements.define(CLOUD_NOTES_MOBILE_OUTLINE_MENU, MobileOutlineMenu);
  customElements.define(CLOUD_NOTES_OUTLINE_BLOCK_PREVIEW, OutlineBlockPreview);
  customElements.define(CLOUD_NOTES_OUTLINE_PANEL_BODY, OutlinePanelBody);
}
