import { SettingHeader } from '@cloud-notes/component/setting-components';
import { useI18n } from '@cloud-notes/i18n';

import { Edgeless } from './edgeless';
import { General } from './general';
import { Page } from './page';

export const EditorSettings = () => {
  const t = useI18n();

  return (
    <>
      <SettingHeader
        title={t['com.cloud-notes.settings.editorSettings.title']()}
        subtitle={t['com.cloud-notes.settings.editorSettings.subtitle']()}
      />
      <General />
      <Page />
      <Edgeless />

      {/* // TODO(@EYHN): implement export and import
       <Preferences /> */}
    </>
  );
};
