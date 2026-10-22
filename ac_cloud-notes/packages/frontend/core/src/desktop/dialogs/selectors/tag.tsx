import { Modal, toast } from '@cloud-notes/component';
import {
  FavoriteTag,
  type ListItem,
  ListTableHeader,
  tagHeaderColsDef,
  TagListItemRenderer,
  type TagMeta,
  VirtualizedList,
} from '@cloud-notes/core/components/page-list';
import { SelectorLayout } from '@cloud-notes/core/components/page-list/selector/selector-layout';
import type {
  DialogComponentProps,
  WORKSPACE_DIALOG_SCHEMA,
} from '@cloud-notes/core/modules/dialogs';
import { FavoriteService } from '@cloud-notes/core/modules/favorite';
import { TagService } from '@cloud-notes/core/modules/tag';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { useLiveData, useService } from '@toeverything/infra';
import { cssVar } from '@toeverything/theme';
import { useCallback, useMemo, useState } from 'react';

const FavoriteOperation = ({ tag }: { tag: ListItem }) => {
  const t = useI18n();
  const favoriteService = useService(FavoriteService);
  const isFavorite = useLiveData(
    favoriteService.favoriteList.isFavorite$('tag', tag.id)
  );

  const onToggleFavoriteCollection = useCallback(() => {
    favoriteService.favoriteList.toggle('tag', tag.id);
    toast(
      isFavorite
        ? t['com.cloud-notes.toastMessage.removedFavorites']()
        : t['com.cloud-notes.toastMessage.addedFavorites']()
    );
  }, [favoriteService.favoriteList, tag.id, isFavorite, t]);

  return (
    <FavoriteTag
      style={{ marginRight: 8 }}
      onClick={onToggleFavoriteCollection}
      active={isFavorite}
    />
  );
};

export const TagSelectorDialog = ({
  close,
  init: selectedTagIds,
}: DialogComponentProps<WORKSPACE_DIALOG_SCHEMA['tag-selector']>) => {
  const t = useI18n();

  const workspace = useService(WorkspaceService).workspace;
  const tagList = useService(TagService).tagList;

  const [selection, setSelection] = useState(selectedTagIds);
  const [keyword, setKeyword] = useState('');
  const tagMetas: TagMeta[] = useLiveData(tagList.tagMetas$);

  const filteredTagMetas = useMemo(() => {
    return tagMetas.filter(tag => {
      const reg = new RegExp(keyword, 'i');
      return reg.test(tag.name);
    });
  }, [keyword, tagMetas]);

  const tagItemRenderer = useCallback((item: ListItem) => {
    return <TagListItemRenderer {...item} />;
  }, []);

  const tagOperationRenderer = useCallback((item: ListItem) => {
    return <FavoriteOperation tag={item} />;
  }, []);

  const tagHeaderRenderer = useCallback(() => {
    return <ListTableHeader headerCols={tagHeaderColsDef} />;
  }, []);

  return (
    <Modal
      open
      onOpenChange={() => close()}
      withoutCloseButton
      width="calc(100% - 32px)"
      height="80%"
      contentOptions={{
        style: {
          padding: 0,
          maxWidth: 976,
          background: cssVar('backgroundPrimaryColor'),
        },
      }}
    >
      <SelectorLayout
        searchPlaceholder={t['com.cloud-notes.selector-tag.search.placeholder']()}
        selectedCount={selection.length}
        onSearch={setKeyword}
        onConfirm={() => close(selection)}
        onCancel={close}
        onClear={() => setSelection([])}
      >
        <VirtualizedList
          selectable={true}
          draggable={false}
          selectedIds={selection}
          onSelectedIdsChange={setSelection}
          items={filteredTagMetas}
          docCollection={workspace.docCollection}
          itemRenderer={tagItemRenderer}
          operationsRenderer={tagOperationRenderer}
          headerRenderer={tagHeaderRenderer}
        />
      </SelectorLayout>
    </Modal>
  );
};
