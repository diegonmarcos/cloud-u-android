import { AttachmentViewExtension } from '@blocksuite/cloud-notes-block-attachment/view';
import { BookmarkViewExtension } from '@blocksuite/cloud-notes-block-bookmark/view';
import { CalloutViewExtension } from '@blocksuite/cloud-notes-block-callout/view';
import { CodeBlockViewExtension } from '@blocksuite/cloud-notes-block-code/view';
import { DataViewViewExtension } from '@blocksuite/cloud-notes-block-data-view/view';
import { DatabaseViewExtension } from '@blocksuite/cloud-notes-block-database/view';
import { DividerViewExtension } from '@blocksuite/cloud-notes-block-divider/view';
import { EdgelessTextViewExtension } from '@blocksuite/cloud-notes-block-edgeless-text/view';
import { EmbedViewExtension } from '@blocksuite/cloud-notes-block-embed/view';
import { EmbedDocViewExtension } from '@blocksuite/cloud-notes-block-embed-doc/view';
import { FrameViewExtension } from '@blocksuite/cloud-notes-block-frame/view';
import { ImageViewExtension } from '@blocksuite/cloud-notes-block-image/view';
import { LatexViewExtension } from '@blocksuite/cloud-notes-block-latex/view';
import { ListViewExtension } from '@blocksuite/cloud-notes-block-list/view';
import { NoteViewExtension } from '@blocksuite/cloud-notes-block-note/view';
import { ParagraphViewExtension } from '@blocksuite/cloud-notes-block-paragraph/view';
import { RootViewExtension } from '@blocksuite/cloud-notes-block-root/view';
import { SurfaceViewExtension } from '@blocksuite/cloud-notes-block-surface/view';
import { SurfaceRefViewExtension } from '@blocksuite/cloud-notes-block-surface-ref/view';
import { TableViewExtension } from '@blocksuite/cloud-notes-block-table/view';
import { FoundationViewExtension } from '@blocksuite/cloud-notes-foundation/view';
import { AdapterPanelViewExtension } from '@blocksuite/cloud-notes-fragment-adapter-panel/view';
import { DocTitleViewExtension } from '@blocksuite/cloud-notes-fragment-doc-title/view';
import { FramePanelViewExtension } from '@blocksuite/cloud-notes-fragment-frame-panel/view';
import { OutlineViewExtension } from '@blocksuite/cloud-notes-fragment-outline/view';
import { BrushViewExtension } from '@blocksuite/cloud-notes-gfx-brush/view';
import { ConnectorViewExtension } from '@blocksuite/cloud-notes-gfx-connector/view';
import { GroupViewExtension } from '@blocksuite/cloud-notes-gfx-group/view';
import { LinkViewExtension as GfxLinkViewExtension } from '@blocksuite/cloud-notes-gfx-link/view';
import { MindmapViewExtension } from '@blocksuite/cloud-notes-gfx-mindmap/view';
import { NoteViewExtension as GfxNoteViewExtension } from '@blocksuite/cloud-notes-gfx-note/view';
import { PointerViewExtension } from '@blocksuite/cloud-notes-gfx-pointer/view';
import { ShapeViewExtension } from '@blocksuite/cloud-notes-gfx-shape/view';
import { TemplateViewExtension } from '@blocksuite/cloud-notes-gfx-template/view';
import { TextViewExtension } from '@blocksuite/cloud-notes-gfx-text/view';
import { InlineCommentViewExtension } from '@blocksuite/cloud-notes-inline-comment/view';
import { FootnoteViewExtension } from '@blocksuite/cloud-notes-inline-footnote/view';
import { LatexViewExtension as InlineLatexViewExtension } from '@blocksuite/cloud-notes-inline-latex/view';
import { LinkViewExtension } from '@blocksuite/cloud-notes-inline-link/view';
import { MentionViewExtension } from '@blocksuite/cloud-notes-inline-mention/view';
import { InlinePresetViewExtension } from '@blocksuite/cloud-notes-inline-preset/view';
import { ReferenceViewExtension } from '@blocksuite/cloud-notes-inline-reference/view';
import { DragHandleViewExtension } from '@blocksuite/cloud-notes-widget-drag-handle/view';
import { EdgelessAutoConnectViewExtension } from '@blocksuite/cloud-notes-widget-edgeless-auto-connect/view';
import { EdgelessDraggingAreaViewExtension } from '@blocksuite/cloud-notes-widget-edgeless-dragging-area/view';
import { EdgelessSelectedRectViewExtension } from '@blocksuite/cloud-notes-widget-edgeless-selected-rect/view';
import { EdgelessToolbarViewExtension } from '@blocksuite/cloud-notes-widget-edgeless-toolbar/view';
import { EdgelessZoomToolbarViewExtension } from '@blocksuite/cloud-notes-widget-edgeless-zoom-toolbar/view';
import { FrameTitleViewExtension } from '@blocksuite/cloud-notes-widget-frame-title/view';
import { KeyboardToolbarViewExtension } from '@blocksuite/cloud-notes-widget-keyboard-toolbar/view';
import { LinkedDocViewExtension } from '@blocksuite/cloud-notes-widget-linked-doc/view';
import { NoteSlicerViewExtension } from '@blocksuite/cloud-notes-widget-note-slicer/view';
import { PageDraggingAreaViewExtension } from '@blocksuite/cloud-notes-widget-page-dragging-area/view';
import { RemoteSelectionViewExtension } from '@blocksuite/cloud-notes-widget-remote-selection/view';
import { ScrollAnchoringViewExtension } from '@blocksuite/cloud-notes-widget-scroll-anchoring/view';
import { SlashMenuViewExtension } from '@blocksuite/cloud-notes-widget-slash-menu/view';
import { ToolbarViewExtension } from '@blocksuite/cloud-notes-widget-toolbar/view';
import { ViewportOverlayViewExtension } from '@blocksuite/cloud-notes-widget-viewport-overlay/view';

export function getInternalViewExtensions() {
  return [
    FoundationViewExtension,

    // Gfx
    PointerViewExtension,
    GfxNoteViewExtension,
    BrushViewExtension,
    ShapeViewExtension,
    MindmapViewExtension,
    ConnectorViewExtension,
    GroupViewExtension,
    TextViewExtension,
    TemplateViewExtension,
    GfxLinkViewExtension,

    // Block
    AttachmentViewExtension,
    BookmarkViewExtension,
    CalloutViewExtension,
    CodeBlockViewExtension,
    DataViewViewExtension,
    DatabaseViewExtension,
    DividerViewExtension,
    EdgelessTextViewExtension,
    EmbedViewExtension,
    EmbedDocViewExtension,
    FrameViewExtension,
    ImageViewExtension,
    LatexViewExtension,
    ListViewExtension,
    NoteViewExtension,
    ParagraphViewExtension,
    SurfaceRefViewExtension,
    TableViewExtension,
    SurfaceViewExtension,
    RootViewExtension,

    // Inline
    InlineCommentViewExtension,
    FootnoteViewExtension,
    LinkViewExtension,
    ReferenceViewExtension,
    InlineLatexViewExtension,
    MentionViewExtension,
    InlinePresetViewExtension,

    // Widget
    // order will affect the z-index of the widget
    DragHandleViewExtension,
    EdgelessAutoConnectViewExtension,
    FrameTitleViewExtension,
    KeyboardToolbarViewExtension,
    LinkedDocViewExtension,
    RemoteSelectionViewExtension,
    ScrollAnchoringViewExtension,
    SlashMenuViewExtension,
    ToolbarViewExtension,
    ViewportOverlayViewExtension,
    EdgelessZoomToolbarViewExtension,
    PageDraggingAreaViewExtension,
    EdgelessSelectedRectViewExtension,
    EdgelessDraggingAreaViewExtension,
    NoteSlicerViewExtension,
    EdgelessToolbarViewExtension,

    // Fragment
    DocTitleViewExtension,
    FramePanelViewExtension,
    OutlineViewExtension,
    AdapterPanelViewExtension,
  ];
}
