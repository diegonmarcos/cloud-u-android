import type { ConfirmModalProps, MenuItemProps } from '@cloud-notes/component';
import { ConfirmModal, MenuItem } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';
import { DeleteIcon } from '@blocksuite/icons/rc';

export const MoveToTrash = (props: MenuItemProps) => {
  const t = useI18n();

  return (
    <MenuItem prefixIcon={<DeleteIcon />} type="danger" {...props}>
      {t['com.cloud-notes.moveToTrash.title']()}
    </MenuItem>
  );
};

const MoveToTrashConfirm = ({
  titles,
  ...confirmModalProps
}: {
  titles: string[];
} & ConfirmModalProps) => {
  const t = useI18n();
  const multiple = titles.length > 1;
  const title = multiple
    ? t['com.cloud-notes.moveToTrash.confirmModal.title.multiple']({
        number: titles.length.toString(),
      })
    : t['com.cloud-notes.moveToTrash.confirmModal.title']();
  const description = multiple
    ? t['com.cloud-notes.moveToTrash.confirmModal.description.multiple']({
        number: titles.length.toString(),
      })
    : t['com.cloud-notes.moveToTrash.confirmModal.description']({
        title: titles[0] || t['Untitled'](),
      });
  return (
    <ConfirmModal
      title={title}
      description={description}
      cancelText={t['com.cloud-notes.confirmModal.button.cancel']()}
      confirmText={t.Delete()}
      confirmButtonOptions={{
        ['data-testid' as string]: 'confirm-delete-page',
        variant: 'error',
      }}
      {...confirmModalProps}
    />
  );
};

MoveToTrash.ConfirmModal = MoveToTrashConfirm;
