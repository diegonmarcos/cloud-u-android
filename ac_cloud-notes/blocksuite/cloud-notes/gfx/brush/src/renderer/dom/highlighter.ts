import {
  DomElementRendererExtension,
  type DomRenderer,
} from '@blocksuite/cloud-notes-block-surface';
import type { HighlighterElementModel } from '@blocksuite/cloud-notes-model';
import { DefaultTheme } from '@blocksuite/cloud-notes-model';

import { renderBrushLikeDom } from './shared';

export const HighlighterDomRendererExtension = DomElementRendererExtension(
  'highlighter',
  (
    model: HighlighterElementModel,
    domElement: HTMLElement,
    renderer: DomRenderer
  ) => {
    renderBrushLikeDom({
      model,
      domElement,
      renderer,
      color: renderer.getColorValue(
        model.color,
        DefaultTheme.hightlighterColor,
        true
      ),
    });
  }
);
