import { type MenuProps } from '@cloud-notes/component';
import { usePageHelper } from '@cloud-notes/core/blocksuite/block-suite-page-list/utils';
import { ExplorerDisplayMenuButton } from '@cloud-notes/core/components/explorer/display-menu';
import { ViewToggle } from '@cloud-notes/core/components/explorer/display-menu/view-toggle';
import type { DocListItemView } from '@cloud-notes/core/components/explorer/docs-view/doc-list-item';
import { ExplorerNavigation } from '@cloud-notes/core/components/explorer/header/navigation';
import type { ExplorerDisplayPreference } from '@cloud-notes/core/components/explorer/types';
import { PageListNewPageButton } from '@cloud-notes/core/components/page-list/docs/page-list-new-page-button';
import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { inferOpenMode } from '@cloud-notes/core/utils';
import { useI18n } from '@cloud-notes/i18n';
import track from '@cloud-notes/track';
import { useService } from '@toeverything/infra';
import { useCallback } from 'react';

import * as styles from './all-page-header.css';

const menuProps: Partial<MenuProps> = {
  contentOptions: {
    side: 'bottom',
    align: 'end',
    alignOffset: 0,
    sideOffset: 8,
  },
};
export const AllDocsHeader = ({
  displayPreference,
  onDisplayPreferenceChange,
  view,
  onViewChange,
}: {
  displayPreference: ExplorerDisplayPreference;
  onDisplayPreferenceChange: (
    displayPreference: ExplorerDisplayPreference
  ) => void;
  view: DocListItemView;
  onViewChange: (view: DocListItemView) => void;
}) => {
  const t = useI18n();
  const workspaceService = useService(WorkspaceService);
  const workspaceDialogService = useService(WorkspaceDialogService);
  const workbenchService = useService(WorkbenchService);
  const workbench = workbenchService.workbench;
  const { createEdgeless, createPage } = usePageHelper(
    workspaceService.workspace.docCollection
  );

  const handleOpenDocs = useCallback(
    (result: {
      docIds: string[];
      entryId?: string;
      isWorkspaceFile?: boolean;
    }) => {
      const { docIds, entryId, isWorkspaceFile } = result;
      // If the imported file is a workspace file, open the entry page.
      if (isWorkspaceFile && entryId) {
        workbench.openDoc(entryId);
      } else if (!docIds.length) {
        return;
      }
      // Open all the docs when there are multiple docs imported.
      if (docIds.length > 1) {
        workbench.openAll();
      } else {
        // Otherwise, open the only doc.
        workbench.openDoc(docIds[0]);
      }
    },
    [workbench]
  );

  const onImportFile = useCallback(() => {
    track.$.header.importModal.open();
    workspaceDialogService.open('import', undefined, payload => {
      if (!payload) {
        return;
      }
      handleOpenDocs(payload);
    });
  }, [workspaceDialogService, handleOpenDocs]);

  return (
    <div className={styles.header}>
      <ExplorerNavigation active="docs" />

      <div className={styles.actions}>
        <ViewToggle view={view} onViewChange={onViewChange} />
        <ExplorerDisplayMenuButton
          menuProps={menuProps}
          displayPreference={displayPreference}
          onDisplayPreferenceChange={onDisplayPreferenceChange}
        />
        <PageListNewPageButton
          size="small"
          onCreateEdgeless={e => createEdgeless({ at: inferOpenMode(e) })}
          onCreatePage={e => createPage('page', { at: inferOpenMode(e) })}
          onCreateDoc={e => createPage(undefined, { at: inferOpenMode(e) })}
          onImportFile={onImportFile}
          data-testid="new-page-button-trigger"
        >
          <span className={styles.newPageButtonLabel}>{t['New Page']()}</span>
        </PageListNewPageButton>
      </div>
    </div>
  );
};
