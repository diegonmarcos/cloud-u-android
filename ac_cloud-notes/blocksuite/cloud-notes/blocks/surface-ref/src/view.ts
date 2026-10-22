import {
  type ViewExtensionContext,
  ViewExtensionProvider,
} from '@blocksuite/cloud-notes-ext-loader';
import { SurfaceRefBlockSchema } from '@blocksuite/cloud-notes-model';
import { ToolbarModuleExtension } from '@blocksuite/cloud-notes-shared/services';
import {
  BlockFlavourIdentifier,
  BlockViewExtension,
  FlavourExtension,
} from '@blocksuite/std';
import { literal } from 'lit/static-html.js';

import { SurfaceRefSlashMenuConfigExtension } from './configs/slash-menu';
import { surfaceRefToolbarModuleConfig } from './configs/toolbar';
import { effects } from './effects';

const flavour = SurfaceRefBlockSchema.model.flavour;

export class SurfaceRefViewExtension extends ViewExtensionProvider {
  override name = 'cloud-notes-surface-ref-block';

  override effect(): void {
    super.effect();
    effects();
  }

  override setup(context: ViewExtensionContext): void {
    super.setup(context);
    context.register([
      FlavourExtension(flavour),
      SurfaceRefSlashMenuConfigExtension,
    ]);
    const isEdgeless = this.isEdgeless(context.scope);
    if (isEdgeless) {
      context.register([
        BlockViewExtension(flavour, literal`cloud-notes-edgeless-surface-ref`),
      ]);
    } else {
      context.register([
        BlockViewExtension(flavour, literal`cloud-notes-surface-ref`),
        ToolbarModuleExtension({
          id: BlockFlavourIdentifier(flavour),
          config: surfaceRefToolbarModuleConfig,
        }),
      ]);
    }
  }
}
