import { AppSidebarService } from '@cloud-notes/core/modules/app-sidebar';
import { DesktopApiService } from '@cloud-notes/core/modules/desktop-api';
import {
  GlobalDialogService,
  WorkspaceDialogService,
} from '@cloud-notes/core/modules/dialogs';
import { I18nService } from '@cloud-notes/core/modules/i18n';
import { UrlService } from '@cloud-notes/core/modules/url';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import {
  useService,
  useServiceOptional,
  useServices,
} from '@toeverything/infra';
import { useStore } from 'jotai';
import { useTheme } from 'next-themes';
import { useEffect } from 'react';

import { usePageHelper } from '../../blocksuite/block-suite-page-list/utils';
import {
  PreconditionStrategy,
  registerCloudNotesCommand,
  registerCloudNotesCreationCommands,
  registerCloudNotesHelpCommands,
  registerCloudNotesLanguageCommands,
  registerCloudNotesLayoutCommands,
  registerCloudNotesNavigationCommands,
  registerCloudNotesSettingsCommands,
  registerCloudNotesUpdatesCommands,
} from '../../commands';
import { EditorSettingService } from '../../modules/editor-setting';
import { CMDKQuickSearchService } from '../../modules/quicksearch/services/cmdk';
import { useNavigateHelper } from './use-navigate-helper';

function registerCMDKCommand(service: CMDKQuickSearchService) {
  return registerCloudNotesCommand({
    id: 'cloud-notes:show-quick-search',
    preconditionStrategy: PreconditionStrategy.Never,
    category: 'cloud-notes:general',
    keyBinding: {
      binding: '$mod+K',
    },
    label: '',
    icon: '',
    run() {
      service.toggle();
    },
  });
}

export function useRegisterWorkspaceCommands() {
  const store = useStore();
  const t = useI18n();
  const theme = useTheme();
  const currentWorkspace = useService(WorkspaceService).workspace;
  const urlService = useService(UrlService);
  const pageHelper = usePageHelper(currentWorkspace.docCollection);
  const navigationHelper = useNavigateHelper();
  const {
    cMDKQuickSearchService,
    editorSettingService,
    workspaceDialogService,
    globalDialogService,
    appSidebarService,
    i18nService,
  } = useServices({
    CMDKQuickSearchService,
    EditorSettingService,
    WorkspaceDialogService,
    GlobalDialogService,
    AppSidebarService,
    I18nService,
  });

  const i18n = i18nService.i18n;

  const desktopApiService = useServiceOptional(DesktopApiService);
  const workbenchService = useServiceOptional(WorkbenchService);

  const quitAndInstall = desktopApiService?.handler.updater.quitAndInstall;

  useEffect(() => {
    const unsub = registerCMDKCommand(cMDKQuickSearchService);

    return () => {
      unsub();
    };
  }, [cMDKQuickSearchService]);

  // register CloudNotesUpdatesCommands
  useEffect(() => {
    if (!quitAndInstall) {
      return;
    }

    const unsub = registerCloudNotesUpdatesCommands({
      store,
      t,
      quitAndInstall,
    });

    return () => {
      unsub();
    };
  }, [quitAndInstall, store, t]);

  // register CloudNotesNavigationCommands
  useEffect(() => {
    const unsub = registerCloudNotesNavigationCommands({
      t,
      docCollection: currentWorkspace.docCollection,
      navigationHelper,
      workspaceDialogService,
      workbenchService,
    });

    return () => {
      unsub();
    };
  }, [
    store,
    t,
    currentWorkspace.docCollection,
    navigationHelper,
    globalDialogService,
    workspaceDialogService,
    workbenchService,
  ]);

  // register CloudNotesSettingsCommands
  useEffect(() => {
    const unsub = registerCloudNotesSettingsCommands({
      store,
      t,
      theme,
      editorSettingService,
    });

    return () => {
      unsub();
    };
  }, [editorSettingService, store, t, theme]);

  useEffect(() => {
    const unsub = registerCloudNotesLanguageCommands({
      i18n,
      t,
    });

    return () => {
      unsub();
    };
  }, [i18n, t]);

  // register CloudNotesLayoutCommands
  useEffect(() => {
    const unsub = registerCloudNotesLayoutCommands({ t, appSidebarService });

    return () => {
      unsub();
    };
  }, [appSidebarService, store, t]);

  // register CloudNotesCreationCommands
  useEffect(() => {
    const unsub = registerCloudNotesCreationCommands({
      globalDialogService,
      pageHelper: pageHelper,
      t,
    });

    return () => {
      unsub();
    };
  }, [store, pageHelper, t, globalDialogService]);

  // register CloudNotesHelpCommands
  useEffect(() => {
    const unsub = registerCloudNotesHelpCommands({
      t,
      urlService,
      workspaceDialogService,
    });

    return () => {
      unsub();
    };
  }, [t, globalDialogService, urlService, workspaceDialogService]);
}
