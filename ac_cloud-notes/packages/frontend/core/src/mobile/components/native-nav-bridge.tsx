import { notify } from '@affine/component';
import { usePageHelper } from '@affine/core/blocksuite/block-suite-page-list/utils';
import { WorkbenchService } from '@affine/core/modules/workbench';
import { WorkspaceService } from '@affine/core/modules/workspace';
import { useService } from '@toeverything/infra';
import { useEffect } from 'react';

import {
  NATIVE_NAV_EVENT,
  type NativeNavDetail,
  resolveNativeNav,
} from './native-nav-routes';

/**
 * Receives the Compose shell's island / page-strip taps (a `cloud-notes:nav`
 * window event, see MainActivity.dispatchNav) and drives the mobile workbench.
 * Rendered inside the workspace scope, so service lookups are legal here
 * (test-root-scope-services); the native side never sees a URL.
 */
export const NativeNavBridge = () => {
  const workbench = useService(WorkbenchService).workbench;
  const workspace = useService(WorkspaceService).workspace;
  const pageHelper = usePageHelper(workspace.docCollection);

  useEffect(() => {
    const listener = (e: Event) => {
      const detail = (e as CustomEvent<NativeNavDetail>).detail;
      const target = detail ? resolveNativeNav(detail) : null;
      if (!target) return;
      switch (target.kind) {
        case 'route':
          workbench.open(target.to);
          break;
        case 'action': {
          const doc =
            target.action === 'new-board'
              ? pageHelper.createEdgeless({ show: false })
              : pageHelper.createPage(undefined, { show: false });
          workbench.openDoc(doc.id);
          break;
        }
        case 'planned':
          notify.warning({
            title: `${target.what} is coming`,
            message: `Planned for roadmap phase ${target.phase}.`,
          });
          break;
      }
    };
    window.addEventListener(NATIVE_NAV_EVENT, listener);
    return () => window.removeEventListener(NATIVE_NAV_EVENT, listener);
  }, [pageHelper, workbench]);

  return null;
};
