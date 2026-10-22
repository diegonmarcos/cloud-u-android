import { CenterPeek } from './components/layout';
import { DatabaseTitle } from './components/title';
import { DatabaseBlockComponent } from './database-block';
import { DatabaseDndPreviewBlockComponent } from './database-dnd-preview-block';
import { BlockRenderer } from './detail-panel/block-renderer';
import { NoteRenderer } from './detail-panel/note-renderer';
import { CreatedTimeCell } from './properties/created-time/cell-renderer';
import { LinkCell } from './properties/link/cell-renderer';
import { RichTextCell } from './properties/rich-text/cell-renderer';
import { IconCell } from './properties/title/icon';
import { HeaderAreaTextCell } from './properties/title/text';

export function effects() {
  customElements.define('cloud-notes-database-title', DatabaseTitle);
  customElements.define('data-view-header-area-icon', IconCell);
  customElements.define('cloud-notes-database-link-cell', LinkCell);
  customElements.define('data-view-header-area-text', HeaderAreaTextCell);
  customElements.define('cloud-notes-database-rich-text-cell', RichTextCell);
  customElements.define('cloud-notes-database-created-time-cell', CreatedTimeCell);
  customElements.define('center-peek', CenterPeek);
  customElements.define('database-datasource-note-renderer', NoteRenderer);
  customElements.define('database-datasource-block-renderer', BlockRenderer);
  customElements.define('cloud-notes-database', DatabaseBlockComponent);

  customElements.define(
    'cloud-notes-dnd-preview-database',
    DatabaseDndPreviewBlockComponent
  );
}
