import { ImageLayoutPainterExtension } from '@blocksuite/cloud-notes-block-image/turbo-painter';
import { ListLayoutPainterExtension } from '@blocksuite/cloud-notes-block-list/turbo-painter';
import { NoteLayoutPainterExtension } from '@blocksuite/cloud-notes-block-note/turbo-painter';
import { ParagraphLayoutPainterExtension } from '@blocksuite/cloud-notes-block-paragraph/turbo-painter';
import { ViewportLayoutPainter } from '@blocksuite/cloud-notes-gfx-turbo-renderer/painter';

new ViewportLayoutPainter([
  ParagraphLayoutPainterExtension,
  ListLayoutPainterExtension,
  NoteLayoutPainterExtension,
  ImageLayoutPainterExtension,
]);
