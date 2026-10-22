import type { InlineEditProps } from '@cloud-notes/component';
import { InlineEdit } from '@cloud-notes/component';
import { useGuard } from '@cloud-notes/core/components/guard';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { DocService, DocsService } from '@cloud-notes/core/modules/doc';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { track } from '@cloud-notes/track';
import { useLiveData, useService } from '@toeverything/infra';
import clsx from 'clsx';
import type { HTMLAttributes } from 'react';

import * as styles from './style.css';

export interface BlockSuiteHeaderTitleProps {
  /** if set, title cannot be edited */
  inputHandleRef?: InlineEditProps['handleRef'];
  className?: string;
}

const inputAttrs = {
  'data-testid': 'title-content',
} as HTMLAttributes<HTMLInputElement>;
export const BlocksuiteHeaderTitle = (props: BlockSuiteHeaderTitleProps) => {
  const { inputHandleRef } = props;
  const workspaceService = useService(WorkspaceService);
  const isSharedMode = workspaceService.workspace.openOptions.isSharedMode;
  const docsService = useService(DocsService);
  const docService = useService(DocService);
  const docTitle = useLiveData(docService.doc.record.title$);

  const onChange = useAsyncCallback(
    async (v: string) => {
      await docsService.changeDocTitle(docService.doc.id, v);
      track.$.header.actions.renameDoc();
    },
    [docService.doc.id, docsService]
  );

  const canEdit = useGuard('Doc_Update', docService.doc.id);

  return (
    <InlineEdit
      className={clsx(styles.title, props.className)}
      value={docTitle}
      onChange={onChange}
      editable={!isSharedMode && canEdit}
      exitible={true}
      placeholder="Untitled"
      data-testid="title-edit-button"
      handleRef={inputHandleRef}
      inputAttrs={inputAttrs}
    />
  );
};
