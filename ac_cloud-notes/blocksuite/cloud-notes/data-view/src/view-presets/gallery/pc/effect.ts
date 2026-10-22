import { GalleryViewUI } from './view.js';

export function pcEffects() {
  if (customElements.get('cloud-notes-data-view-gallery')) {
    return;
  }
  customElements.define('cloud-notes-data-view-gallery', GalleryViewUI);
}
