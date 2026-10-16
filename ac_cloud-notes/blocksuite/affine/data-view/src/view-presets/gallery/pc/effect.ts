import { GalleryViewUI } from './view.js';

export function pcEffects() {
  if (customElements.get('affine-data-view-gallery')) {
    return;
  }
  customElements.define('affine-data-view-gallery', GalleryViewUI);
}
