import type {
  MermaidRenderRequest,
  MermaidRenderResult,
} from '@cloud-notes/core/modules/mermaid/renderer';
import type {
  TypstRenderRequest,
  TypstRenderResult,
} from '@cloud-notes/core/modules/typst/renderer';

type NativePreviewHandlers = {
  renderMermaidSvg?: (
    request: MermaidRenderRequest
  ) => Promise<MermaidRenderResult>;
  renderTypstSvg?: (request: TypstRenderRequest) => Promise<TypstRenderResult>;
};

let enableMermaidWasmNativeRenderer =
  BUILD_CONFIG.isIOS || BUILD_CONFIG.isAndroid;
let nativePreviewHandlers: NativePreviewHandlers | null = null;

export function setMermaidWasmNativeRendererEnabled(enabled: boolean) {
  enableMermaidWasmNativeRenderer = enabled;
}

export function isMermaidWasmNativeRendererEnabled() {
  return enableMermaidWasmNativeRenderer;
}

export function registerNativePreviewHandlers(
  handlers: NativePreviewHandlers | null
) {
  nativePreviewHandlers = handlers;
}

export function getNativePreviewHandlers() {
  return nativePreviewHandlers;
}

export type { NativePreviewHandlers };
