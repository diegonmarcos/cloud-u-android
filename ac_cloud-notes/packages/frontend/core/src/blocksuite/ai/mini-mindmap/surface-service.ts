import { SurfaceBlockSchema } from '@blocksuite/cloud-notes/blocks/surface';
import { BlockService } from '@blocksuite/cloud-notes/std';

export class MindmapSurfaceBlockService extends BlockService {
  static override readonly flavour = SurfaceBlockSchema.model.flavour;
}
