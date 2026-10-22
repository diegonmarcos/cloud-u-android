import type { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { SettingsIcon } from '@blocksuite/icons/rc';
import { appSettingAtom } from '@toeverything/infra';
import type { createStore } from 'jotai';
import type { useTheme } from 'next-themes';

import type { EditorSettingService } from '../modules/editor-setting';
import { registerCloudNotesCommand } from './registry';

export function registerCloudNotesSettingsCommands({
  t,
  store,
  theme,
  editorSettingService,
}: {
  t: ReturnType<typeof useI18n>;
  store: ReturnType<typeof createStore>;
  theme: ReturnType<typeof useTheme>;
  editorSettingService: EditorSettingService;
}) {
  const unsubs: Array<() => void> = [];
  const updateSettings = editorSettingService.editorSetting.set.bind(
    editorSettingService.editorSetting
  );
  const settings$ = editorSettingService.editorSetting.settings$;

  // color modes
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:change-color-mode-to-auto',
      label: `${t['com.cloud-notes.cmdk.cloud-notes.color-mode.to']()} ${t[
        'com.cloud-notes.themeSettings.system'
      ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => theme.theme !== 'system',
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'theme',
          value: 'system',
        });
        theme.setTheme('system');
      },
    })
  );
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:change-color-mode-to-dark',
      label: `${t['com.cloud-notes.cmdk.cloud-notes.color-mode.to']()} ${t[
        'com.cloud-notes.themeSettings.dark'
      ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => theme.theme !== 'dark',
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'theme',
          value: 'dark',
        });
        theme.setTheme('dark');
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:change-color-mode-to-light',
      label: `${t['com.cloud-notes.cmdk.cloud-notes.color-mode.to']()} ${t[
        'com.cloud-notes.themeSettings.light'
      ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => theme.theme !== 'light',
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'theme',
          value: 'light',
        });

        theme.setTheme('light');
      },
    })
  );

  // Font styles
  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:change-font-style-to-sans',
      label: `${t['com.cloud-notes.cmdk.cloud-notes.font-style.to']()} ${t[
        'com.cloud-notes.appearanceSettings.fontStyle.sans'
      ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => settings$.value.fontFamily !== 'Sans',
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'fontStyle',
          value: 'Sans',
        });

        updateSettings('fontFamily', 'Sans');
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:change-font-style-to-serif',
      label: `${t['com.cloud-notes.cmdk.cloud-notes.font-style.to']()} ${t[
        'com.cloud-notes.appearanceSettings.fontStyle.serif'
      ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => settings$.value.fontFamily !== 'Serif',
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'fontStyle',
          value: 'Serif',
        });

        updateSettings('fontFamily', 'Serif');
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: 'cloud-notes:change-font-style-to-mono',
      label: `${t['com.cloud-notes.cmdk.cloud-notes.font-style.to']()} ${t[
        'com.cloud-notes.appearanceSettings.fontStyle.mono'
      ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => settings$.value.fontFamily !== 'Mono',
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'fontStyle',
          value: 'Mono',
        });

        updateSettings('fontFamily', 'Mono');
      },
    })
  );

  // Layout Style
  unsubs.push(
    registerCloudNotesCommand({
      id: `cloud-notes:change-client-border-style`,
      label: () => `${t['com.cloud-notes.cmdk.cloud-notes.client-border-style.to']()} ${t[
        store.get(appSettingAtom).clientBorder
          ? 'com.cloud-notes.cmdk.cloud-notes.switch-state.off'
          : 'com.cloud-notes.cmdk.cloud-notes.switch-state.on'
      ]()}
        `,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => BUILD_CONFIG.isElectron,
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'clientBorder',
          value: store.get(appSettingAtom).clientBorder ? 'off' : 'on',
        });
        store.set(appSettingAtom, prev => ({
          ...prev,
          clientBorder: !prev.clientBorder,
        }));
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: `cloud-notes:change-full-width-layout`,
      label: () =>
        `${t[
          settings$.value.fullWidthLayout
            ? 'com.cloud-notes.cmdk.cloud-notes.default-page-width-layout.standard'
            : 'com.cloud-notes.cmdk.cloud-notes.default-page-width-layout.full-width'
        ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'fullWidthLayout',
          value: settings$.value.fullWidthLayout ? 'off' : 'on',
        });
        updateSettings('fullWidthLayout', !settings$.value.fullWidthLayout);
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: `cloud-notes:change-noise-background-on-the-sidebar`,
      label: () =>
        `${t[
          'com.cloud-notes.cmdk.cloud-notes.noise-background-on-the-sidebar.to'
        ]()} ${t[
          store.get(appSettingAtom).enableNoisyBackground
            ? 'com.cloud-notes.cmdk.cloud-notes.switch-state.off'
            : 'com.cloud-notes.cmdk.cloud-notes.switch-state.on'
        ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () => BUILD_CONFIG.isElectron,
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'enableNoisyBackground',
          value: store.get(appSettingAtom).enableNoisyBackground ? 'off' : 'on',
        });

        store.set(appSettingAtom, prev => ({
          ...prev,
          enableNoisyBackground: !prev.enableNoisyBackground,
        }));
      },
    })
  );

  unsubs.push(
    registerCloudNotesCommand({
      id: `cloud-notes:change-translucent-ui-on-the-sidebar`,
      label: () =>
        `${t['com.cloud-notes.cmdk.cloud-notes.translucent-ui-on-the-sidebar.to']()} ${t[
          store.get(appSettingAtom).enableBlurBackground
            ? 'com.cloud-notes.cmdk.cloud-notes.switch-state.off'
            : 'com.cloud-notes.cmdk.cloud-notes.switch-state.on'
        ]()}`,
      category: 'cloud-notes:settings',
      icon: <SettingsIcon />,
      preconditionStrategy: () =>
        BUILD_CONFIG.isElectron && environment.isMacOs,
      run() {
        track.$.cmdk.settings.changeAppSetting({
          key: 'enableBlurBackground',
          value: store.get(appSettingAtom).enableBlurBackground ? 'off' : 'on',
        });
        store.set(appSettingAtom, prev => ({
          ...prev,
          enableBlurBackground: !prev.enableBlurBackground,
        }));
      },
    })
  );

  return () => {
    unsubs.forEach(unsub => unsub());
  };
}
