import { uniReactRoot } from '@cloud-notes/component';
import { CloudNotesErrorBoundary } from '@cloud-notes/core/components/cloud-notes/cloud-notes-error-boundary';
import { AiLoginRequiredModal } from '@cloud-notes/core/components/cloud-notes/auth/ai-login-required';
import { SWRConfigProvider } from '@cloud-notes/core/components/providers/swr-config-provider';
import { WorkspaceSideEffects } from '@cloud-notes/core/components/providers/workspace-side-effects';
import {
  DefaultServerService,
  WorkspaceServerService,
} from '@cloud-notes/core/modules/cloud';
import { GlobalContextService } from '@cloud-notes/core/modules/global-context';
import { PeekViewManagerModal } from '@cloud-notes/core/modules/peek-view';
import type {
  Workspace,
  WorkspaceMetadata,
} from '@cloud-notes/core/modules/workspace';
import { WorkspacesService } from '@cloud-notes/core/modules/workspace';
import {
  FrameworkScope,
  LiveData,
  useLiveData,
  useService,
  useServices,
} from '@toeverything/infra';
import {
  type PropsWithChildren,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
} from 'react';
import { map } from 'rxjs';

import { AppFallback } from '../../components/app-fallback';
import { MobileShellHost } from '../../components/mobile-shell-host';
import { WorkspaceDialogs } from '../../dialogs';
import { MobileBackCoordinator } from '../../modules/back-coordinator';

// TODO(@forehalo): reuse the global context with [core/electron]
declare global {
  /**
   * @internal debug only
   */
  // oxlint-disable-next-line no-var
  var currentWorkspace: Workspace | undefined;
  // oxlint-disable-next-line no-var
  var exportWorkspaceSnapshot: (docs?: string[]) => Promise<void>;
  // oxlint-disable-next-line no-var
  var importWorkspaceSnapshot: () => Promise<void>;
  interface WindowEventMap {
    'cloud-notes:workspace:change': CustomEvent<{ id: string }>;
  }
}

export const WorkspaceLayout = ({
  meta,
  children,
}: PropsWithChildren<{ meta: WorkspaceMetadata }>) => {
  // todo: reduce code duplication with packages\frontend\core\src\pages\workspace\index.tsx
  const { workspacesService, globalContextService, defaultServerService } =
    useServices({
      WorkspacesService,
      GlobalContextService,
      DefaultServerService,
    });

  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const workspaceServer = workspace?.scope.get(WorkspaceServerService)?.server;

  useLayoutEffect(() => {
    const ref = workspacesService.open({ metadata: meta });
    setWorkspace(ref.workspace);
    return () => {
      ref.dispose();
    };
  }, [meta, workspacesService]);

  useEffect(() => {
    if (workspace) {
      // for debug purpose
      window.currentWorkspace = workspace ?? undefined;
      window.dispatchEvent(
        new CustomEvent('cloud-notes:workspace:change', {
          detail: {
            id: workspace.id,
          },
        })
      );
      localStorage.setItem('last_workspace_id', workspace.id);
      localStorage.setItem('last_workspace_flavour', workspace.flavour);
      globalContextService.globalContext.workspaceId.set(workspace.id);
      if (workspaceServer) {
        globalContextService.globalContext.serverId.set(workspaceServer.id);
      }
      globalContextService.globalContext.workspaceFlavour.set(
        workspace.flavour
      );
      return () => {
        window.currentWorkspace = undefined;
        globalContextService.globalContext.workspaceId.set(null);
        if (workspaceServer) {
          globalContextService.globalContext.serverId.set(
            defaultServerService.server.id
          );
        }
        globalContextService.globalContext.workspaceFlavour.set(null);
      };
    }
    return;
  }, [
    defaultServerService.server.id,
    globalContextService,
    workspace,
    workspaceServer,
  ]);

  const rootDocReady$ = useMemo(
    () =>
      workspace
        ? LiveData.from(
            workspace.engine.doc
              .docState$(workspace.id)
              .pipe(map(v => v.ready)),
            false
          )
        : null,
    [workspace]
  );
  const isRootDocReady = useLiveData(rootDocReady$) ?? false;

  if (!workspace) {
    return null; // skip this, workspace will be set in layout effect
  }

  if (!isRootDocReady) {
    return <AppFallback />;
  }

  return (
    <FrameworkScope scope={workspaceServer?.scope}>
      <FrameworkScope scope={workspace.scope}>
        <WorkspaceBackReset workspaceId={workspace.id} />
        <CloudNotesErrorBoundary height="100dvh">
          <SWRConfigProvider>
            <MobileShellHost>
              <WorkspaceDialogs />

              {/* ---- some side-effect components ---- */}
              <PeekViewManagerModal />
              <AiLoginRequiredModal />
              <uniReactRoot.Root />
              <WorkspaceSideEffects />
              {children}
            </MobileShellHost>
          </SWRConfigProvider>
        </CloudNotesErrorBoundary>
      </FrameworkScope>
    </FrameworkScope>
  );
};

const WorkspaceBackReset = ({ workspaceId }: { workspaceId: string }) => {
  const coordinator = useService(MobileBackCoordinator);
  useEffect(() => {
    coordinator.reset();
    return () => coordinator.reset();
  }, [coordinator, workspaceId]);
  return null;
};
