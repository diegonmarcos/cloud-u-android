import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import type { CapacitorConfig } from '@capacitor/cli';
import { KeyboardResize } from '@capacitor/keyboard';

const packageJson = JSON.parse(
  readFileSync(resolve(__dirname, './package.json'), 'utf-8')
);

interface AppConfig {
  cloudNotesVersion: string;
}

const config: CapacitorConfig & AppConfig = {
  appId: 'app.cloudnotes.pro',
  appName: 'Cloud Notes',
  webDir: 'dist',
  cloudNotesVersion: packageJson.version,
  ios: {
    scheme: 'Cloud Notes',
    path: '.',
    appendUserAgent: `iOS cloud-notes/${packageJson.version}`,
    webContentsDebuggingEnabled: true,
    // Silence Capacitor's bridge logging (⚡️ TO JS / ⚡️ To Native -> / ⚡️ [log]).
    loggingBehavior: 'none',
  },
  server: {
    // url: 'http://localhost:8080',
  },
  plugins: {
    CapacitorCookies: {
      enabled: false,
    },
    CapacitorHttp: {
      enabled: false,
    },
    Keyboard: {
      resize: KeyboardResize.None,
    },
  },
};

if (process.env.CAP_SERVER_URL) {
  Object.assign(config, {
    server: {
      url: process.env.CAP_SERVER_URL,
    },
  });
}

export default config;
