import '@cloud-notes/core/bootstrap/browser';
import '@cloud-notes/component/theme';
import '@cloud-notes/core/mobile/styles/mobile.css';
import './proxy';

import { viewportRuntimeConfig } from '@blocksuite/cloud-notes/std/gfx';

// The Compose shell draws the bottom nav (libs:nav-bar-bottom); the web's own tabs hide.
(window as unknown as { cloudNotesNativeNav?: boolean }).cloudNotesNativeNav =
  true;

// ONE source of the system-bar insets: the Compose shell's first row owns the status bar and its
// bottom nav clears the navigation bar, and the WebView sits between them, so the web half must not
// pad for either a second time (SafeArea reads --safe-area-inset-*, which Capacitor's SystemBars
// fills with the bar heights). !important beats the injected inline value.
{
  const style = document.createElement('style');
  style.textContent = ':root{--safe-area-inset-top:0px !important;--safe-area-inset-bottom:0px !important}';
  document.documentElement.appendChild(style);
}

// Android WebView is less prone to the iOS WKWebView process kill, so keep the
// user-facing zoom range unchanged. These knobs only reduce gesture-time work
// and low-zoom canvas memory while preserving the normal-zoom render quality.
viewportRuntimeConfig.VIEWPORT_REFRESH_PIXEL_THRESHOLD = 60;
viewportRuntimeConfig.VIEWPORT_REFRESH_MAX_INTERVAL = 300;
viewportRuntimeConfig.SKIP_REFRESH_DURING_GESTURE = true;
viewportRuntimeConfig.OVERSCAN_RATIO = 0.2;
viewportRuntimeConfig.OVERSCAN_RATIO_BLOCK = 0;
// Keep this aligned with iOS: the ~200ms gesture debounce plus this delay gives
// elements/connectors enough time to settle while still reappearing within ~500ms.
viewportRuntimeConfig.POST_GESTURE_REFRESH_DELAY = 220;
viewportRuntimeConfig.CANVAS_DPR_CAP_BY_ZOOM = [
  [0.5, 1],
  [0.8, 2],
];
