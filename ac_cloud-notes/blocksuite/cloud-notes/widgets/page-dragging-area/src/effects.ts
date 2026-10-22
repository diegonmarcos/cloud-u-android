import {
  CLOUD_NOTES_PAGE_DRAGGING_AREA_WIDGET,
  CloudNotesPageDraggingAreaWidget,
} from './index';

export function effects() {
  customElements.define(
    CLOUD_NOTES_PAGE_DRAGGING_AREA_WIDGET,
    CloudNotesPageDraggingAreaWidget
  );
}
