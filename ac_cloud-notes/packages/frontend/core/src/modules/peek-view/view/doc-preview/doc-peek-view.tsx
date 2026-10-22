import { Scrollable } from '@cloud-notes/component';
import { PageDetailLoading } from '@cloud-notes/component/page-detail-skeleton';
import { AIAppEvents, type AIChatParams } from '@cloud-notes/core/blocksuite/ai';
import type { CloudNotesEditorContainer } from '@cloud-notes/core/blocksuite/block-suite-editor';
import { EditorOutlineViewer } from '@cloud-notes/core/blocksuite/outline-viewer';
import { CloudNotesErrorBoundary } from '@cloud-notes/core/components/cloud-notes/cloud-notes-error-boundary';
import { useGuard } from '@cloud-notes/core/components/guard';
import { PageNotFound } from '@cloud-notes/core/desktop/pages/404';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { DebugLogger } from '@cloud-notes/debug';
import { DisposableGroup } from '@blocksuite/cloud-notes/global/disposable';
import { Bound } from '@blocksuite/cloud-notes/global/gfx';
import { RefNodeSlotsProvider } from '@blocksuite/cloud-notes/inlines/reference';
import { GfxControllerIdentifier } from '@blocksuite/cloud-notes/std/gfx';
import {
  FrameworkScope,
  useLiveData,
  useService,
  useServices,
} from '@toeverything/infra';
import clsx from 'clsx';
import { lazy, Suspense, useCallback, useEffect } from 'react';
import type { Subscription } from 'rxjs';

import { WorkbenchService } from '../../../workbench/services/workbench';
import type { DocReferenceInfo } from '../../entities/peek-view';
import { PeekViewService } from '../../services/peek-view';
import { useEditor } from '../utils';
import * as styles from './doc-peek-view.css';

const logger = new DebugLogger('doc-peek-view');

// Lazy load BlockSuiteEditor to break circular dependency
const BlockSuiteEditor = lazy(() =>
  import('@cloud-notes/core/blocksuite/block-suite-editor').then(module => ({
    default: module.BlockSuiteEditor,
  }))
);

function fitViewport(
  editor: CloudNotesEditorContainer,
  xywh?: `[${number},${number},${number},${number}]`
) {
  try {
    if (!editor.host) {
      throw new Error('editor host is not ready');
    }

    const gfx = editor.host.std.get(GfxControllerIdentifier);
    const viewport = gfx.viewport;
    viewport.onResize();

    if (xywh) {
      const newViewport = {
        xywh: xywh,
        padding: [60, 20, 20, 20] as [number, number, number, number],
      };
      viewport.setViewportByBound(
        Bound.deserialize(newViewport.xywh),
        newViewport.padding,
        false
      );
    } else {
      gfx.fitToScreen({
        smooth: false,
      });
    }
  } catch (e) {
    logger.warn('failed to fitViewPort', e);
  }
}

function DocPeekPreviewEditor({
  xywh,
}: {
  xywh?: `[${number},${number},${number},${number}]`;
}) {
  const { editorService } = useServices({
    EditorService,
  });
  const editor = editorService.editor;
  const doc = editor.doc;
  const workspace = editor.doc.workspace;
  const mode = useLiveData(editor.mode$);
  const defaultOpenProperty = useLiveData(editor.defaultOpenProperty$);
  const workbench = useService(WorkbenchService).workbench;
  const peekView = useService(PeekViewService).peekView;
  const editorElement = useLiveData(editor.editorContainer$);

  const isInTrash = useLiveData(doc.record.trash$);

  const handleOnEditorReady = useCallback(
    (editorContainer: CloudNotesEditorContainer) => {
      const disposableGroup = new DisposableGroup();
      const refNodeSlots =
        editorContainer.std.getOptional(RefNodeSlotsProvider);
      if (!refNodeSlots) return;
      // doc change event inside peek view should be handled by peek view
      disposableGroup.add(
        // todo(@pengx17): seems not working
        refNodeSlots.docLinkClicked.subscribe(options => {
          if (options.host !== editorContainer.host) {
            return;
          }
          peekView
            .open({
              docRef: { docId: options.pageId },
              ...options.params,
            })
            .catch(console.error);
        })
      );

      const unbind = editor.bindEditorContainer(editorContainer);

      if (mode === 'edgeless') {
        fitViewport(editorContainer, xywh);
      }

      return () => {
        unbind();
        disposableGroup.dispose();
      };
    },
    [editor, mode, peekView, xywh]
  );

  useEffect(() => {
    const disposables: Subscription[] = [];
    const openHandler = (params: AIChatParams | null) => {
      if (!params) {
        return;
      }
      if (doc) {
        workbench.openDoc(doc.id);
        peekView.close();
        // chat panel open is already handled in <DetailPageImpl />
      }
    };
    disposables.push(AIAppEvents.requestOpenWithChat.subscribe(openHandler));
    disposables.push(AIAppEvents.requestSendWithChat.subscribe(openHandler));
    return () => disposables.forEach(d => d.unsubscribe());
  }, [doc, peekView, workbench, workspace.id]);

  const openOutlinePanel = useCallback(() => {
    workbench.openDoc(doc.id);
    workbench.openSidebar();
    workbench.activeView$.value.activeSidebarTab('outline');
    peekView.close();
  }, [doc, peekView, workbench]);

  const canEdit = useGuard('Doc_Update', doc.id);

  const readonly = !canEdit || isInTrash;

  return (
    <CloudNotesErrorBoundary>
      <Scrollable.Root>
        <Scrollable.Viewport
          className={clsx('cloud-notes-page-viewport', styles.cloudNotesDocViewport)}
        >
          <Suspense fallback={<PageDetailLoading />}>
            <BlockSuiteEditor
              className={styles.editor}
              mode={mode}
              page={doc.blockSuiteDoc}
              readonly={readonly}
              onEditorReady={handleOnEditorReady}
              defaultOpenProperty={defaultOpenProperty}
            />
          </Suspense>
        </Scrollable.Viewport>
        <Scrollable.Scrollbar />
      </Scrollable.Root>
      {!BUILD_CONFIG.isMobileEdition && !BUILD_CONFIG.isMobileWeb ? (
        <EditorOutlineViewer
          editor={editorElement?.host ?? null}
          show={mode === 'page'}
          openOutlinePanel={openOutlinePanel}
        />
      ) : null}
    </CloudNotesErrorBoundary>
  );
}

export function DocPeekPreview({
  docRef,
  animating,
}: {
  docRef: DocReferenceInfo;
  animating?: boolean;
}) {
  const {
    docId,
    blockIds,
    elementIds,
    mode,
    xywh,
    databaseId,
    databaseDocId,
    databaseRowId,
  } = docRef;
  const { doc, editor, loading } = useEditor(
    docId,
    mode,
    {
      blockIds,
      elementIds,
    },
    databaseId && databaseRowId && databaseDocId
      ? {
          docId: databaseDocId,
          databaseId,
          databaseRowId,
          type: 'database',
        }
      : undefined,
    !animating
  );

  const canAccess = useGuard('Doc_Read', docId);

  // if sync engine has been synced and the page is null, show 404 page.
  if (!doc || !editor || !canAccess) {
    return loading || canAccess === undefined ? (
      <PageDetailLoading key="current-page-is-null" />
    ) : (
      <PageNotFound noPermission />
    );
  }

  return (
    <FrameworkScope scope={doc.scope}>
      <FrameworkScope scope={editor.scope}>
        <DocPeekPreviewEditor xywh={xywh} />
      </FrameworkScope>
    </FrameworkScope>
  );
}
