import { useThemeColorV2 } from '@cloud-notes/component';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { LiveData, useLiveData, useService } from '@toeverything/infra';
import { useMemo } from 'react';

import { PageHeader } from '../../components';
import { boardsOf } from './boards';

/** Canvas > Boards: every edgeless doc, newest first; tap opens it on its canvas. */
export const BoardsView = () => {
  useThemeColorV2('layer/background/mobile/primary');
  const workbench = useService(WorkbenchService).workbench;
  const docList = useService(DocsService).list;

  const candidates = useLiveData(
    useMemo(
      () =>
        LiveData.computed(get =>
          get(docList.docs$).map(d => {
            const meta = get(d.meta$);
            return {
              id: d.id,
              title: meta.title,
              trash: meta.trash,
              updatedDate: meta.updatedDate,
              mode: get(d.primaryMode$) as string,
            };
          })
        ),
      [docList]
    )
  );
  const boards = boardsOf(candidates);

  return (
    <div data-testid="boards">
      <PageHeader back={false}>Boards</PageHeader>
      {boards.length === 0 ? (
        <div style={{ padding: 16 }} data-testid="boards-empty">
          No boards yet. Use <strong>New board</strong> in the strip above to start a canvas.
        </div>
      ) : (
        boards.map(b => (
          <button
            key={b.id}
            type="button"
            data-testid="board-row"
            onClick={() => workbench.openDoc(b.id)}
            style={{
              display: 'block',
              width: '100%',
              padding: '14px 16px',
              textAlign: 'left',
              border: 0,
              borderBottom: '1px solid rgba(128,128,128,0.25)',
              background: 'transparent',
              color: 'inherit',
              font: 'inherit',
            }}
          >
            {b.title}
          </button>
        ))
      )}
      <div style={{ height: 96 }} />
    </div>
  );
};
