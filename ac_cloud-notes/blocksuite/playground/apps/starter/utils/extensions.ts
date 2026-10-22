import { RefNodeSlotsProvider } from '@blocksuite/cloud-notes/inlines/reference';
import {
  CommunityCanvasTextFonts,
  DocModeProvider,
  EditorSettingExtension,
  FeatureFlagService,
  FontConfigExtension,
  ParseDocUrlExtension,
} from '@blocksuite/cloud-notes/shared/services';
import type { ExtensionType, Store, Workspace } from '@blocksuite/cloud-notes/store';
import { type TestCloudNotesEditorContainer } from '@blocksuite/integration-test';
import { getTestViewManager } from '@blocksuite/integration-test/view';

import {
  mockDocModeService,
  mockEditorSetting,
  mockParseDocUrlService,
} from '../../_common/mock-services';

const viewManager = getTestViewManager();

export function getTestCommonExtensions(
  editor: TestCloudNotesEditorContainer
): ExtensionType[] {
  return [
    FontConfigExtension(CommunityCanvasTextFonts),
    EditorSettingExtension({
      setting$: mockEditorSetting(),
    }),
    ParseDocUrlExtension(mockParseDocUrlService(editor.doc.workspace)),
    {
      setup: di => {
        di.override(DocModeProvider, mockDocModeService(editor));
      },
    },
    // CommentProviderExtension(mockCommentProvider()),
  ];
}

export function createTestEditor(store: Store, workspace: Workspace) {
  store
    .get(FeatureFlagService)
    .setFlag('enable_advanced_block_visibility', true);

  const editor = document.createElement('cloud-notes-editor-container');

  editor.autofocus = true;
  editor.doc = store;

  const defaultExtensions = getTestCommonExtensions(editor);
  editor.pageSpecs = [...viewManager.get('page'), ...defaultExtensions];
  editor.edgelessSpecs = [...viewManager.get('edgeless'), ...defaultExtensions];

  editor.std
    .get(RefNodeSlotsProvider)
    .docLinkClicked.subscribe(({ pageId: docId }) => {
      const target = workspace.getDoc(docId)?.getStore();
      if (!target) {
        throw new Error(`Failed to jump to doc ${docId}`);
      }
      target.load();
      editor.doc = target;
    });

  return editor;
}
