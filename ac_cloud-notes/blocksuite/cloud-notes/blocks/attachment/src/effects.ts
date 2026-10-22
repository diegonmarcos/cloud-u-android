import { AttachmentBlockComponent } from './attachment-block';
import { AttachmentEdgelessBlockComponent } from './attachment-edgeless-block';

export function effects() {
  customElements.define(
    'cloud-notes-edgeless-attachment',
    AttachmentEdgelessBlockComponent
  );
  customElements.define('cloud-notes-attachment', AttachmentBlockComponent);
}
