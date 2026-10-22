import { CloudNotesAddBlockWidget } from './components/add-block-widget';
import {
  EDGELESS_DND_PREVIEW_ELEMENT,
  EdgelessDndPreviewElement,
} from './components/edgeless-preview/preview';
import { CLOUD_NOTES_ADD_BLOCK_WIDGET, CLOUD_NOTES_DRAG_HANDLE_WIDGET } from './consts';
import { CloudNotesDragHandleWidget } from './drag-handle';

export function effects() {
  customElements.define(CLOUD_NOTES_DRAG_HANDLE_WIDGET, CloudNotesDragHandleWidget);
  customElements.define(CLOUD_NOTES_ADD_BLOCK_WIDGET, CloudNotesAddBlockWidget);
  customElements.define(
    EDGELESS_DND_PREVIEW_ELEMENT,
    EdgelessDndPreviewElement
  );
}
