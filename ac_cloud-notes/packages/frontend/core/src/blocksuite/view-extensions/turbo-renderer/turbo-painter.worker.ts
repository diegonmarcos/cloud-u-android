import { CodeLayoutPainterExtension } from '@blocksuite/cloud-notes/blocks/code';
import { ImageLayoutPainterExtension } from '@blocksuite/cloud-notes/blocks/image';
import { ListLayoutPainterExtension } from '@blocksuite/cloud-notes/blocks/list';
import { NoteLayoutPainterExtension } from '@blocksuite/cloud-notes/blocks/note';
import { ParagraphLayoutPainterExtension } from '@blocksuite/cloud-notes/blocks/paragraph';
import { ViewportLayoutPainter } from '@blocksuite/cloud-notes/gfx/turbo-renderer';

new ViewportLayoutPainter([
  ParagraphLayoutPainterExtension,
  ListLayoutPainterExtension,
  NoteLayoutPainterExtension,
  CodeLayoutPainterExtension,
  ImageLayoutPainterExtension,
]);
