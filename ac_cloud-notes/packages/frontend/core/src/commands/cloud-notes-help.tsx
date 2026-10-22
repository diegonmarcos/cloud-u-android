import type { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { ContactWithUsIcon, NewIcon } from '@blocksuite/icons/rc';

import type { WorkspaceDialogService } from '../modules/dialogs';
import type { UrlService } from '../modules/url';
import { registerCloudNotesCommand } from './registry';

export function registerCloudNotesHelpCommands({
  t,
  urlService,
  workspaceDialogService,
}: {
  t: ReturnType<typeof useI18n>;
  urlService: UrlService;
  workspaceDialogService: WorkspaceDialogService;
}) {
  const unsubs: Array<() => void> = [];
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:help-whats-new',
      category: 'cloud-notes:help',
      icon: <NewIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.whats-new'](),
      run() {
        track.$.cmdk.help.openChangelog();
        urlService.openPopupWindow(BUILD_CONFIG.changelogUrl);
      },
    })
  );
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:help-contact-us',
      category: 'cloud-notes:help',
      icon: <ContactWithUsIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.contact-us'](),
      run() {
        track.$.cmdk.help.contactUs();
        workspaceDialogService.open('setting', {
          activeTab: 'about',
        });
      },
    })
  );

  return () => {
    unsubs.forEach(unsub => unsub());
  };
}
