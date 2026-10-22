import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { BlockViewExtension, FlavourExtension } from '@blocksuite/std';
import { literal } from 'lit/static-html.js';

import { DataViewBlockSchema } from './data-view-model';
import { effects } from './effects';

const flavour = DataViewBlockSchema.model.flavour;

export class DataViewViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-data-view-block';

  override effect() {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext) {
    super.setup(context);
    context.register([
      FlavourExtension(flavour),
      BlockViewExtension(flavour, literal`cloud-notes-data-view`),
    ]);
  }
}
