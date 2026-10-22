import type { MenuItemProps } from '@cloud-notes/component';
import { MenuItem } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';
import { ShareIcon } from '@blocksuite/icons/rc';

export const DisablePublicSharing = (props: MenuItemProps) => {
  const t = useI18n();
  return (
    <MenuItem type="danger" prefixIcon={<ShareIcon />} {...props}>
      {t['Disable Public Sharing']()}
    </MenuItem>
  );
};
