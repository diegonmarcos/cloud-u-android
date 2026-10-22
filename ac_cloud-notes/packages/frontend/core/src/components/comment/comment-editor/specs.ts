import { CloudViewExtension } from '@cloud-notes/core/blocksuite/view-extensions/cloud';
import { CloudNotesEditorViewExtension } from '@cloud-notes/core/blocksuite/view-extensions/editor-view/editor-view';
import { CloudNotesThemeViewExtension } from '@cloud-notes/core/blocksuite/view-extensions/theme';
import { I18n } from '@cloud-notes/i18n';
import { CodeBlockViewExtension } from '@blocksuite/cloud-notes/blocks/code/view';
import { DividerViewExtension } from '@blocksuite/cloud-notes/blocks/divider/view';
import { LatexViewExtension as LatexBlockViewExtension } from '@blocksuite/cloud-notes/blocks/latex/view';
import { ListViewExtension } from '@blocksuite/cloud-notes/blocks/list/view';
import { NoteViewExtension } from '@blocksuite/cloud-notes/blocks/note/view';
import { ParagraphViewExtension } from '@blocksuite/cloud-notes/blocks/paragraph/view';
import { RootViewExtension } from '@blocksuite/cloud-notes/blocks/root/view';
import {
  PeekViewExtension,
  type PeekViewService,
} from '@blocksuite/cloud-notes/components/peek';
import {
  type ViewExtensionContext,
  ViewExtensionManager,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes/ext-loader';
import { PlainTextClipboardConfig } from '@blocksuite/cloud-notes/foundation/clipboard';
import { LatexInlineSpecExtension } from '@blocksuite/cloud-notes/inlines/latex';
import { LatexViewExtension as LatexInlineViewExtension } from '@blocksuite/cloud-notes/inlines/latex/view';
import { LinkInlineSpecExtension } from '@blocksuite/cloud-notes/inlines/link';
import { LinkViewExtension } from '@blocksuite/cloud-notes/inlines/link/view';
import { MentionInlineSpecExtension } from '@blocksuite/cloud-notes/inlines/mention';
import { MentionViewExtension } from '@blocksuite/cloud-notes/inlines/mention/view';
import {
  BackgroundInlineSpecExtension,
  BoldInlineSpecExtension,
  CodeInlineSpecExtension,
  ColorInlineSpecExtension,
  InlineSpecExtensions,
  ItalicInlineSpecExtension,
  StrikeInlineSpecExtension,
  UnderlineInlineSpecExtension,
} from '@blocksuite/cloud-notes/inlines/preset';
import { ReferenceInlineSpecExtension } from '@blocksuite/cloud-notes/inlines/reference';
import { ReferenceViewExtension } from '@blocksuite/cloud-notes/inlines/reference/view';
import {
  DefaultOpenDocExtension,
  DocDisplayMetaService,
  DocModeService,
  FileSizeLimitService,
  FontConfigExtension,
  fontConfigSchema,
  FontLoaderService,
  PageViewportServiceExtension,
  ThemeService,
  ToolbarRegistryExtension,
} from '@blocksuite/cloud-notes/shared/services';
import type { CloudNotesTextAttributes } from '@blocksuite/cloud-notes/shared/types';
import { InlineManagerExtension } from '@blocksuite/cloud-notes/std/inline';
import { LinkedDocViewExtension } from '@blocksuite/cloud-notes/widgets/linked-doc/view';
import { ToolbarViewExtension } from '@blocksuite/cloud-notes/widgets/toolbar/view';
import { ViewportOverlayViewExtension } from '@blocksuite/cloud-notes/widgets/viewport-overlay/view';
import type { FrameworkProvider } from '@toeverything/infra';
import { z } from 'zod';

import { createCommentLinkedWidgetConfig } from './linked-widget-config';

const commentEditorViewExtensionOptionsSchema = z.object({
  peekView: z.optional(z.custom<PeekViewService>()),
  fontConfig: z.optional(z.array(fontConfigSchema)),
});

export type CommentEditorViewExtensionOptions = z.infer<
  typeof commentEditorViewExtensionOptionsSchema
>;

class CommentEditorViewExtensionProvider extends ViewExtensionProvider<CommentEditorViewExtensionOptions> {
  override name = 'comment-editor';

  override schema = commentEditorViewExtensionOptionsSchema;

  override setup(
    context: ViewExtensionContext,
    options?: CommentEditorViewExtensionOptions
  ) {
    super.setup(context, options);
    context.register([
      ThemeService,
      DocModeService,
      DocDisplayMetaService,
      DefaultOpenDocExtension,
      FontLoaderService,
      ToolbarRegistryExtension,
      PageViewportServiceExtension,
      FileSizeLimitService,

      ...InlineSpecExtensions,
      InlineManagerExtension<CloudNotesTextAttributes>({
        id: 'DefaultInlineManager',
        specs: [
          BoldInlineSpecExtension.identifier,
          ItalicInlineSpecExtension.identifier,
          UnderlineInlineSpecExtension.identifier,
          StrikeInlineSpecExtension.identifier,
          CodeInlineSpecExtension.identifier,
          BackgroundInlineSpecExtension.identifier,
          ColorInlineSpecExtension.identifier,
          LatexInlineSpecExtension.identifier,
          ReferenceInlineSpecExtension.identifier,
          LinkInlineSpecExtension.identifier,
          MentionInlineSpecExtension.identifier,
        ],
      }),

      PlainTextClipboardConfig,
    ]);

    if (options?.fontConfig) {
      context.register(FontConfigExtension(options.fontConfig));
    }
    if (options?.peekView) {
      context.register(PeekViewExtension(options.peekView));
    }
  }
}

let manager: ViewExtensionManager | null = null;
export function getCommentEditorViewManager(framework: FrameworkProvider) {
  if (!manager) {
    manager = new ViewExtensionManager([
      CommentEditorViewExtensionProvider,

      // Blocks
      CodeBlockViewExtension,
      DividerViewExtension,
      LatexBlockViewExtension,
      ListViewExtension,

      NoteViewExtension,
      ParagraphViewExtension,
      RootViewExtension,

      // Inline
      LinkViewExtension,
      ReferenceViewExtension,
      MentionViewExtension,
      LatexInlineViewExtension,

      // Widget
      ToolbarViewExtension,
      ViewportOverlayViewExtension,
      LinkedDocViewExtension,

      // Cloud Notes side
      CloudNotesThemeViewExtension,
      CloudNotesEditorViewExtension,

      // for rendering mentions
      CloudViewExtension,
    ]);

    manager.configure(ParagraphViewExtension, {
      getPlaceholder: () => {
        return I18n.t('com.cloud-notes.notification.comment-prompt');
      },
    });

    manager.configure(
      LinkedDocViewExtension,
      createCommentLinkedWidgetConfig(framework)
    );

    manager.configure(CloudViewExtension, {
      framework,
      enableCloud: true,
    });
  }
  return manager;
}
