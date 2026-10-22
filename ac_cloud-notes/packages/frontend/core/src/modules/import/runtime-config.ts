import type { NativeImportSessionHandlers } from '@cloud-notes/electron-api';

export type {
  NativeImportBrowserSource,
  NativeImportFormat,
  NativeImportSessionHandlers,
} from '@cloud-notes/electron-api';

let nativeImportSessionHandlers: NativeImportSessionHandlers | null = null;

export function registerNativeImportSessionHandlers(
  handlers: NativeImportSessionHandlers | null
) {
  nativeImportSessionHandlers = handlers;
}

export function getNativeImportSessionHandlers() {
  return nativeImportSessionHandlers;
}
