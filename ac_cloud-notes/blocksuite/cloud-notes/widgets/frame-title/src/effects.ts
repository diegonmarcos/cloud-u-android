import {
  CLOUD_NOTES_FRAME_TITLE_WIDGET,
  CloudNotesFrameTitleWidget,
} from './cloud-notes-frame-title-widget.js';
import { EdgelessFrameTitleEditor } from './edgeless-frame-title-editor.js';
import { CLOUD_NOTES_FRAME_TITLE, CloudNotesFrameTitle } from './frame-title.js';

export function effects() {
  customElements.define(CLOUD_NOTES_FRAME_TITLE_WIDGET, CloudNotesFrameTitleWidget);
  customElements.define(CLOUD_NOTES_FRAME_TITLE, CloudNotesFrameTitle);
  customElements.define(
    'edgeless-frame-title-editor',
    EdgelessFrameTitleEditor
  );
}
