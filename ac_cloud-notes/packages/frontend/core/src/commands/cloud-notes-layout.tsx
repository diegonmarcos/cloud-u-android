import type { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { SidebarIcon } from '@blocksuite/icons/rc';

import type { AppSidebarService } from '../modules/app-sidebar';
import { registerCloudNotesCommand } from './registry';

export function registerCloudNotesLayoutCommands({
  t,
  appSidebarService,
}: {
  t: ReturnType<typeof useI18n>;
  appSidebarService: AppSidebarService;
}) {
  const unsubs: Array<() => void> = [];
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:toggle-left-sidebar',
      category: 'cloud-notes:layout',
      icon: <SidebarIcon />,
      label: () =>
        appSidebarService.sidebar.open$.value
          ? t['com.cloud-notes.cmdk.cloud-notes.left-sidebar.collapse']()
          : t['com.cloud-notes.cmdk.cloud-notes.left-sidebar.expand'](),

      keyBinding: {
        binding: '$mod+/',
      },
      run() {
        track.$.navigationPanel.$.toggle({
          type: appSidebarService.sidebar.open$.value ? 'collapse' : 'expand',
        });
        appSidebarService.sidebar.toggleSidebar();
      },
    })
  );

  return () => {
    unsubs.forEach(unsub => unsub());
  };
}
