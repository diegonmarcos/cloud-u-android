import { Divider, MenuItem } from '@cloud-notes/component';
import { GlobalDialogService } from '@cloud-notes/core/modules/dialogs';
import { useI18n } from '@cloud-notes/i18n';
import { PlusIcon } from '@blocksuite/icons/rc';
import { useService } from '@toeverything/infra';
import { useCallback } from 'react';

import {
  ItemContainer,
  ItemText,
  prefixIcon,
} from '../add-workspace/index.css';
import { addServerDividerWrapper } from './index.css';

export const AddServer = () => {
  const t = useI18n();
  const globalDialogService = useService(GlobalDialogService);

  const onAddServer = useCallback(() => {
    globalDialogService.open('sign-in', { step: 'addSelfhosted' });
  }, [globalDialogService]);

  if (!BUILD_CONFIG.isNative) {
    return null;
  }

  return (
    <>
      <div className={addServerDividerWrapper}>
        <Divider size="thinner" />
      </div>
      <MenuItem
        block={true}
        prefixIcon={<PlusIcon />}
        prefixIconClassName={prefixIcon}
        onClick={onAddServer}
        data-testid="new-server"
        className={ItemContainer}
      >
        <div className={ItemText}>
          {t['com.cloud-notes.workspaceList.addServer']()}
        </div>
      </MenuItem>
    </>
  );
};
