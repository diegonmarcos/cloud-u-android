import { WidgetViewExtension } from '@blocksuite/std';
import { literal, unsafeStatic } from 'lit/static-html.js';

import { CLOUD_NOTES_DOC_REMOTE_SELECTION_WIDGET } from './doc';
import { CLOUD_NOTES_EDGELESS_REMOTE_SELECTION_WIDGET } from './edgeless';

export * from './doc';
export * from './edgeless';

export const docRemoteSelectionWidget = WidgetViewExtension(
  'cloud-notes:page',
  CLOUD_NOTES_DOC_REMOTE_SELECTION_WIDGET,
  literal`${unsafeStatic(CLOUD_NOTES_DOC_REMOTE_SELECTION_WIDGET)}`
);

export const edgelessRemoteSelectionWidget = WidgetViewExtension(
  'cloud-notes:page',
  CLOUD_NOTES_EDGELESS_REMOTE_SELECTION_WIDGET,
  literal`${unsafeStatic(CLOUD_NOTES_EDGELESS_REMOTE_SELECTION_WIDGET)}`
);
