import { createIdentifier } from '@blocksuite/global/di';
import type { ExtensionType } from '@blocksuite/store';

import type { CloudNotesUserInfo } from './types';

export interface WriterInfoService {
  getWriterInfo(): CloudNotesUserInfo | null;
}

export const WriterInfoProvider = createIdentifier<WriterInfoService>(
  'cloud-notes-writer-info-service'
);

export function WriterInfoServiceExtension(
  service: WriterInfoService
): ExtensionType {
  return {
    setup(di) {
      di.addImpl(WriterInfoProvider, () => service);
    },
  };
}
