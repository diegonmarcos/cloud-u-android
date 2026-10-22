import type { UniComponent } from '@blocksuite/cloud-notes-shared/types';
import { createIdentifier } from '@blocksuite/global/di';
export enum IconType {
  Emoji = 'emoji',
  CloudNotesIcon = 'cloud-notes-icon',
  Blob = 'blob',
}

export type IconData =
  | {
      type: IconType.Emoji;
      unicode: string;
    }
  | {
      type: IconType.CloudNotesIcon;
      name: string;
      color: string;
    }
  | {
      type: IconType.Blob;
      blob: Blob;
    };

export interface IconPickerService {
  iconPickerComponent: UniComponent<{ onSelect?: (data?: IconData) => void }>;
}

export const IconPickerServiceIdentifier =
  createIdentifier<IconPickerService>('IconPickerService');
