import { WidgetViewExtension } from '@blocksuite/std';
import { literal, unsafeStatic } from 'lit/static-html.js';

import { CLOUD_NOTES_DRAG_HANDLE_WIDGET } from './consts';

export * from './consts';
export * from './drag-handle';
export * from './utils';
export type { DragBlockPayload } from './watchers/drag-event-watcher';

export const dragHandleWidget = WidgetViewExtension(
  'cloud-notes:page',
  CLOUD_NOTES_DRAG_HANDLE_WIDGET,
  literal`${unsafeStatic(CLOUD_NOTES_DRAG_HANDLE_WIDGET)}`
);
