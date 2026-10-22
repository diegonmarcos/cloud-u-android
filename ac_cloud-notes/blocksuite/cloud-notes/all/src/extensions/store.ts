import { AttachmentStoreExtension } from '@blocksuite/cloud-notes-block-attachment/store';
import { BookmarkStoreExtension } from '@blocksuite/cloud-notes-block-bookmark/store';
import { CalloutStoreExtension } from '@blocksuite/cloud-notes-block-callout/store';
import { CodeStoreExtension } from '@blocksuite/cloud-notes-block-code/store';
import { DataViewStoreExtension } from '@blocksuite/cloud-notes-block-data-view/store';
import { DatabaseStoreExtension } from '@blocksuite/cloud-notes-block-database/store';
import { DividerStoreExtension } from '@blocksuite/cloud-notes-block-divider/store';
import { EdgelessTextStoreExtension } from '@blocksuite/cloud-notes-block-edgeless-text/store';
import { EmbedStoreExtension } from '@blocksuite/cloud-notes-block-embed/store';
import { EmbedDocStoreExtension } from '@blocksuite/cloud-notes-block-embed-doc/store';
import { FrameStoreExtension } from '@blocksuite/cloud-notes-block-frame/store';
import { ImageStoreExtension } from '@blocksuite/cloud-notes-block-image/store';
import { LatexStoreExtension } from '@blocksuite/cloud-notes-block-latex/store';
import { ListStoreExtension } from '@blocksuite/cloud-notes-block-list/store';
import { NoteStoreExtension } from '@blocksuite/cloud-notes-block-note/store';
import { ParagraphStoreExtension } from '@blocksuite/cloud-notes-block-paragraph/store';
import { RootStoreExtension } from '@blocksuite/cloud-notes-block-root/store';
import { SurfaceStoreExtension } from '@blocksuite/cloud-notes-block-surface/store';
import { SurfaceRefStoreExtension } from '@blocksuite/cloud-notes-block-surface-ref/store';
import { TableStoreExtension } from '@blocksuite/cloud-notes-block-table/store';
import { FoundationStoreExtension } from '@blocksuite/cloud-notes-foundation/store';
import { BrushStoreExtension } from '@blocksuite/cloud-notes-gfx-brush/store';
import { ConnectorStoreExtension } from '@blocksuite/cloud-notes-gfx-connector/store';
import { GroupStoreExtension } from '@blocksuite/cloud-notes-gfx-group/store';
import { MindmapStoreExtension } from '@blocksuite/cloud-notes-gfx-mindmap/store';
import { ShapeStoreExtension } from '@blocksuite/cloud-notes-gfx-shape/store';
import { TextStoreExtension } from '@blocksuite/cloud-notes-gfx-text/store';
import { FootnoteStoreExtension } from '@blocksuite/cloud-notes-inline-footnote/store';
import { LatexStoreExtension as InlineLatexStoreExtension } from '@blocksuite/cloud-notes-inline-latex/store';
import { LinkStoreExtension } from '@blocksuite/cloud-notes-inline-link/store';
import { InlinePresetStoreExtension } from '@blocksuite/cloud-notes-inline-preset/store';
import { ReferenceStoreExtension } from '@blocksuite/cloud-notes-inline-reference/store';

export function getInternalStoreExtensions() {
  return [
    FoundationStoreExtension,

    AttachmentStoreExtension,
    BookmarkStoreExtension,
    CalloutStoreExtension,
    CodeStoreExtension,
    DataViewStoreExtension,
    DatabaseStoreExtension,
    DividerStoreExtension,
    EdgelessTextStoreExtension,
    EmbedStoreExtension,
    EmbedDocStoreExtension,
    FrameStoreExtension,
    ImageStoreExtension,
    LatexStoreExtension,
    ListStoreExtension,
    NoteStoreExtension,
    ParagraphStoreExtension,
    SurfaceRefStoreExtension,
    TableStoreExtension,
    SurfaceStoreExtension,
    RootStoreExtension,

    FootnoteStoreExtension,
    LinkStoreExtension,
    ReferenceStoreExtension,
    InlineLatexStoreExtension,
    InlinePresetStoreExtension,

    BrushStoreExtension,
    ShapeStoreExtension,
    MindmapStoreExtension,
    ConnectorStoreExtension,
    GroupStoreExtension,
    TextStoreExtension,
  ];
}
