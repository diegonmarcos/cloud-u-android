import {
  EdgelessRootBlockComponent,
  EdgelessRootPreviewBlockComponent,
  PageRootBlockComponent,
  PreviewRootBlockComponent,
} from './index.js';

export function effects() {
  // Register components by category
  registerRootComponents();
}

function registerRootComponents() {
  customElements.define('cloud-notes-page-root', PageRootBlockComponent);
  customElements.define('cloud-notes-preview-root', PreviewRootBlockComponent);
  customElements.define('cloud-notes-edgeless-root', EdgelessRootBlockComponent);
  customElements.define(
    'cloud-notes-edgeless-root-preview',
    EdgelessRootPreviewBlockComponent
  );
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-edgeless-root': EdgelessRootBlockComponent;
    'cloud-notes-page-root': PageRootBlockComponent;
  }
}
