import {
  CLOUD_NOTES_VIEWPORT_OVERLAY_WIDGET,
  CloudNotesViewportOverlayWidget,
} from './index';

export function effects() {
  customElements.define(
    CLOUD_NOTES_VIEWPORT_OVERLAY_WIDGET,
    CloudNotesViewportOverlayWidget
  );
}
