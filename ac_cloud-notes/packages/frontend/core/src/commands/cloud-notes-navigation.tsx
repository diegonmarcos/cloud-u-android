import type { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import type { Workspace } from '@blocksuite/cloud-notes/store';
import { ArrowRightBigIcon } from '@blocksuite/icons/rc';

import type { useNavigateHelper } from '../components/hooks/use-navigate-helper';
import type { WorkspaceDialogService } from '../modules/dialogs';
import type { WorkbenchService } from '../modules/workbench';
import { registerCloudNotesCommand } from './registry';

export function registerCloudNotesNavigationCommands({
  t,
  docCollection,
  navigationHelper,
  workspaceDialogService,
  workbenchService,
}: {
  t: ReturnType<typeof useI18n>;
  navigationHelper: ReturnType<typeof useNavigateHelper>;
  docCollection: Workspace;
  workspaceDialogService: WorkspaceDialogService;
  workbenchService?: WorkbenchService;
}) {
  const unsubs: Array<() => void> = [];
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:goto-all-pages',
      category: 'cloud-notes:navigation',
      icon: <ArrowRightBigIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.navigation.goto-all-pages'](),
      run() {
        track.$.cmdk.navigation.navigate({
          to: 'allDocs',
        });

        navigationHelper.jumpToPage(docCollection.id, 'all');
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:goto-collection-list',
      category: 'cloud-notes:navigation',
      icon: <ArrowRightBigIcon />,
      label: 'Go to Collection List',
      run() {
        track.$.cmdk.navigation.navigate({
          to: 'collectionList',
        });

        navigationHelper.jumpToCollections(docCollection.id);
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:goto-tag-list',
      category: 'cloud-notes:navigation',
      icon: <ArrowRightBigIcon />,
      label: 'Go to Tag List',
      run() {
        track.$.cmdk.navigation.navigate({
          to: 'tagList',
        });

        navigationHelper.jumpToTags(docCollection.id);
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:goto-workspace',
      category: 'cloud-notes:navigation',
      icon: <ArrowRightBigIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.navigation.goto-workspace'](),
      run() {
        track.$.cmdk.navigation.navigate({
          to: 'workspace',
        });

        workbenchService?.workbench.openWorkspaceSelector();
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:open-settings',
      category: 'cloud-notes:navigation',
      icon: <ArrowRightBigIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.navigation.open-settings'](),
      keyBinding: '$mod+,',
      run() {
        track.$.cmdk.settings.openSettings();
        workspaceDialogService.open('setting', {
          activeTab: 'appearance',
        });
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:open-account',
      category: 'cloud-notes:navigation',
      icon: <ArrowRightBigIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.navigation.open-account-settings'](),
      run() {
        track.$.cmdk.settings.openSettings({ to: 'account' });
        workspaceDialogService.open('setting', {
          activeTab: 'account',
        });
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:goto-trash',
      category: 'cloud-notes:navigation',
      icon: <ArrowRightBigIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.navigation.goto-trash'](),
      run() {
        track.$.cmdk.navigation.navigate({
          to: 'trash',
        });

        navigationHelper.jumpToPage(docCollection.id, 'trash');
      },
    })
  );

  return () => {
    unsubs.forEach(unsub => unsub());
  };
}
