import { useI18n } from '@cloud-notes/i18n';

import {
  RenameDialog,
  type RenameDialogProps,
  RenameSubMenu,
  type RenameSubMenuProps,
} from '../../../rename';

export const CollectionRenameSubMenu = ({
  title,
  text,
  ...props
}: RenameSubMenuProps) => {
  const t = useI18n();
  return (
    <RenameSubMenu
      title={title || t['com.cloud-notes.m.explorer.collection.rename-menu-title']()}
      text={text || t['com.cloud-notes.m.explorer.collection.rename']()}
      {...props}
    />
  );
};

const CollectionDesc = () => {
  const t = useI18n();
  return t['com.cloud-notes.collection.emptyCollectionDescription']();
};

export const CollectionRenameDialog = ({
  title,
  confirmText,
  descRenderer,
  ...props
}: RenameDialogProps) => {
  return (
    <RenameDialog
      title={title}
      confirmText={confirmText}
      {...props}
      descRenderer={descRenderer ?? CollectionDesc}
    />
  );
};
