import { IconButton } from '@cloud-notes/component';
import { RenameModal } from '@cloud-notes/component/rename-modal';
import { NavigationPanelService } from '@cloud-notes/core/modules/navigation-panel';
import { TagService } from '@cloud-notes/core/modules/tag';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { AddTagIcon } from '@blocksuite/icons/rc';
import { useLiveData, useServices } from '@toeverything/infra';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { CollapsibleSection } from '../../layouts/collapsible-section';
import { NavigationPanelTagNode } from '../../nodes/tag';
import { NavigationPanelTreeRoot } from '../../tree';
import { RootEmpty } from './empty';
import * as styles from './styles.css';

export const NavigationPanelTags = () => {
  const { tagService, navigationPanelService } = useServices({
    TagService,
    NavigationPanelService,
  });
  const path = useMemo(() => ['tags'], []);
  const collapsed = useLiveData(navigationPanelService.collapsed$(path));
  const [creating, setCreating] = useState(false);
  const tags = useLiveData(tagService.tagList.tags$);

  const t = useI18n();

  const handleCreateNewTag = useCallback(
    (name: string) => {
      tagService.tagList.createTag(name, tagService.randomTagColor());
      track.$.navigationPanel.organize.createOrganizeItem({ type: 'tag' });
      navigationPanelService.setCollapsed(path, false);
    },
    [navigationPanelService, path, tagService]
  );

  useEffect(() => {
    if (collapsed) setCreating(false);
  }, [collapsed]);

  const handleOpenCreateModal = useCallback(() => {
    setCreating(true);
  }, []);

  return (
    <CollapsibleSection
      path={path}
      testId="navigation-panel-tags"
      headerClassName={styles.draggedOverHighlight}
      title={t['com.cloud-notes.rootAppSidebar.tags']()}
      actions={
        <div className={styles.iconContainer}>
          <IconButton
            data-testid="navigation-panel-bar-add-tag-button"
            onClick={handleOpenCreateModal}
            size="16"
            tooltip={t[
              'com.cloud-notes.rootAppSidebar.explorer.tag-section-add-tooltip'
            ]()}
          >
            <AddTagIcon />
          </IconButton>
          {creating && (
            <RenameModal
              open
              onOpenChange={setCreating}
              onRename={handleCreateNewTag}
              currentName={t['com.cloud-notes.rootAppSidebar.tags.new-tag']()}
            />
          )}
        </div>
      }
    >
      <NavigationPanelTreeRoot placeholder={<RootEmpty />}>
        {tags.map(tag => (
          <NavigationPanelTagNode
            key={tag.id}
            tagId={tag.id}
            reorderable={false}
            location={{
              at: 'navigation-panel:tags:list',
            }}
            parentPath={path}
          />
        ))}
      </NavigationPanelTreeRoot>
    </CollapsibleSection>
  );
};
