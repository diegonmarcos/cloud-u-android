import { setupGlobal } from '@cloud-notes/env/global';
import { getBuildConfig } from '@cloud-notes-tools/utils/build-config';
import { Package } from '@cloud-notes-tools/utils/workspace';

globalThis.BUILD_CONFIG = getBuildConfig(new Package('@cloud-notes/web'), {
  mode: 'development',
  channel: 'canary',
});

if (typeof window !== 'undefined') {
  window.location.search = '?prefixUrl=http://127.0.0.1:3010/';
}

setupGlobal();
