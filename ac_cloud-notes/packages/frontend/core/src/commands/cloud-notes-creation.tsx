import type { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import type { DocMode } from '@blocksuite/cloud-notes/model';
import { ImportIcon, PlusIcon } from '@blocksuite/icons/rc';

import type { usePageHelper } from '../blocksuite/block-suite-page-list/utils';
import type { GlobalDialogService } from '../modules/dialogs';
import { registerCloudNotesCommand } from './registry';

export function registerCloudNotesCreationCommands({
  pageHelper,
  t,
  globalDialogService,
}: {
  t: ReturnType<typeof useI18n>;
  pageHelper: ReturnType<typeof usePageHelper>;
  globalDialogService: GlobalDialogService;
}) {
  const unsubs: Array<() => void> = [];
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:new-page',
      category: 'cloud-notes:creation',
      label: t['com.cloud-notes.cmdk.cloud-notes.new-page'](),
      icon: <PlusIcon />,
      keyBinding: BUILD_CONFIG.isElectron
        ? {
            binding: '$mod+N',
            skipRegister: true,
          }
        : undefined,
      run() {
        track.$.cmdk.creation.createDoc({ mode: 'page' });

        pageHelper.createPage('page' as DocMode);
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:new-edgeless-page',
      category: 'cloud-notes:creation',
      icon: <PlusIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.new-edgeless-page'](),
      run() {
        track.$.cmdk.creation.createDoc({
          mode: 'edgeless',
        });

        pageHelper.createEdgeless();
      },
    })
  );

  if (!BUILD_CONFIG.isMobileEdition) {
    unsubs.push(
      registerCloudNotesCommand({
        id: 'cloud-notes:new-workspace',
        category: 'cloud-notes:creation',
        icon: <PlusIcon />,
        label: t['com.cloud-notes.cmdk.cloud-notes.new-workspace'](),
        run() {
          track.$.cmdk.workspace.createWorkspace();

          globalDialogService.open('create-workspace', {});
        },
      })
    );
  }
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:import-workspace',
      category: 'cloud-notes:creation',
      icon: <ImportIcon />,
      label: t['com.cloud-notes.cmdk.cloud-notes.import-workspace'](),
      preconditionStrategy: () => {
        return BUILD_CONFIG.isElectron;
      },
      run() {
        track.$.cmdk.workspace.createWorkspace({
          control: 'import',
        });

        globalDialogService.open('import-workspace', undefined);
      },
    })
  );

  return () => {
    unsubs.forEach(unsub => unsub());
  };
}
