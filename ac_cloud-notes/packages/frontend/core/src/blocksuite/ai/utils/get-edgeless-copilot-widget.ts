import type { EditorHost } from '@blocksuite/cloud-notes/std';

import type { EdgelessCopilotWidget } from '../widgets/edgeless-copilot';
import { CLOUD_NOTES_EDGELESS_COPILOT_WIDGET } from '../widgets/edgeless-copilot/constant';

export function getEdgelessCopilotWidget(
  host: EditorHost
): EdgelessCopilotWidget {
  const rootBlockId = host.store.root?.id as string;
  const copilotWidget = host.view.getWidget(
    CLOUD_NOTES_EDGELESS_COPILOT_WIDGET,
    rootBlockId
  ) as EdgelessCopilotWidget;

  return copilotWidget;
}
