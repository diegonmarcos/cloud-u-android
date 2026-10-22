import { useI18n } from '@cloud-notes/i18n';

import { SettingGroup } from '../group';
import { RowLayout } from '../row.layout';

export const OthersGroup = () => {
  const t = useI18n();

  return (
    <SettingGroup title={t['com.cloud-notes.mobile.setting.others.title']()}>
      <RowLayout
        label={t['com.cloud-notes.mobile.setting.others.discord']()}
        href="https://discord.com/invite/whd5mjYqVw"
      />
      <RowLayout
        label={t['com.cloud-notes.mobile.setting.others.github']()}
        href="https://github.com/diegonmarcos/cloud-u-android"
      />

      <RowLayout
        label={t['com.cloud-notes.mobile.setting.others.website']()}
        href="https://notes.diegonmarcos.com/"
      />

      <RowLayout
        label={t['com.cloud-notes.mobile.setting.others.privacy']()}
        href="https://notes.diegonmarcos.com/privacy"
      />

      <RowLayout
        label={t['com.cloud-notes.mobile.setting.others.terms']()}
        href="https://notes.diegonmarcos.com/terms"
      />
    </SettingGroup>
  );
};
