import {
  AnimatedDeleteIcon,
  toast,
  useConfirmModal,
  useDropTarget,
} from '@cloud-notes/component';
import { MenuLinkItem } from '@cloud-notes/core/modules/app-sidebar/views';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { GlobalContextService } from '@cloud-notes/core/modules/global-context';
import { GuardService } from '@cloud-notes/core/modules/permissions';
import type { CloudNotesDNDData } from '@cloud-notes/core/types/dnd';
import { UserFriendlyError } from '@cloud-notes/error';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';

export const TrashButton = () => {
  const t = useI18n();
  const docsService = useService(DocsService);
  const { openConfirmModal } = useConfirmModal();
  const globalContextService = useService(GlobalContextService);
  const trashActive = useLiveData(globalContextService.globalContext.isTrash.$);
  const guardService = useService(GuardService);

  const { dropTargetRef, draggedOver } = useDropTarget<CloudNotesDNDData>(
    () => ({
      data: {
        at: 'app-sidebar:trash',
      },
      canDrop(data) {
        return data.source.data.entity?.type === 'doc';
      },
      onDrop(data) {
        if (data.source.data.entity?.type === 'doc') {
          const docId = data.source.data.entity.id;
          const docRecord = docsService.list.doc$(docId).value;
          if (docRecord) {
            openConfirmModal({
              title: t['com.cloud-notes.moveToTrash.confirmModal.title'](),
              description: t['com.cloud-notes.moveToTrash.confirmModal.description'](
                {
                  title: docRecord.title$.value || t['Untitled'](),
                }
              ),
              confirmText: t.Delete(),
              confirmButtonOptions: {
                variant: 'error',
              },
              async onConfirm() {
                try {
                  const canTrash = await guardService.can(
                    'Doc_Trash',
                    docRecord.id
                  );
                  if (!canTrash) {
                    toast(t['com.cloud-notes.no-permission']());
                    return;
                  }
                  await docRecord.moveToTrash();
                } catch (error) {
                  console.error(error);
                  const userFriendlyError = UserFriendlyError.fromAny(error);
                  toast(
                    t[`error.${userFriendlyError.name}`](userFriendlyError.data)
                  );
                }
              },
            });
          }
        }
      },
      allowExternal: true,
    }),
    [docsService.list, guardService, openConfirmModal, t]
  );

  return (
    <MenuLinkItem
      ref={dropTargetRef}
      icon={<AnimatedDeleteIcon closed={draggedOver} />}
      active={trashActive || draggedOver}
      to={'/trash'}
    >
      <span data-testid="trash-page">
        {t['com.cloud-notes.workspaceSubPath.trash']()}
      </span>
    </MenuLinkItem>
  );
};
