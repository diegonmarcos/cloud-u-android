import {
  CLOUD_NOTES_FRAME_PANEL_BODY,
  FramePanelBody,
} from './body/frame-panel-body';
import { CLOUD_NOTES_FRAME_CARD, FrameCard } from './card/frame-card';
import {
  CLOUD_NOTES_FRAME_CARD_TITLE,
  FrameCardTitle,
} from './card/frame-card-title';
import {
  CLOUD_NOTES_FRAME_TITLE_EDITOR,
  FrameCardTitleEditor,
} from './card/frame-card-title-editor';
import { CLOUD_NOTES_FRAME_PREVIEW, FramePreview } from './card/frame-preview';
import { CLOUD_NOTES_FRAME_PANEL, FramePanel } from './frame-panel';
import {
  CLOUD_NOTES_FRAME_PANEL_HEADER,
  FramePanelHeader,
} from './header/frame-panel-header';
import {
  CLOUD_NOTES_FRAMES_SETTING_MENU,
  FramesSettingMenu,
} from './header/frames-setting-menu';

export function effects() {
  customElements.define(CLOUD_NOTES_FRAME_PANEL, FramePanel);
  customElements.define(CLOUD_NOTES_FRAME_TITLE_EDITOR, FrameCardTitleEditor);
  customElements.define(CLOUD_NOTES_FRAME_CARD, FrameCard);
  customElements.define(CLOUD_NOTES_FRAME_CARD_TITLE, FrameCardTitle);
  customElements.define(CLOUD_NOTES_FRAME_PANEL_BODY, FramePanelBody);
  customElements.define(CLOUD_NOTES_FRAME_PANEL_HEADER, FramePanelHeader);
  customElements.define(CLOUD_NOTES_FRAMES_SETTING_MENU, FramesSettingMenu);
  customElements.define(CLOUD_NOTES_FRAME_PREVIEW, FramePreview);
}
