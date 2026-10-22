import { IconPicker, uniReactRoot } from '@cloud-notes/component';
// Import the identifier for internal use
import { type IconPickerService as IIconPickerService } from '@blocksuite/cloud-notes-shared/services';
import { Service } from '@toeverything/infra';

// Re-export types from BlockSuite shared services
export type {
  IconData,
  IconPickerService as IIconPickerService,
} from '@blocksuite/cloud-notes-shared/services';
export { IconPickerServiceIdentifier } from '@blocksuite/cloud-notes-shared/services';

export class IconPickerService extends Service implements IIconPickerService {
  public readonly iconPickerComponent =
    uniReactRoot.createUniComponent(IconPicker);
}
