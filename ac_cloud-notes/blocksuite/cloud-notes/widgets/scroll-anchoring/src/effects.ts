import {
  CLOUD_NOTES_SCROLL_ANCHORING_WIDGET,
  CloudNotesScrollAnchoringWidget,
} from './scroll-anchoring.js';

export function effects() {
  customElements.define(
    CLOUD_NOTES_SCROLL_ANCHORING_WIDGET,
    CloudNotesScrollAnchoringWidget
  );
}

declare global {
  interface HTMLElementTagNameMap {
    [CLOUD_NOTES_SCROLL_ANCHORING_WIDGET]: CloudNotesScrollAnchoringWidget;
  }
}
