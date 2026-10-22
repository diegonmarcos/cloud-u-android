import { createTextActions } from '@blocksuite/cloud-notes-gfx-text';
import { EdgelessTextBlockModel } from '@blocksuite/cloud-notes-model';
import {
  type ToolbarModuleConfig,
  ToolbarModuleExtension,
} from '@blocksuite/cloud-notes-shared/services';
import { BlockFlavourIdentifier } from '@blocksuite/std';

export const edgelessTextToolbarConfig = {
  // No need to adjust element bounds, which updates itself using ResizeObserver
  actions: createTextActions(EdgelessTextBlockModel, 'edgeless-text'),

  when: ctx => ctx.getSurfaceModelsByType(EdgelessTextBlockModel).length > 0,
} as const satisfies ToolbarModuleConfig;

export const edgelessTextToolbarExtension = ToolbarModuleExtension({
  id: BlockFlavourIdentifier('affine:surface:edgeless-text'),
  config: edgelessTextToolbarConfig,
});
