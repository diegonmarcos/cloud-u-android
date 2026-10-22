import { IconButton } from '@cloud-notes/component';
import { IsFavoriteIcon } from '@cloud-notes/core/components/pure/icons';
import type { CollectionMeta } from '@cloud-notes/core/modules/collection';
import { CompatibleFavoriteItemsAdapter } from '@cloud-notes/core/modules/favorite';
import { WorkbenchLink } from '@cloud-notes/core/modules/workbench';
import { ViewLayersIcon } from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { type MouseEvent, useCallback } from 'react';

import { item, name, prefixIcon, suffixIcon } from './styles.css';

export const CollectionListItem = ({ meta }: { meta: CollectionMeta }) => {
  const favAdapter = useService(CompatibleFavoriteItemsAdapter);

  const isFavorite = useLiveData(favAdapter.isFavorite$(meta.id, 'collection'));

  const toggle = useCallback(
    (e: MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
      favAdapter.toggle(meta.id, 'collection');
    },
    [favAdapter, meta.id]
  );

  return (
    <WorkbenchLink to={`/collection/${meta.id}`} className={item}>
      <ViewLayersIcon className={prefixIcon} />
      <span className={name}>{meta.title}</span>
      <IconButton
        className={suffixIcon}
        onClick={toggle}
        icon={<IsFavoriteIcon favorite={isFavorite} />}
        size="24"
      />
    </WorkbenchLink>
  );
};
