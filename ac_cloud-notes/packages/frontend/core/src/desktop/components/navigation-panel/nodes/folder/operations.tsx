import { MenuItem } from '@cloud-notes/component';
import { IsFavoriteIcon } from '@cloud-notes/core/components/pure/icons';
import { CompatibleFavoriteItemsAdapter } from '@cloud-notes/core/modules/favorite';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { useMemo } from 'react';

export const FavoriteFolderOperation = ({ id }: { id: string }) => {
  const t = useI18n();
  const compatibleFavoriteItemsAdapter = useService(
    CompatibleFavoriteItemsAdapter
  );

  const favorite = useLiveData(
    useMemo(() => {
      return compatibleFavoriteItemsAdapter.isFavorite$(id, 'folder');
    }, [compatibleFavoriteItemsAdapter, id])
  );

  return (
    <MenuItem
      prefixIcon={<IsFavoriteIcon favorite={favorite} />}
      onClick={() => compatibleFavoriteItemsAdapter.toggle(id, 'folder')}
    >
      {favorite
        ? t['com.cloud-notes.rootAppSidebar.organize.folder-rm-favorite']()
        : t['com.cloud-notes.rootAppSidebar.organize.folder-add-favorite']()}
    </MenuItem>
  );
};
