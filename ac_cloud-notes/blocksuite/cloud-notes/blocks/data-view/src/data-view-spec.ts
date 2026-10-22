import { BlockViewExtension, FlavourExtension } from '@blocksuite/std';
import type { ExtensionType } from '@blocksuite/store';
import { literal } from 'lit/static-html.js';

export const DataViewBlockSpec: ExtensionType[] = [
  FlavourExtension('cloud-notes:data-view'),
  BlockViewExtension('cloud-notes:data-view', literal`cloud-notes-data-view`),
];
