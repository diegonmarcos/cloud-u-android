import {
  createCustomToolbarExtension,
  createToolbarMoreMenuConfig,
} from '@cloud-notes/core/blocksuite/view-extensions/editor-config/toolbar';
import { WorkspaceServerService } from '@cloud-notes/core/modules/cloud';
import { EditorSettingService } from '@cloud-notes/core/modules/editor-setting';
import { ToolbarMoreMenuConfigExtension } from '@blocksuite/cloud-notes/components/toolbar';
import { EditorSettingExtension } from '@blocksuite/cloud-notes/shared/services';
import type { ExtensionType } from '@blocksuite/store';
import type { FrameworkProvider } from '@toeverything/infra';

export function getEditorConfigExtension(
  framework: FrameworkProvider
): ExtensionType[] {
  const editorSettingService = framework.get(EditorSettingService);
  const workspaceServerService = framework.get(WorkspaceServerService);
  const baseUrl = workspaceServerService.server?.baseUrl ?? location.origin;

  return [
    EditorSettingExtension({
      setting$: editorSettingService.editorSetting.settingSignal,
      set: (k, v) => editorSettingService.editorSetting.set(k, v),
    }),
    ToolbarMoreMenuConfigExtension(createToolbarMoreMenuConfig(framework)),

    createCustomToolbarExtension(editorSettingService.editorSetting, baseUrl),
  ].flat();
}
