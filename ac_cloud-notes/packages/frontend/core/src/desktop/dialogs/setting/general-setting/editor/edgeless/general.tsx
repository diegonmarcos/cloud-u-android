import { Menu, MenuItem, MenuTrigger, Switch } from '@cloud-notes/component';
import { SettingRow } from '@cloud-notes/component/setting-components';
import { EditorSettingService } from '@cloud-notes/core/modules/editor-setting';
import type { EdgelessDefaultTheme } from '@cloud-notes/core/modules/editor-setting/schema';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useMemo } from 'react';

import { menuTrigger } from '../style.css';

const getThemeOptions = (
  t: ReturnType<typeof useI18n>
): { value: EdgelessDefaultTheme; label: string }[] => [
  {
    value: 'specified' as EdgelessDefaultTheme,
    label:
      t[
        'com.cloud-notes.settings.editorSettings.page.edgeless-default-theme.specified'
      ](),
  },
  {
    value: 'dark' as EdgelessDefaultTheme,
    label: t['com.cloud-notes.themeSettings.dark'](),
  },
  {
    value: 'light' as EdgelessDefaultTheme,
    label: t['com.cloud-notes.themeSettings.light'](),
  },
  {
    value: 'auto' as EdgelessDefaultTheme,
    label: t['com.cloud-notes.themeSettings.auto'](),
  },
];

const getThemeValue = (
  theme: EdgelessDefaultTheme,
  t: ReturnType<typeof useI18n>
) => {
  switch (theme) {
    case 'dark':
      return t['com.cloud-notes.themeSettings.dark']();
    case 'light':
      return t['com.cloud-notes.themeSettings.light']();
    case 'auto':
      return t['com.cloud-notes.themeSettings.auto']();
    case 'specified':
      return t[
        'com.cloud-notes.settings.editorSettings.page.edgeless-default-theme.specified'
      ]();
    default:
      return '';
  }
};

export const GeneralEdgelessSetting = () => {
  const t = useI18n();
  const editorSetting = useService(EditorSettingService).editorSetting;
  const edgelessDefaultTheme = useLiveData(
    editorSetting.settings$
  ).edgelessDefaultTheme;

  const items = getThemeOptions(t);
  const currentTheme = useMemo(() => {
    return getThemeValue(edgelessDefaultTheme, t);
  }, [edgelessDefaultTheme, t]);

  const menuItems = useMemo(() => {
    return items.map(item => {
      const selected = edgelessDefaultTheme === item.value;
      const onSelect = () => {
        editorSetting.set('edgelessDefaultTheme', item.value);
      };
      return (
        <MenuItem key={item.value} selected={selected} onSelect={onSelect}>
          {item.label}
        </MenuItem>
      );
    });
  }, [editorSetting, items, edgelessDefaultTheme]);

  const handleScrollZoomChange = useCallback(
    (checked: boolean) => {
      editorSetting.set('edgelessScrollZoom', checked);
    },
    [editorSetting]
  );

  return (
    <>
      <SettingRow
        name={t[
          'com.cloud-notes.settings.editorSettings.page.edgeless-default-theme.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.editorSettings.page.edgeless-default-theme.description'
        ]()}
      >
        <Menu
          items={menuItems}
          contentOptions={{
            align: 'end',
            sideOffset: 16,
            style: {
              width: '280px',
            },
          }}
        >
          <MenuTrigger tooltip={currentTheme} className={menuTrigger}>
            {currentTheme}
          </MenuTrigger>
        </Menu>
      </SettingRow>
      <SettingRow
        name={t[
          'com.cloud-notes.settings.editorSettings.page.edgeless-scroll-wheel-zoom.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.editorSettings.page.edgeless-scroll-wheel-zoom.description'
        ]()}
      >
        <Switch
          checked={editorSetting.edgelessScrollZoom.$.value}
          onChange={handleScrollZoomChange}
        ></Switch>
      </SettingRow>
    </>
  );
};
