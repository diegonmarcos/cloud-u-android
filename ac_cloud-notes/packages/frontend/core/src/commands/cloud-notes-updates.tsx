import { notify } from '@cloud-notes/component';
import { updateReadyAtom } from '@cloud-notes/core/components/hooks/use-app-updater';
import type { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { ResetIcon } from '@blocksuite/icons/rc';
import type { createStore } from 'jotai';

import { registerCloudNotesCommand } from './registry';

export function registerCloudNotesUpdatesCommands({
  t,
  store,
  quitAndInstall,
}: {
  t: ReturnType<typeof useI18n>;
  store: ReturnType<typeof createStore>;
  quitAndInstall: () => Promise<void>;
}) {
  const unsubs: Array<() => void> = [];

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:restart-to-upgrade',
      category: 'cloud-notes:updates',
      icon: <ResetIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.restart-to-upgrade'](),
      preconditionStrategy: () => !!store.get(updateReadyAtom),
      run() {
        track.$.cmdk.updates.quitAndInstall();

        quitAndInstall().catch(err => {
          notify.error({
            title: 'Failed to restart to upgrade',
            message: 'Please restart the app manually to upgrade.',
          });
          console.error(err);
        });
      },
    })
  );

  return () => {
    unsubs.forEach(unsub => unsub());
  };
}
