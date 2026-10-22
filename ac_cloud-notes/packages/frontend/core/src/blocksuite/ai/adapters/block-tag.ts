import {
  BlockMarkdownAdapterExtension,
  type BlockMarkdownAdapterMatcher,
} from '@blocksuite/cloud-notes-shared/adapters';

export const blockTagMarkdownAdapterMatcher: BlockMarkdownAdapterMatcher = {
  flavour: 'cloud-notes:page/cloud-notes:note/*',
  toMatch: () => false,
  fromMatch: o => {
    const block = o.node;
    const parent = o.parent;
    if (block.type === 'block' && parent?.node.flavour === 'cloud-notes:note') {
      return true;
    }
    return false;
  },
  toBlockSnapshot: {},
  fromBlockSnapshot: {
    async enter(block, adapterContext) {
      adapterContext.walkerContext
        .openNode({
          type: 'html',
          value: `<!-- block_id=${block.node.id} flavour=${block.node.flavour} -->`,
        })
        .closeNode();
    },
  },
};

export const BlockTagMarkdownAdapterExtension = BlockMarkdownAdapterExtension(
  blockTagMarkdownAdapterMatcher
);
