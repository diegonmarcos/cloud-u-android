import type { DocProps } from '@cloud-notes/core/blocksuite/initialization';
import type { DocMode } from '@blocksuite/cloud-notes/model';

export interface DocCreateOptions {
  id?: string;
  title?: string;
  primaryMode?: DocMode;
  skipInit?: boolean;
  docProps?: DocProps;
  isTemplate?: boolean;
}
