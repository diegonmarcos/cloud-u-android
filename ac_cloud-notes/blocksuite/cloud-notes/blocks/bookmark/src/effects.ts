import { BookmarkBlockComponent } from './bookmark-block';
import { BookmarkEdgelessBlockComponent } from './bookmark-edgeless-block';
import { BookmarkCard } from './components/bookmark-card';

export function effects() {
  customElements.define(
    'cloud-notes-edgeless-bookmark',
    BookmarkEdgelessBlockComponent
  );
  customElements.define('cloud-notes-bookmark', BookmarkBlockComponent);
  customElements.define('bookmark-card', BookmarkCard);
}
