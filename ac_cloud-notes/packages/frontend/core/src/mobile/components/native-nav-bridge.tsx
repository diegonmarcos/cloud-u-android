import { notify } from '@affine/component';
import { usePageHelper } from '@affine/core/blocksuite/block-suite-page-list/utils';
import { WorkspaceDialogService } from '@affine/core/modules/dialogs';
import { DocsService } from '@affine/core/modules/doc';
import { WorkbenchService } from '@affine/core/modules/workbench';
import { WorkspaceService } from '@affine/core/modules/workspace';
import { LiveData, useLiveData, useService } from '@toeverything/infra';
import { useEffect, useMemo } from 'react';

import {
  type CloudNotesHost,
  drawerDocs,
  NATIVE_NAV_EVENT,
  NATIVE_OPEN_DOC_EVENT,
  NATIVE_OPEN_SETTINGS_EVENT,
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
  const dialogs = useService(WorkspaceDialogService);
  const docList = useService(DocsService).list;

  // The top bar's Settings > Configs and a page picked in its drawer.
  useEffect(() => {
    const openSettings = () =>
      dialogs.open('setting', { activeTab: 'appearance' });
    const openDoc = (e: Event) => {
      const id = (e as CustomEvent<{ id?: string }>).detail?.id;
      if (id) workbench.openDoc(id);
    };
    window.addEventListener(NATIVE_OPEN_SETTINGS_EVENT, openSettings);
    window.addEventListener(NATIVE_OPEN_DOC_EVENT, openDoc);
    return () => {
      window.removeEventListener(NATIVE_OPEN_SETTINGS_EVENT, openSettings);
      window.removeEventListener(NATIVE_OPEN_DOC_EVENT, openDoc);
    };
  }, [dialogs, workbench]);

  // The drawer's page list: the native side cannot see the workspace, so the
  // web half pushes it whenever a title or a trash flag changes.
  const metas = useLiveData(
    useMemo(
      () =>
        LiveData.computed(get =>
          get(docList.docs$).map(d => ({ id: d.id, ...get(d.meta$) }))
        ),
      [docList]
    )
  );
  useEffect(() => {
    const host = (window as unknown as { CloudNotesHost?: CloudNotesHost })
      .CloudNotesHost;
    host?.setDocs(JSON.stringify(drawerDocs(metas)));
  }, [metas]);

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
