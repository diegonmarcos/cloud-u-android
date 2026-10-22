import { getMermaidRenderer } from '@cloud-notes/core/modules/mermaid/renderer';
import { getTypstRenderer } from '@cloud-notes/core/modules/typst/renderer';

import { renderClassicMermaidSvg } from './classic-mermaid';
import { isMermaidWasmNativeRendererEnabled } from './runtime-config';
import type { PreviewRenderRequestMap, PreviewRenderResultMap } from './types';

export async function renderMermaidSvgBackend(
  request: PreviewRenderRequestMap['mermaid']
): Promise<PreviewRenderResultMap['mermaid']> {
  if (!isMermaidWasmNativeRendererEnabled()) {
    return renderClassicMermaidSvg(request);
  }

  return getMermaidRenderer().render(request);
}

export async function renderTypstSvgBackend(
  request: PreviewRenderRequestMap['typst']
): Promise<PreviewRenderResultMap['typst']> {
  return getTypstRenderer().render(request);
}
