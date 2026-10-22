import { notify, toast } from '@cloud-notes/component';
import {
  pushGlobalLoadingEventAtom,
  resolveGlobalLoadingEventAtom,
} from '@cloud-notes/component/global-loading';
import {
  AIAppEvents,
  createAIRequestService,
  setupAIProvider,
} from '@cloud-notes/core/blocksuite/ai';
import { useRegisterFindInPageCommands } from '@cloud-notes/core/components/hooks/cloud-notes/use-register-find-in-page-commands';
import { useRegisterWorkspaceCommands } from '@cloud-notes/core/components/hooks/use-register-workspace-commands';
import { OverCapacityNotification } from '@cloud-notes/core/components/over-capacity';
import {
  AuthService,
  EventSourceService,
  GraphQLService,
  RealtimeService,
} from '@cloud-notes/core/modules/cloud';
import {
  GlobalDialogService,
  WorkspaceDialogService,
} from '@cloud-notes/core/modules/dialogs';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { EditorSettingService } from '@cloud-notes/core/modules/editor-setting';
import { useRegisterNavigationCommands } from '@cloud-notes/core/modules/navigation/view/use-register-navigation-commands';
import { QuickSearchContainer } from '@cloud-notes/core/modules/quicksearch';
import { NbstoreService } from '@cloud-notes/core/modules/storage';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import {
  getCloudNotesWorkspaceSchema,
  WorkspaceService,
} from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import track from '@cloud-notes/track';
import type { DocMode } from '@blocksuite/cloud-notes/model';
import { ZipTransformer } from '@blocksuite/cloud-notes/widgets/linked-doc';
import {
  effect,
  fromPromise,
  onStart,
  throwIfAborted,
  useLiveData,
  useService,
  useServices,
} from '@toeverything/infra';
import { useSetAtom } from 'jotai';
import { useEffect } from 'react';
import { catchError, EMPTY, finalize, switchMap, tap, timeout } from 'rxjs';

/**
 * @deprecated just for legacy code, will be removed in the future
 */
export const WorkspaceSideEffects = () => {
  const t = useI18n();
  const pushGlobalLoadingEvent = useSetAtom(pushGlobalLoadingEventAtom);
  const resolveGlobalLoadingEvent = useSetAtom(resolveGlobalLoadingEventAtom);
  const { workspaceService, docsService } = useServices({
    WorkspaceService,
    DocsService,
    EditorSettingService,
  });
  const currentWorkspace = workspaceService.workspace;
  const docsList = docsService.list;

  const workbench = useService(WorkbenchService).workbench;
  useEffect(() => {
    const insertTemplate = effect(
      switchMap(({ template, mode }: { template: string; mode: string }) => {
        return fromPromise(async abort => {
          const templateZip = await fetch(template, { signal: abort });
          const templateBlob = await templateZip.blob();
          throwIfAborted(abort);
          const [doc] = await ZipTransformer.importDocs(
            currentWorkspace.docCollection,
            getCloudNotesWorkspaceSchema(),
            templateBlob
          );
          if (doc) {
            doc.resetHistory();
          }

          return { doc, mode };
        }).pipe(
          timeout(10000 /* 10s */),
          tap(({ mode, doc }) => {
            if (doc) {
              docsList.setPrimaryMode(doc.id, mode as DocMode);
              workbench.openDoc(doc.id);
            }
          }),
          onStart(() => {
            pushGlobalLoadingEvent({
              key: 'insert-template',
            });
          }),
          catchError(err => {
            console.error(err);
            toast(t['com.cloud-notes.ai.template-insert.failed']());
            return EMPTY;
          }),
          finalize(() => {
            resolveGlobalLoadingEvent('insert-template');
          })
        );
      })
    );

    const disposable = AIAppEvents.requestInsertTemplate.subscribe(
      ({ template, mode }) => {
        insertTemplate({ template, mode });
      }
    );

    return () => {
      disposable.unsubscribe();
      insertTemplate.unsubscribe();
    };
  }, [
    currentWorkspace.docCollection,
    docsList,
    pushGlobalLoadingEvent,
    resolveGlobalLoadingEvent,
    t,
    workbench,
  ]);

  const workspaceDialogService = useService(WorkspaceDialogService);
  const globalDialogService = useService(GlobalDialogService);

  useEffect(() => {
    const disposable = AIAppEvents.requestUpgradePlan.subscribe(() => {
      workspaceDialogService.open('setting', {
        activeTab: 'billing',
      });
      track.$.paywall.aiAction.viewPlans();
    });
    return () => {
      disposable.unsubscribe();
    };
  }, [workspaceDialogService]);

  const graphqlService = useService(GraphQLService);
  const eventSourceService = useService(EventSourceService);
  const authService = useService(AuthService);
  const nbstoreService = useService(NbstoreService);
  const realtimeConnectionError = useLiveData(
    useService(RealtimeService).connectionError$
  );

  useEffect(() => {
    if (!realtimeConnectionError) return;
    const message = {
      authentication:
        t['com.cloud-notes.realtime.connection-error.authentication'](),
      network: t['com.cloud-notes.realtime.connection-error.network'](),
      server: t['com.cloud-notes.realtime.connection-error.server'](),
      timeout: t['com.cloud-notes.realtime.connection-error.timeout'](),
    }[realtimeConnectionError.type];
    const id = notify.warning(
      {
        title: t['com.cloud-notes.realtime.connection-error.title'](),
        message,
      },
      { id: `realtime-connection-error:${realtimeConnectionError.endpoint}` }
    );
    return () => {
      notify.dismiss(id);
    };
  }, [realtimeConnectionError, t]);

  useEffect(() => {
    const dispose = setupAIProvider(
      createAIRequestService(
        graphqlService.gql,
        eventSourceService.eventSource,
        nbstoreService.realtime,
        async docIds => {
          await Promise.all(
            [currentWorkspace.id, 'db$docProperties', ...docIds].map(docId =>
              currentWorkspace.engine.doc.waitForSynced(docId)
            )
          );
        }
      ),
      globalDialogService,
      authService
    );
    return () => {
      dispose();
    };
  }, [
    currentWorkspace.engine.doc,
    currentWorkspace.id,
    eventSourceService,
    nbstoreService,
    graphqlService,
    globalDialogService,
    authService,
  ]);

  useRegisterWorkspaceCommands();
  useRegisterNavigationCommands();
  useRegisterFindInPageCommands();

  return (
    <>
      <QuickSearchContainer />
      <OverCapacityNotification />
    </>
  );
};
