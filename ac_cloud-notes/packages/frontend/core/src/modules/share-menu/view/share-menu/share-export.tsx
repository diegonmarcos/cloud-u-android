import { useExportPage } from '@cloud-notes/core/components/hooks/cloud-notes/use-export-page';
import {
  ExportMenuItems,
  PrintMenuItems,
} from '@cloud-notes/core/components/page-list';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';

import * as styles from './index.css';

export const ShareExport = () => {
  const t = useI18n();
  const editor = useService(EditorService).editor;
  const exportHandler = useExportPage();
  const currentMode = useLiveData(editor.mode$);

  return (
    <div className={styles.exportContainerStyle}>
      <div className={styles.descriptionStyle}>
        {t['com.cloud-notes.share-menu.ShareViaExportDescription']()}
      </div>
      <div className={styles.exportContainerStyle}>
        <ExportMenuItems
          exportHandler={exportHandler}
          className={styles.exportItemStyle}
          pageMode={currentMode}
        />
      </div>
      {currentMode === 'page' && (
        <>
          <div className={styles.descriptionStyle}>
            {t['com.cloud-notes.share-menu.ShareViaPrintDescription']()}
          </div>
          <div>
            <PrintMenuItems
              exportHandler={exportHandler}
              className={styles.exportItemStyle}
            />
          </div>
        </>
      )}
    </div>
  );
};
