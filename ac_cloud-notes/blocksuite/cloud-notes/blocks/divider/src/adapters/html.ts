import { DividerBlockSchema } from '@blocksuite/cloud-notes-model';
import {
  BlockHtmlAdapterExtension,
  type BlockHtmlAdapterMatcher,
  HastUtils,
} from '@blocksuite/cloud-notes-shared/adapters';
import { nanoid } from '@blocksuite/store';

export const dividerBlockHtmlAdapterMatcher: BlockHtmlAdapterMatcher = {
  flavour: DividerBlockSchema.model.flavour,
  toMatch: o => HastUtils.isElement(o.node) && o.node.tagName === 'hr',
  fromMatch: o => o.node.flavour === DividerBlockSchema.model.flavour,
  toBlockSnapshot: {
    enter: (o, context) => {
      if (!HastUtils.isElement(o.node)) {
        return;
      }
      const { walkerContext } = context;
      walkerContext
        .openNode(
          {
            type: 'block',
            id: nanoid(),
            flavour: 'cloud-notes:divider',
            props: {},
            children: [],
          },
          'children'
        )
        .closeNode();
    },
  },
  fromBlockSnapshot: {
    enter: (_, context) => {
      const { walkerContext } = context;
      walkerContext
        .openNode(
          {
            type: 'element',
            tagName: 'hr',
            properties: {},
            children: [],
          },
          'children'
        )
        .closeNode();
    },
  },
};

export const DividerBlockHtmlAdapterExtension = BlockHtmlAdapterExtension(
  dividerBlockHtmlAdapterMatcher
);
