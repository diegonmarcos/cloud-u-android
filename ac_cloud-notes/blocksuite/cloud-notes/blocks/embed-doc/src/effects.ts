import { EmbedLinkedDocBlockComponent } from './embed-linked-doc-block';
import { EmbedEdgelessLinkedDocBlockComponent } from './embed-linked-doc-block/embed-edgeless-linked-doc-block';
import { EmbedSyncedDocBlockComponent } from './embed-synced-doc-block';
import { EmbedSyncedDocCard } from './embed-synced-doc-block/components/embed-synced-doc-card';
import { EmbedEdgelessSyncedDocBlockComponent } from './embed-synced-doc-block/embed-edgeless-synced-doc-block';

export function effects() {
  customElements.define('cloud-notes-embed-synced-doc-card', EmbedSyncedDocCard);

  customElements.define(
    'cloud-notes-embed-edgeless-linked-doc-block',
    EmbedEdgelessLinkedDocBlockComponent
  );
  customElements.define(
    'cloud-notes-embed-linked-doc-block',
    EmbedLinkedDocBlockComponent
  );

  customElements.define(
    'cloud-notes-embed-edgeless-synced-doc-block',
    EmbedEdgelessSyncedDocBlockComponent
  );
  customElements.define(
    'cloud-notes-embed-synced-doc-block',
    EmbedSyncedDocBlockComponent
  );
}

declare global {
  interface HTMLElementTagNameMap {
    'cloud-notes-embed-synced-doc-card': EmbedSyncedDocCard;
    'cloud-notes-embed-synced-doc-block': EmbedSyncedDocBlockComponent;
    'cloud-notes-embed-edgeless-synced-doc-block': EmbedEdgelessSyncedDocBlockComponent;
    'cloud-notes-embed-linked-doc-block': EmbedLinkedDocBlockComponent;
    'cloud-notes-embed-edgeless-linked-doc-block': EmbedEdgelessLinkedDocBlockComponent;
  }
}
