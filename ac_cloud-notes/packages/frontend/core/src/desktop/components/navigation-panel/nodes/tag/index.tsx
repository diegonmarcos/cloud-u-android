import {
  type DropTargetDropEvent,
  type DropTargetOptions,
  toast,
} from '@cloud-notes/component';
import { GlobalContextService } from '@cloud-notes/core/modules/global-context';
import { NavigationPanelService } from '@cloud-notes/core/modules/navigation-panel';
import type { Tag } from '@cloud-notes/core/modules/tag';
import { TagService } from '@cloud-notes/core/modules/tag';
import type { CloudNotesDNDData } from '@cloud-notes/core/types/dnd';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import { useLiveData, useService, useServices } from '@toeverything/infra';
import clsx from 'clsx';
import { useCallback, useMemo } from 'react';

import {
  NavigationPanelTreeNode,
  type NavigationPanelTreeNodeDropEffect,
} from '../../tree';
import { NavigationPanelDocNode } from '../doc';
import type { GenericNavigationPanelNode } from '../types';
import { Empty } from './empty';
import { useNavigationPanelTagNodeOperations } from './operations';
import * as styles from './styles.css';

export const NavigationPanelTagNode = ({
  tagId,
  onDrop,
  location,
  reorderable,
  operations: additionalOperations,
  dropEffect,
  canDrop,
  parentPath,
}: {
  tagId: string;
} & GenericNavigationPanelNode) => {
  const t = useI18n();
  const { tagService, globalContextService } = useServices({
    TagService,
    GlobalContextService,
  });
  const navigationPanelService = useService(NavigationPanelService);
  const active =
    useLiveData(globalContextService.globalContext.tagId.$) === tagId;
  const path = useMemo(
    () => [...(parentPath ?? []), `tag-${tagId}`],
    [parentPath, tagId]
  );
  const collapsed = useLiveData(navigationPanelService.collapsed$(path));
  const setCollapsed = useCallback(
    (value: boolean) => {
      navigationPanelService.setCollapsed(path, value);
    },
    [navigationPanelService, path]
  );

  const tagRecord = useLiveData(tagService.tagList.tagByTagId$(tagId));
  const tagColor = useLiveData(tagRecord?.color$);
  const tagName = useLiveData(tagRecord?.value$);

  const Icon = useCallback(
    ({ className }: { className?: string }) => {
      return (
        <div className={clsx(styles.tagIconContainer, className)}>
          <div
            className={styles.tagIcon}
            style={{
              backgroundColor: tagColor,
            }}
          ></div>
        </div>
      );
    },
    [tagColor]
  );

  const dndData = useMemo(() => {
    return {
      draggable: {
        entity: {
          type: 'tag',
          id: tagId,
        },
        from: location,
      },
      dropTarget: {
        at: 'navigation-panel:tag',
      },
    } satisfies CloudNotesDNDData;
  }, [location, tagId]);

  const handleRename = useCallback(
    (newName: string) => {
      if (tagRecord && tagRecord.value$.value !== newName) {
        tagRecord.rename(newName);
        track.$.navigationPanel.organize.renameOrganizeItem({
          type: 'tag',
        });
      }
    },
    [tagRecord]
  );

  const handleDropOnTag = useCallback(
    (data: DropTargetDropEvent<CloudNotesDNDData>) => {
      if (data.treeInstruction?.type === 'make-child' && tagRecord) {
        if (data.source.data.entity?.type === 'doc') {
          tagRecord.tag(data.source.data.entity.id);
          track.$.navigationPanel.tags.tagDoc({
            control: 'drag',
          });
          track.$.navigationPanel.tags.drop({
            type: data.source.data.entity.type,
          });
        } else {
          toast(t['com.cloud-notes.rootAppSidebar.tag.doc-only']());
        }
      } else {
        onDrop?.(data);
      }
    },
    [onDrop, t, tagRecord]
  );

  const handleDropEffectOnTag = useCallback<NavigationPanelTreeNodeDropEffect>(
    data => {
      if (data.treeInstruction?.type === 'make-child') {
        if (data.source.data.entity?.type === 'doc') {
          return 'link';
        }
      } else {
        return dropEffect?.(data);
      }
      return;
    },
    [dropEffect]
  );

  const handleDropOnPlaceholder = useCallback(
    (data: DropTargetDropEvent<CloudNotesDNDData>) => {
      if (tagRecord) {
        if (data.source.data.entity?.type === 'doc') {
          tagRecord.tag(data.source.data.entity.id);
        } else {
          toast(t['com.cloud-notes.rootAppSidebar.tag.doc-only']());
        }
      }
    },
    [t, tagRecord]
  );

  const handleCanDrop = useMemo<DropTargetOptions<CloudNotesDNDData>['canDrop']>(
    () => args => {
      const entityType = args.source.data.entity?.type;
      return args.treeInstruction?.type !== 'make-child'
        ? ((typeof canDrop === 'function' ? canDrop(args) : canDrop) ?? true)
        : entityType === 'doc';
    },
    [canDrop]
  );

  const operations = useNavigationPanelTagNodeOperations(
    tagId,
    useMemo(
      () => ({
        openNodeCollapsed: () => setCollapsed(false),
      }),
      [setCollapsed]
    )
  );

  const finalOperations = useMemo(() => {
    if (additionalOperations) {
      return [...operations, ...additionalOperations];
    }
    return operations;
  }, [additionalOperations, operations]);

  if (!tagRecord) {
    return null;
  }

  return (
    <NavigationPanelTreeNode
      icon={Icon}
      name={tagName || t['Untitled']()}
      dndData={dndData}
      onDrop={handleDropOnTag}
      renameable
      collapsed={collapsed}
      setCollapsed={setCollapsed}
      to={`/tag/${tagId}`}
      active={active}
      reorderable={reorderable}
      onRename={handleRename}
      canDrop={handleCanDrop}
      childrenPlaceholder={<Empty onDrop={handleDropOnPlaceholder} />}
      operations={finalOperations}
      dropEffect={handleDropEffectOnTag}
      data-testid={`navigation-panel-tag-${tagId}`}
      explorerIconConfig={{
        where: 'tag',
        id: tagId,
      }}
    >
      <NavigationPanelTagNodeDocs tag={tagRecord} path={path} />
    </NavigationPanelTreeNode>
  );
};

/**
 * the `tag.pageIds$` has a performance issue,
 * so we split the tag node children into a separate component,
 * so it won't be rendered when the tag node is collapsed.
 */
export const NavigationPanelTagNodeDocs = ({
  tag,
  path,
}: {
  tag: Tag;
  path: string[];
}) => {
  const tagDocIds = useLiveData(tag.pageIds$);

  return tagDocIds.map(docId => (
    <NavigationPanelDocNode
      key={docId}
      docId={docId}
      reorderable={false}
      location={{
        at: 'navigation-panel:tags:docs',
      }}
      parentPath={path}
    />
  ));
};
