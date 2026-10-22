import { ToolbarModuleExtension } from '@blocksuite/cloud-notes-shared/services';
import { BlockFlavourIdentifier } from '@blocksuite/std';

import { builtinInlineLinkToolbarConfig } from './link-node/configs/toolbar.js';

export const linkToolbar = ToolbarModuleExtension({
  id: BlockFlavourIdentifier('cloud-notes:link'),
  config: builtinInlineLinkToolbarConfig,
});
