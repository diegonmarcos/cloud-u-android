import { getThemeOptions } from '@cloud-notes/core/desktop/dialogs/setting/general-setting/appearance';
import { useI18n } from '@cloud-notes/i18n';
import { useTheme } from 'next-themes';
import { useMemo } from 'react';

import { SettingDropdownSelect } from '../dropdown-select';
import { RowLayout } from '../row.layout';

export const ThemeSetting = () => {
  const t = useI18n();

  const options = useMemo(() => getThemeOptions(t), [t]);
  const { setTheme, theme } = useTheme();

  return (
    <RowLayout label={t['com.cloud-notes.mobile.setting.appearance.theme']()}>
      <SettingDropdownSelect
        options={options}
        value={theme}
        onChange={setTheme}
      />
    </RowLayout>
  );
};
