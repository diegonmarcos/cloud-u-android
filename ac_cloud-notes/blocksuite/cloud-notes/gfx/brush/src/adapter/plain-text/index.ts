import { ElementToPlainTextAdapterExtension } from '@blocksuite/cloud-notes-block-surface';

export const brushToPlainTextAdapterMatcher =
  ElementToPlainTextAdapterExtension({
    name: 'brush',
    match: elementModel => elementModel.type === 'brush',
    toAST: () => {
      const content = `Brush Stroke`;
      return { content };
    },
  });
