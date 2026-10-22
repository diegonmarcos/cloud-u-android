import type { EditorHost } from '@blocksuite/cloud-notes/std';

import {
  CLOUD_NOTES_AI_PANEL_WIDGET,
  CloudNotesAIPanelWidget,
} from '../widgets/ai-panel/ai-panel';

export const getAIPanelWidget = (host: EditorHost): CloudNotesAIPanelWidget => {
  const rootBlockId = host.store.root?.id;
  if (!rootBlockId) {
    throw new Error('rootBlockId is not found');
  }
  const aiPanel = host.view.getWidget(CLOUD_NOTES_AI_PANEL_WIDGET, rootBlockId);
  if (!(aiPanel instanceof CloudNotesAIPanelWidget)) {
    throw new Error('AI panel not found');
  }
  return aiPanel;
};
