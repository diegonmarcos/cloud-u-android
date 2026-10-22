import { WidgetViewExtension } from '@blocksuite/std';
import { literal, unsafeStatic } from 'lit/static-html.js';

import { CLOUD_NOTES_SCROLL_ANCHORING_WIDGET } from './scroll-anchoring.js';

export * from './scroll-anchoring.js';

export const scrollAnchoringWidget = WidgetViewExtension(
  'cloud-notes:page',
  CLOUD_NOTES_SCROLL_ANCHORING_WIDGET,
  literal`${unsafeStatic(CLOUD_NOTES_SCROLL_ANCHORING_WIDGET)}`
);
