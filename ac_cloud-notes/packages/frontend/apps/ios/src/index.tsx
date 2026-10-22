import './setup';
import '@cloud-notes/component/theme';
import '@cloud-notes/core/mobile/styles/mobile.css';

import { Telemetry } from '@cloud-notes/core/components/telemetry';
import { bindNativeDBApis } from '@cloud-notes/nbstore/sqlite';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './app';
import { NbStoreNativeDBApis } from './plugins/nbstore';

bindNativeDBApis(NbStoreNativeDBApis);

function mountApp() {
  // oxlint-disable-next-line typescript/no-non-null-assertion
  const root = document.getElementById('app')!;
  createRoot(root).render(
    <StrictMode>
      <Telemetry />
      <App />
    </StrictMode>
  );
}

try {
  mountApp();
} catch (err) {
  console.error('Failed to bootstrap app', err);
}
