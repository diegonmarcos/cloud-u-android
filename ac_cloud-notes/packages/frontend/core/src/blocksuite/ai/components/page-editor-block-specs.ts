import { ViewExtensionManager } from '@blocksuite/cloud-notes/ext-loader';
import { getInternalViewExtensions } from '@blocksuite/cloud-notes/extensions/view';
import { BlockViewIdentifier } from '@blocksuite/cloud-notes/std';
import type { ExtensionType } from '@blocksuite/cloud-notes/store';
import { literal } from 'lit/static-html.js';

const manager = new ViewExtensionManager([...getInternalViewExtensions()]);
const customPageEditorBlockSpecs: ExtensionType[] = [
  ...manager.get('page'),
  {
    setup: di => {
      di.override(
        BlockViewIdentifier('affine:page'),
        () => literal`cloud-notes-page-root`
      );
    },
  },
];

export const getCustomPageEditorBlockSpecs = () => {
  return customPageEditorBlockSpecs;
};
