import { LinkPreview } from './link';

export * from './link';

export function effects() {
  customElements.define('cloud-notes-link-preview', LinkPreview);
}
