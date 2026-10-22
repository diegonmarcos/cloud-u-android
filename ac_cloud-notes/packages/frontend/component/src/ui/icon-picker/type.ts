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
