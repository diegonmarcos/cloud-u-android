import type { DocMode } from '@blocksuite/cloud-notes/model';

export const getDefaultShareMode = (
  currentMode?: DocMode
): DocMode | undefined => {
  return currentMode === 'edgeless' ? 'edgeless' : undefined;
};
