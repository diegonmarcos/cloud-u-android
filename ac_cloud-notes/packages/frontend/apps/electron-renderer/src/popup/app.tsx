import { ThemeProvider } from '@cloud-notes/core/components/theme-provider';
import { configureElectronStateStorageImpls } from '@cloud-notes/core/desktop/storage';
import { configureDesktopApiModule } from '@cloud-notes/core/modules/desktop-api';
import { configureI18nModule, I18nProvider } from '@cloud-notes/core/modules/i18n';
import { configureStorageModule } from '@cloud-notes/core/modules/storage';
import { configureEssentialThemeModule } from '@cloud-notes/core/modules/theme';
import { appInfo } from '@cloud-notes/electron-api';
import { Framework, FrameworkRoot } from '@toeverything/infra';

import * as styles from './app.css';
import { Recording } from './recording';

const framework = new Framework();
configureI18nModule(framework);
configureEssentialThemeModule(framework);
configureStorageModule(framework);
configureElectronStateStorageImpls(framework);
configureDesktopApiModule(framework);
const frameworkProvider = framework.provider();

const mode = appInfo?.windowName as 'notification' | 'recording';

export function App() {
  return (
    <FrameworkRoot framework={frameworkProvider}>
      <ThemeProvider>
        <I18nProvider>
          <div className={styles.root} data-is-windows={environment.isWindows}>
            {mode === 'recording' && <Recording />}
          </div>
        </I18nProvider>
      </ThemeProvider>
    </FrameworkRoot>
  );
}
