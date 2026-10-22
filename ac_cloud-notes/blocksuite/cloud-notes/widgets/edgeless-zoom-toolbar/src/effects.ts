import {
  CLOUD_NOTES_EDGELESS_ZOOM_TOOLBAR_WIDGET,
  CloudNotesEdgelessZoomToolbarWidget,
} from '.';
import { MobileZoomRuler } from './mobile-zoom-ruler';
import { ZoomBarToggleButton } from './zoom-bar-toggle-button';
import { EdgelessZoomToolbar } from './zoom-toolbar';

export function effects() {
  customElements.define('edgeless-zoom-toolbar', EdgelessZoomToolbar);
  customElements.define('zoom-bar-toggle-button', ZoomBarToggleButton);
  customElements.define('mobile-zoom-ruler', MobileZoomRuler);
  customElements.define(
    CLOUD_NOTES_EDGELESS_ZOOM_TOOLBAR_WIDGET,
    CloudNotesEdgelessZoomToolbarWidget
  );
}
