import { CLOUD_NOTES_DOC_REMOTE_SELECTION_WIDGET } from './doc';
import { CloudNotesDocRemoteSelectionWidget } from './doc/doc-remote-selection';
import {
  CLOUD_NOTES_EDGELESS_REMOTE_SELECTION_WIDGET,
  EdgelessRemoteSelectionWidget,
} from './edgeless';

export function effects() {
  customElements.define(
    CLOUD_NOTES_DOC_REMOTE_SELECTION_WIDGET,
    CloudNotesDocRemoteSelectionWidget
  );
  customElements.define(
    CLOUD_NOTES_EDGELESS_REMOTE_SELECTION_WIDGET,
    EdgelessRemoteSelectionWidget
  );
}
