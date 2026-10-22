import type { ConfirmModalProps, ElementOrFactory } from '@cloud-notes/component';
import {
  CloudNotesPageReference,
  CloudNotesSharedPageReference,
} from '@cloud-notes/core/components/cloud-notes/reference-link';
import { DocService, DocsService } from '@cloud-notes/core/modules/doc';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { toDocSearchParams } from '@cloud-notes/core/modules/navigation';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes/ext-loader';
import { FrameworkProvider } from '@toeverything/infra';
import type { TemplateResult } from 'lit';
import { z } from 'zod';

import { patchForAudioEmbedView } from './audio/audio-view';
import { buildDocDisplayMetaExtension } from './display-meta';
import { patchDocModeService } from './doc-mode-service';
import { patchDocUrlExtensions } from './doc-url';
import { patchFileSizeLimitExtension } from './file-size-limit';
import { patchNotificationService } from './notification-service';
import { patchOpenDocExtension } from './open-doc';
import { patchQuickSearchService } from './quick-search-service';
import {
  patchReferenceRenderer,
  type ReferenceReactRenderer,
} from './reference-renderer';
import { patchSideBarService } from './side-bar-service';

const optionsSchema = z.object({
  // services
  framework: z.instanceof(FrameworkProvider),

  // react renderer
  reactToLit: z
    .function()
    .args(z.custom<ElementOrFactory>(), z.boolean().optional())
    .returns(z.custom<TemplateResult>()),
  confirmModal: z.object({
    openConfirmModal: z
      .function()
      .args(z.custom<ConfirmModalProps>().optional(), z.any().optional()),
    closeConfirmModal: z.function(),
  }),

  scope: z.enum(['doc', 'workspace']).optional(),
});

export type CloudNotesEditorViewOptions = z.infer<typeof optionsSchema>;

export class CloudNotesEditorViewExtension extends ViewExtensionProvider<CloudNotesEditorViewOptions> {
  override name = 'cloud-notes-editor-view';

  override schema = optionsSchema;

  private readonly _getCustomReferenceRenderer = (
    framework: FrameworkProvider
  ): ReferenceReactRenderer => {
    const workspaceService = framework.get(WorkspaceService);
    return function customReference(reference) {
      const data = reference.delta.attributes?.reference;
      if (!data) return <span />;

      const pageId = data.pageId;
      if (!pageId) return <span />;

      // title alias
      const title = data.title;
      const params = toDocSearchParams(data.params);

      if (workspaceService.workspace.openOptions.isSharedMode) {
        return (
          <CloudNotesSharedPageReference
            docCollection={workspaceService.workspace.docCollection}
            pageId={pageId}
            params={params}
            title={title}
          />
        );
      }

      return (
        <CloudNotesPageReference pageId={pageId} params={params} title={title} />
      );
    };
  };

  override setup(
    context: ViewExtensionContext,
    options?: CloudNotesEditorViewOptions
  ) {
    super.setup(context, options);
    if (!options) {
      return;
    }
    const { framework, reactToLit, confirmModal, scope = 'doc' } = options;

    const referenceRenderer = this._getCustomReferenceRenderer(framework);

    context
      .register([
        patchReferenceRenderer(reactToLit, referenceRenderer),
        patchNotificationService(confirmModal),
        patchOpenDocExtension(),
        patchSideBarService(framework),
        patchFileSizeLimitExtension(framework),
        buildDocDisplayMetaExtension(framework),
        patchForAudioEmbedView(reactToLit),
      ])
      .register(patchDocUrlExtensions(framework))
      .register(patchQuickSearchService(framework));

    if (scope === 'doc') {
      const docService = framework.get(DocService);
      const docsService = framework.get(DocsService);
      const editorService = framework.get(EditorService);
      context.register([
        patchDocModeService(docService, docsService, editorService),
      ]);
    }
  }
}
