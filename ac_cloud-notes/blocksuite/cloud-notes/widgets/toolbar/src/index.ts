import { WidgetViewExtension } from '@blocksuite/std';
import { literal, unsafeStatic } from 'lit/static-html.js';

import { CLOUD_NOTES_TOOLBAR_WIDGET } from './toolbar';

export * from './toolbar';

export const toolbarWidget = WidgetViewExtension(
  'cloud-notes:page',
  CLOUD_NOTES_TOOLBAR_WIDGET,
  literal`${unsafeStatic(CLOUD_NOTES_TOOLBAR_WIDGET)}`
);
