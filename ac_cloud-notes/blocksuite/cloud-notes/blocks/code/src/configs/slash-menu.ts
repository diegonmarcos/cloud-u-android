import { type SlashMenuConfig } from '@blocksuite/cloud-notes-widget-slash-menu';

export const codeSlashMenuConfig: SlashMenuConfig = {
  disableWhen: ({ model }) => {
    return model.flavour === 'affine:code';
  },
  items: [],
};
