import { Button } from '@cloud-notes/component';
import {
  SettingRow,
  SettingWrapper,
} from '@cloud-notes/component/setting-components';
import { useI18n } from '@cloud-notes/i18n';

export const Preferences = () => {
  const t = useI18n();
  return (
    <SettingWrapper
      title={t['com.cloud-notes.settings.editorSettings.preferences']()}
    >
      <SettingRow
        name={t[
          'com.cloud-notes.settings.editorSettings.preferences.export.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.editorSettings.preferences.export.description'
        ]()}
      >
        <Button>Export</Button>
      </SettingRow>
      <SettingRow
        name={t[
          'com.cloud-notes.settings.editorSettings.preferences.import.title'
        ]()}
        desc={t[
          'com.cloud-notes.settings.editorSettings.preferences.import.description'
        ]()}
      >
        <Button>Import</Button>
      </SettingRow>
    </SettingWrapper>
  );
};
