import { uniReactRoot } from '@cloud-notes/component';
import { AiLoginRequiredModal } from '@cloud-notes/core/components/cloud-notes/auth/ai-login-required';
import { useResponsiveSidebar } from '@cloud-notes/core/components/hooks/use-responsive-siedebar';
import { SWRConfigProvider } from '@cloud-notes/core/components/providers/swr-config-provider';
import { WorkspaceSideEffects } from '@cloud-notes/core/components/providers/workspace-side-effects';
import { AIIsland } from '@cloud-notes/core/desktop/components/ai-island';
import { AppContainer } from '@cloud-notes/core/desktop/components/app-container';
import { DocumentTitle } from '@cloud-notes/core/desktop/components/document-title';
import { WorkspaceDialogs } from '@cloud-notes/core/desktop/dialogs';
import { PeekViewManagerModal } from '@cloud-notes/core/modules/peek-view';
import { QuotaCheck } from '@cloud-notes/core/modules/quota';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { LiveData, useLiveData, useService } from '@toeverything/infra';
import type { PropsWithChildren } from 'react';

export const WorkspaceLayout = function WorkspaceLayout({
  children,
}: PropsWithChildren) {
  const currentWorkspace = useService(WorkspaceService).workspace;
  return (
    <SWRConfigProvider>
      <WorkspaceDialogs />

      {/* ---- some side-effect components ---- */}
      {currentWorkspace?.flavour !== 'local' ? (
        <QuotaCheck workspaceMeta={currentWorkspace.meta} />
      ) : null}
      <AiLoginRequiredModal />
      <WorkspaceSideEffects />
      <PeekViewManagerModal />
      <DocumentTitle />

      <WorkspaceLayoutInner>{children}</WorkspaceLayoutInner>
      {/* should show after workspace loaded */}
      {/* FIXME: wait for better ai, <WorkspaceAIOnboarding /> */}
      <AIIsland />
      <uniReactRoot.Root />
    </SWRConfigProvider>
  );
};

/**
 * Wraps the workspace layout main router view
 */
const WorkspaceLayoutUIContainer = ({ children }: PropsWithChildren) => {
  const workbench = useService(WorkbenchService).workbench;
  const currentPath = useLiveData(
    LiveData.computed(get => {
      return get(workbench.basename$) + get(workbench.location$).pathname;
    })
  );
  useResponsiveSidebar();

  return (
    <AppContainer data-current-path={currentPath}>{children}</AppContainer>
  );
};
const WorkspaceLayoutInner = ({ children }: PropsWithChildren) => {
  return <WorkspaceLayoutUIContainer>{children}</WorkspaceLayoutUIContainer>;
};
