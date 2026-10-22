import { SurfaceBlockComponent } from './surface-block.js';
import { SurfaceBlockVoidComponent } from './surface-block-void.js';

export function effects() {
  customElements.define('cloud-notes-surface-void', SurfaceBlockVoidComponent);
  customElements.define('cloud-notes-surface', SurfaceBlockComponent);
}
