import { getWorkerUrl } from '@cloud-notes/env/worker';
import { CodeLayoutHandlerExtension } from '@blocksuite/cloud-notes/blocks/code';
import { ImageLayoutHandlerExtension } from '@blocksuite/cloud-notes/blocks/image';
import { ListLayoutHandlerExtension } from '@blocksuite/cloud-notes/blocks/list';
import { NoteLayoutHandlerExtension } from '@blocksuite/cloud-notes/blocks/note';
import { ParagraphLayoutHandlerExtension } from '@blocksuite/cloud-notes/blocks/paragraph';
import {
  TurboRendererConfigFactory,
  ViewportTurboRendererExtension,
} from '@blocksuite/cloud-notes/gfx/turbo-renderer';

function createPainterWorker() {
  const worker = new Worker(getWorkerUrl('turbo-painter'));
  return worker;
}

export const turboRendererExtension = [
  ParagraphLayoutHandlerExtension,
  ListLayoutHandlerExtension,
  NoteLayoutHandlerExtension,
  CodeLayoutHandlerExtension,
  ImageLayoutHandlerExtension,
  TurboRendererConfigFactory({
    options: {
      zoomThreshold: 1,
      debounceTime: 1000,
    },
    painterWorkerEntry: createPainterWorker,
  }),
  ViewportTurboRendererExtension,
];
