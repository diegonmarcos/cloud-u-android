import type { MindmapStyle } from '@blocksuite/cloud-notes/model';
import type { EditorHost } from '@blocksuite/cloud-notes/std';
import { html, nothing } from 'lit';

import { markdownToMindmap } from '../mini-mindmap';
import { getAIPanelWidget } from '../utils/ai-widgets';
import type { AIContext } from '../utils/context';
import type { CloudNotesAIPanelWidgetConfig } from '../widgets/ai-panel/type';

export const createMindmapRenderer: (
  host: EditorHost,
  /**
   * Used to store data for later use during rendering.
   */
  ctx: AIContext,
  style?: MindmapStyle
) => CloudNotesAIPanelWidgetConfig['answerRenderer'] = (host, ctx, style) => {
  return (answer, state) => {
    if (state === 'generating') {
      const panel = getAIPanelWidget(host);
      panel.generatingElement?.updateLoadingProgress(2);
    }

    if (state !== 'finished' && state !== 'error') {
      return nothing;
    }

    return html`<mini-mindmap-preview
      .ctx=${ctx}
      .host=${host}
      .answer=${answer}
      .mindmapStyle=${style}
      .templateShow=${style === undefined}
      .height=${300}
    ></mini-mindmap-preview>`;
  };
};

/**
 * Creates a renderer for executing a handler.
 * The ai panel will not display anything after the answer is generated.
 */
export const createMindmapExecuteRenderer: (
  host: EditorHost,
  /**
   * Used to store data for later use during rendering.
   */
  ctx: AIContext,
  handler: (host: EditorHost, ctx: AIContext) => void
) => CloudNotesAIPanelWidgetConfig['answerRenderer'] = (host, ctx, handler) => {
  return (answer, state) => {
    if (state !== 'finished') {
      const panel = getAIPanelWidget(host);
      panel.generatingElement?.updateLoadingProgress(2);
      return nothing;
    }

    ctx.set({
      node: markdownToMindmap(answer, host.store, host.std.store.provider),
    });

    handler(host, ctx);

    return nothing;
  };
};
