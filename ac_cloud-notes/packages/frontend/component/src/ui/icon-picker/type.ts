export enum IconType {
  Emoji = 'emoji',
  CloudNotesIcon = 'cloud-notes-icon',
  Blob = 'blob',
}

/** Icons chosen before the rename were stored with this type. */
export const LEGACY_ICON_TYPE = 'affine-icon';

export const isCloudNotesIconType = (type: string): boolean =>
  type === (IconType.CloudNotesIcon as string) || type === LEGACY_ICON_TYPE;

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
