import { ImageBlockFallbackCard } from './components/image-block-fallback.js';
import { ImageBlockPageComponent } from './components/page-image-block.js';
import { ImageBlockComponent } from './image-block.js';
import { ImageEdgelessBlockComponent } from './image-edgeless-block.js';
import { ImageEdgelessPlaceholderBlockComponent } from './preview-image/edgeless.js';
import { ImagePlaceholderBlockComponent } from './preview-image/page.js';

export function effects() {
  customElements.define('cloud-notes-image', ImageBlockComponent);
  customElements.define('cloud-notes-edgeless-image', ImageEdgelessBlockComponent);
  customElements.define('cloud-notes-page-image', ImageBlockPageComponent);
  customElements.define('cloud-notes-image-fallback-card', ImageBlockFallbackCard);
  customElements.define(
    'cloud-notes-placeholder-preview-image',
    ImagePlaceholderBlockComponent
  );
  customElements.define(
    'cloud-notes-edgeless-placeholder-preview-image',
    ImageEdgelessPlaceholderBlockComponent
  );
}
