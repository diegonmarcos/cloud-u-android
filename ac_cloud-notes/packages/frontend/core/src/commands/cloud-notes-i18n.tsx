import type { I18n } from '@cloud-notes/core/modules/i18n';
import type { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { SettingsIcon } from '@blocksuite/icons/rc';

import { registerCloudNotesCommand } from './registry';

export function registerCloudNotesLanguageCommands({
  i18n,
  t,
}: {
  i18n: I18n;
  t: ReturnType<typeof useI18n>;
}) {
  // Display Language
  const disposables = i18n.languageList.map(language => {
    return registerCloudNotesCommand({
      id: `cloud-notes:change-display-language-to-${language.name}`,
      label: `${t['com.cloud-notes.cmdk.cloud-notes.display-language.to']()} ${
        language.originalName
      }`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () =>
        i18n.currentLanguage$.value.key !== language.key,
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'language',
          value: language.name,
        });

        i18n.changeLanguage(language.key);
      },
    });
  });

  return () => {
    disposables.forEach(dispose => dispose());
  };
}
