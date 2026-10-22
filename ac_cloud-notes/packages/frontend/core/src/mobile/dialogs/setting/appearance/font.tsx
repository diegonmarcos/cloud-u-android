import { getBaseFontStyleOptions } from '@cloud-notes/core/desktop/dialogs/setting/general-setting/editor/general';
import {
  EditorSettingService,
  type FontFamily,
} from '@cloud-notes/core/modules/editor-setting';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useMemo } from 'react';

import { SettingDropdownSelect } from '../dropdown-select';
import { RowLayout } from '../row.layout';

export const FontStyleSetting = () => {
  const t = useI18n();
  const editorSetting = useService(EditorSettingService).editorSetting;
  const fontFamily = useLiveData(
    editorSetting.settings$.selector(s => s.fontFamily)
  );

  const options = useMemo(() => getBaseFontStyleOptions(t), [t]);
  const handleEdit = useCallback(
    (v: FontFamily) => {
      editorSetting.set('fontFamily', v);
    },
    [editorSetting]
  );

  return (
    <RowLayout label={t['com.cloud-notes.mobile.setting.appearance.font']()}>
      <SettingDropdownSelect<FontFamily>
        options={options}
        value={fontFamily}
        onChange={handleEdit}
      />
    </RowLayout>
  );
};
