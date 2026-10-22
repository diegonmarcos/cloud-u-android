import { IconButton, MobileMenu } from '@cloud-notes/component';
import { useEnableCloud } from '@cloud-notes/core/components/hooks/cloud-notes/use-enable-cloud';
import { DocService } from '@cloud-notes/core/modules/doc';
import { ShareMenuContent } from '@cloud-notes/core/modules/share-menu';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { ShareiOsIcon } from '@blocksuite/icons/rc';
import { useServices } from '@toeverything/infra';

import * as styles from './page-header-share-button.css';

export const PageHeaderShareButton = () => {
  const { workspaceService, docService } = useServices({
    WorkspaceService,
    DocService,
  });
  const workspace = workspaceService.workspace;
  const doc = docService.doc.blockSuiteDoc;
  const confirmEnableCloud = useEnableCloud();

  if (workspace.meta.flavour === 'local') {
    return null;
  }

  return (
    <MobileMenu
      items={
        <div className={styles.content}>
          <ShareMenuContent
            workspaceMetadata={workspace.meta}
            currentPage={doc}
            onEnableCloudNotesCloud={() =>
              confirmEnableCloud(workspace, {
                openPageId: doc.id,
              })
            }
          />
        </div>
      }
    >
      <IconButton size={24} style={{ padding: 10 }} icon={<ShareiOsIcon />} />
    </MobileMenu>
  );
};
