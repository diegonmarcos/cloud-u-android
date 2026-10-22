import { registerNativeImportSessionHandlers } from '@cloud-notes/core/modules/import';
import { apis } from '@cloud-notes/electron-api';

const importApis = apis?.import;

if (importApis) {
  registerNativeImportSessionHandlers({
    createImportSession: options =>
      importApis.createImportSessionFromSource(options),
    nextImportBatch: sessionId => importApis.nextImportBatch(sessionId),
    cancelImportSession: sessionId => importApis.cancelImportSession(sessionId),
    disposeImportSession: sessionId =>
      importApis.disposeImportSession(sessionId),
  });
}
