import { observeResize, useConfirmModal } from '@cloud-notes/component';
import {
  AIChatRuntime,
  createAIRequestService,
  useAIChatElement,
  useAIChatRuntime,
  WorkspaceAIChatSessionStrategy,
} from '@cloud-notes/core/blocksuite/ai';
import { AIChatContent } from '@cloud-notes/core/blocksuite/ai/components/ai-chat-content';
import {
  AIChatTabs,
  AIChatToolbar,
  configureAIChatToolbar,
} from '@cloud-notes/core/blocksuite/ai/components/ai-chat-toolbar';
import { getViewManager } from '@cloud-notes/core/blocksuite/manager/view';
import { NotificationServiceImpl } from '@cloud-notes/core/blocksuite/view-extensions/editor-view/notification-service';
import { useAIChatConfig } from '@cloud-notes/core/components/hooks/cloud-notes/use-ai-chat-config';
import { useAISpecs } from '@cloud-notes/core/components/hooks/cloud-notes/use-ai-specs';
import { useAISubscribe } from '@cloud-notes/core/components/hooks/cloud-notes/use-ai-subscribe';
import {
  AIDraftService,
  AIModelService,
  AIToolsConfigService,
} from '@cloud-notes/core/modules/ai-button';
import {
  EventSourceService,
  GraphQLService,
  ServerService,
  SubscriptionService,
} from '@cloud-notes/core/modules/cloud';
import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { FeatureFlagService } from '@cloud-notes/core/modules/feature-flag';
import { PeekViewService } from '@cloud-notes/core/modules/peek-view';
import { NbstoreService } from '@cloud-notes/core/modules/storage';
import { AppThemeService } from '@cloud-notes/core/modules/theme';
import {
  ViewBody,
  ViewHeader,
  ViewIcon,
  ViewService,
  ViewTitle,
  WorkbenchService,
} from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { RefNodeSlotsProvider } from '@blocksuite/cloud-notes/inlines/reference';
import { BlockStdScope } from '@blocksuite/cloud-notes/std';
import type { Workspace } from '@blocksuite/cloud-notes/store';
import { type Signal, signal } from '@preact/signals-core';
import { useFramework, useService } from '@toeverything/infra';
import { nanoid } from 'nanoid';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import * as styles from './index.css';

function useAIRequestService() {
  const graphqlService = useService(GraphQLService);
  const eventSourceService = useService(EventSourceService);
  const nbstoreService = useService(NbstoreService);
  const workspace = useService(WorkspaceService).workspace;

  return useMemo(
    () =>
      createAIRequestService(
        graphqlService.gql,
        eventSourceService.eventSource,
        nbstoreService.realtime,
        async docIds => {
          await Promise.all(
            [workspace.id, 'db$docProperties', ...docIds].map(docId =>
              workspace.engine.doc.waitForSynced(docId)
            )
          );
        }
      ),
    [graphqlService, eventSourceService, nbstoreService, workspace]
  );
}

function createMockStd(workspace: Workspace) {
  workspace.meta.initialize();
  // just pick a random doc for now
  const store = workspace.docs.values().next().value?.getStore();
  if (!store) return null;
  const std = new BlockStdScope({
    store,
    extensions: [...getViewManager().config.init().value.get('page')],
  });
  std.render();
  return std;
}

function useMockStd() {
  const workspace = useService(WorkspaceService).workspace;
  const std = useMemo(() => {
    if (!workspace) return null;
    return createMockStd(workspace.docCollection);
  }, [workspace]);
  return std;
}

export const Component = () => {
  const t = useI18n();
  const framework = useFramework();
  const [isBodyProvided, setIsBodyProvided] = useState(false);
  const [isHeaderProvided, setIsHeaderProvided] = useState(false);
  const chatContainerRef = useRef<HTMLDivElement>(null);
  const chatToolContainerRef = useRef<HTMLDivElement>(null);
  const chatTabsContainerRef = useRef<HTMLDivElement | null>(null);
  const widthSignalRef = useRef<Signal<number>>(signal(0));
  const requestService = useAIRequestService();
  const workbench = useService(WorkbenchService).workbench;

  const workspaceId = useService(WorkspaceService).workspace.id;

  const runtime = useMemo(
    () =>
      new AIChatRuntime({
        request: requestService,
        scope: { kind: 'workspace', workspaceId },
        strategy: new WorkspaceAIChatSessionStrategy(),
      }),
    [requestService, workspaceId]
  );
  const snapshot = useAIChatRuntime(runtime);
  const session =
    snapshot?.sessions.find(
      session => session.sessionId === snapshot.activeSessionId
    ) ?? null;
  const { docDisplayConfig, searchMenuConfig, reasoningConfig } =
    useAIChatConfig();

  const onOpenDoc = useCallback(
    (docId: string) => {
      workbench.openDoc(docId, { at: 'active' });
    },
    [workbench]
  );
  const onOpenSessionDoc = useCallback(
    (docId: string, sessionId: string) => {
      const { workbench } = framework.get(WorkbenchService);
      const viewService = framework.get(ViewService);
      workbench.open(`/${docId}?sessionId=${sessionId}`, { at: 'active' });
      workbench.openSidebar();
      viewService.view.activeSidebarTab('chat');
    },
    [framework]
  );

  const confirmModal = useConfirmModal();
  const notificationService = useMemo(
    () =>
      new NotificationServiceImpl(
        confirmModal.closeConfirmModal,
        confirmModal.openConfirmModal
      ),
    [confirmModal.closeConfirmModal, confirmModal.openConfirmModal]
  );
  const specs = useAISpecs();
  const mockStd = useMockStd();
  const handleAISubscribe = useAISubscribe();

  const deleteSession = useCallback(
    async (sessionToDelete: BlockSuitePresets.AIRecentSession) => {
      const confirm = await notificationService.confirm({
        title: t['com.cloud-notes.ai.chat-panel.session.delete.confirm.title'](),
        message: t['com.cloud-notes.ai.chat-panel.session.delete.confirm.message'](),
        confirmText: t['Delete'](),
        cancelText: t['Cancel'](),
      });
      if (!confirm) return;
      await runtime.dispatch({
        type: 'deleteSession',
        sessionId: sessionToDelete.sessionId,
      });
      notificationService.toast(
        t['com.cloud-notes.ai.chat-panel.session.delete.toast.success'](),
        {}
      );
    },
    [notificationService, runtime, t]
  );

  useAIChatElement({
    containerRef: chatContainerRef,
    selector: 'ai-chat-content',
    enabled: isBodyProvided,
    createElement: () => new AIChatContent(),
    configureElement: content => {
      content.session = session;
      content.runtime = runtime;
      content.runtimeSnapshot = snapshot;
      content.workspaceId = workspaceId;
      content.extensions = specs;
      content.host = mockStd?.host;
      content.docDisplayConfig = docDisplayConfig;
      content.searchMenuConfig = searchMenuConfig;
      content.reasoningConfig = reasoningConfig;
      content.cloudNotesFeatureFlagService = framework.get(FeatureFlagService);
      content.cloudNotesWorkspaceDialogService = framework.get(
        WorkspaceDialogService
      );
      content.peekViewService = framework.get(PeekViewService);
      content.cloudNotesThemeService = framework.get(AppThemeService);
      content.notificationService = notificationService;
      content.aiDraftService = framework.get(AIDraftService);
      content.aiToolsConfigService = framework.get(AIToolsConfigService);
      content.aiModelService = framework.get(AIModelService);
      content.serverService = framework.get(ServerService);
      content.subscriptionService = framework.get(SubscriptionService);
      content.onAISubscribe = handleAISubscribe;
      content.onOpenDoc = onOpenDoc;
    },
    onElementReady: content => {
      content.independentMode = true;
      content.onboardingOffsetY = -100;
    },
  });

  useAIChatElement({
    containerRef: chatToolContainerRef,
    selector: 'ai-chat-toolbar',
    enabled: isHeaderProvided,
    createElement: () => new AIChatToolbar(),
    configureElement: tool => {
      configureAIChatToolbar(tool, {
        session,
        runtime,
        runtimeSnapshot: snapshot ?? runtime.getSnapshot(),
        docDisplayConfig,
        notificationService,
        onOpenDoc: (docId: string, sessionId: string) => {
          onOpenSessionDoc(docId, sessionId);
        },
        onSessionDelete: (
          sessionToDelete: BlockSuitePresets.AIRecentSession
        ) => {
          deleteSession(sessionToDelete).catch(console.error);
        },
      });
    },
  });

  useEffect(() => {
    const refNodeSlots = mockStd?.getOptional(RefNodeSlotsProvider);
    if (!refNodeSlots) return;
    const sub = refNodeSlots.docLinkClicked.subscribe(event => {
      const { workbench } = framework.get(WorkbenchService);
      workbench.openDoc({
        docId: event.pageId,
        mode: event.params?.mode,
        blockIds: event.params?.blockIds,
        elementIds: event.params?.elementIds,
        refreshKey: nanoid(),
      });
    });
    return () => sub.unsubscribe();
  }, [framework, mockStd]);

  useAIChatElement({
    containerRef: chatTabsContainerRef,
    selector: 'ai-chat-tabs',
    enabled: true,
    createElement: () => new AIChatTabs(),
    configureElement: tabs => {
      tabs.runtime = runtime;
      tabs.runtimeSnapshot = snapshot;
    },
  });

  const onChatContainerRef = useCallback((node: HTMLDivElement) => {
    if (node) {
      setIsBodyProvided(true);
      chatContainerRef.current = node;
      widthSignalRef.current.value = node.clientWidth;
    }
  }, []);

  const onChatToolContainerRef = useCallback((node: HTMLDivElement) => {
    if (node) {
      setIsHeaderProvided(true);
      chatToolContainerRef.current = node;
    }
  }, []);

  const onChatTabsContainerRef = useCallback((node: HTMLDivElement | null) => {
    chatTabsContainerRef.current = node;
  }, []);

  // observe chat container width and provide to ai-chat-content
  useEffect(() => {
    if (!isBodyProvided || !chatContainerRef.current) return;
    return observeResize(chatContainerRef.current, entry => {
      widthSignalRef.current.value = entry.contentRect.width;
    });
  }, [isBodyProvided]);

  return (
    <>
      <ViewTitle title={t['com.cloud-notes.workspaceSubPath.chat']()} />
      <ViewIcon icon="ai" />
      <ViewHeader>
        <div className={styles.chatHeader}>
          <div
            className={styles.chatTabsContainer}
            ref={onChatTabsContainerRef}
          />
          <div ref={onChatToolContainerRef} />
        </div>
      </ViewHeader>
      <ViewBody>
        <div className={styles.chatRoot} ref={onChatContainerRef} />
      </ViewBody>
    </>
  );
};
