import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import track from '@cloud-notes/track';
import type { Container } from '@blocksuite/cloud-notes/global/di';
import {
  FileSizeLimitProvider,
  type IFileSizeLimitService,
} from '@blocksuite/cloud-notes/shared/services';
import { Extension } from '@blocksuite/cloud-notes/store';
import type { FrameworkProvider } from '@toeverything/infra';

export function patchFileSizeLimitExtension(framework: FrameworkProvider) {
  const workspaceDialogService = framework.get(WorkspaceDialogService);

  class CloudNotesFileSizeLimitService
    extends Extension
    implements IFileSizeLimitService
  {
    // 2GB
    maxFileSize = 2 * 1024 * 1024 * 1024;

    onOverFileSize() {
      workspaceDialogService.open('setting', {
        activeTab: 'plans',
        scrollAnchor: 'cloudPricingPlan',
      });
      track.$.paywall.storage.viewPlans();
    }

    static override setup(di: Container) {
      di.override(FileSizeLimitProvider, CloudNotesFileSizeLimitService);
    }
  }

  return CloudNotesFileSizeLimitService;
}
