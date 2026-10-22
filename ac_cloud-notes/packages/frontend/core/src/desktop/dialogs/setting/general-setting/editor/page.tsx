import { RadioGroup, type RadioItem, Switch } from '@cloud-notes/component';
import {
  SettingRow,
  SettingWrapper,
} from '@cloud-notes/component/setting-components';
import { EditorSettingService } from '@cloud-notes/core/modules/editor-setting';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback, useMemo } from 'react';

import { settingWrapper } from './style.css';

export const Page = () => {
  const t = useI18n();
  const editorSetting = useService(EditorSettingService).editorSetting;
  const settings = useLiveData(editorSetting.settings$);

  const radioItems = useMemo<RadioItem[]>(
    () => [
      {
        value: 'standard',
        label:
          t[
            'com.cloud-notes.settings.editorSettings.page.default-page-width.standard'
          ](),
        testId: 'standard-width-trigger',
      },
      {
        value: 'fullWidth',
        label:
          t[
            'com.cloud-notes.settings.editorSettings.page.default-page-width.full-width'
          ](),
        testId: 'full-width-trigger',
      },
    ],
    [t]
  );

  const handleFullWidthLayoutChange = useCallback(
    (value: string) => {
      const checked = value === 'fullWidth';
      editorSetting.set('fullWidthLayout', checked);
    },
    [editorSetting]
  );
  const handleDisplayDocInfoChange = useCallback(
    (checked: boolean) => {
      editorSetting.set('displayDocInfo', checked);
    },
    [editorSetting]
  );
  const handleDisplayBiDirectionalLinkChange = useCallback(
    (checked: boolean) => {
      editorSetting.set('displayBiDirectionalLink', checked);
    },
    [editorSetting]
  );

  return (
    <SettingWrapper title={t['com.cloud-notes.settings.editorSettings.page']()}>
      <SettingRow
        name={t[
          'com.cloud-notes.settings.editorSettings.page.default-page-width.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.editorSettings.page.default-page-width.description'
        ]()}
      >
        <RadioGroup
          items={radioItems}
          value={settings.fullWidthLayout ? 'fullWidth' : 'standard'}
          width={250}
          className={settingWrapper}
          onChange={handleFullWidthLayoutChange}
        />
      </SettingRow>
      <SettingRow
        name={t[
          'com.cloud-notes.settings.editorSettings.page.display-doc-info.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.editorSettings.page.display-doc-info.description'
        ]()}
      >
        <Switch
          data-testid="display-doc-info-trigger"
          checked={settings.displayDocInfo}
          onChange={handleDisplayDocInfoChange}
        />
      </SettingRow>
      <SettingRow
        name={t[
          'com.cloud-notes.settings.editorSettings.page.display-bi-link.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.editorSettings.page.display-bi-link.description'
        ]()}
      >
        <Switch
          data-testid="display-bi-link-trigger"
          checked={settings.displayBiDirectionalLink}
          onChange={handleDisplayBiDirectionalLinkChange}
        />
      </SettingRow>
    </SettingWrapper>
  );
};
