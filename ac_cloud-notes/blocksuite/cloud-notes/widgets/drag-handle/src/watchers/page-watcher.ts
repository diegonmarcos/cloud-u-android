import { PageViewportService } from '@blocksuite/cloud-notes-shared/services';

import type { CloudNotesDragHandleWidget } from '../drag-handle.js';

export class PageWatcher {
  get pageViewportService() {
    return this.widget.std.get(PageViewportService);
  }

  constructor(readonly widget: CloudNotesDragHandleWidget) {}

  watch() {
    const { disposables } = this.widget;

    disposables.add(
      this.pageViewportService.subscribe(() => {
        this.widget.hide();
      })
    );
  }
}
