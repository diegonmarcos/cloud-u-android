import { RootBlockSchema } from '@blocksuite/cloud-notes/model';
import { BlockService } from '@blocksuite/cloud-notes/std';
import { Subject } from 'rxjs';

export class MindmapService extends BlockService {
  static override readonly flavour = RootBlockSchema.model.flavour;

  requestCenter = new Subject<void>();

  center() {
    this.requestCenter.next();
  }

  override mounted(): void {}
}
