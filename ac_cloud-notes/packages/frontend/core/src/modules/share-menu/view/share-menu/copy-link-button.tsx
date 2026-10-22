import { Button, Menu, MenuItem, MenuTrigger } from '@cloud-notes/component';
import {
  getSelectedNodes,
  useSharingUrl,
} from '@cloud-notes/core/components/hooks/cloud-notes/use-share-url';
import { getDefaultShareMode } from '@cloud-notes/core/components/hooks/cloud-notes/use-share-url.utils';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { useI18n } from '@cloud-notes/i18n';
import type { DocMode } from '@blocksuite/cloud-notes/model';
import { BlockIcon, EdgelessIcon, PageIcon } from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import clsx from 'clsx';
import { useCallback, useMemo } from 'react';

import * as styles from './copy-link-button.css';

export const CopyLinkButton = ({
  workspaceId,
  secondary,
}: {
  secondary?: boolean;
  workspaceId: string;
}) => {
  const t = useI18n();

  const editor = useService(EditorService).editor;
  const currentMode = useLiveData(editor.mode$);
  const editorContainer = useLiveData(editor.editorContainer$);

  const { blockIds, elementIds } = useMemo(
    () => getSelectedNodes(editorContainer?.host || null, currentMode),
    [editorContainer, currentMode]
  );
  const { onClickCopyLink } = useSharingUrl({
    workspaceId,
    pageId: editor.doc.id,
  });

  const onCopyPageLink = useCallback(() => {
    onClickCopyLink('page' as DocMode);
  }, [onClickCopyLink]);
  const onCopyEdgelessLink = useCallback(() => {
    onClickCopyLink('edgeless' as DocMode);
  }, [onClickCopyLink]);
  const onCopyBlockLink = useCallback(() => {
    onClickCopyLink(currentMode, blockIds, elementIds);
  }, [onClickCopyLink, currentMode, blockIds, elementIds]);

  const onCopyLink = useCallback(() => {
    onClickCopyLink(getDefaultShareMode(currentMode));
  }, [currentMode, onClickCopyLink]);

  return (
    <div
      className={clsx(styles.copyLinkContainerStyle, { secondary: secondary })}
    >
      <Button
        className={styles.copyLinkButtonStyle}
        onClick={onCopyLink}
        withoutHover
        variant={secondary ? 'secondary' : 'primary'}
      >
        <span
          className={clsx(styles.copyLinkLabelStyle, {
            secondary: secondary,
          })}
        >
          {t['com.cloud-notes.share-menu.copy']()}
        </span>
        {BUILD_CONFIG.isDesktopEdition && (
          <span
            className={clsx(styles.copyLinkShortcutStyle, {
              secondary: secondary,
            })}
          >
            {environment.isMacOs ? '⌘ + ⌥ + C' : 'Ctrl + Shift + C'}
          </span>
        )}
      </Button>
      <Menu
        contentOptions={{
          align: 'end',
        }}
        items={
          <>
            <MenuItem
              prefixIcon={<PageIcon />}
              onSelect={onCopyPageLink}
              data-testid="share-link-menu-copy-page"
            >
              {t['com.cloud-notes.share-menu.copy.page']()}
            </MenuItem>
            <MenuItem
              prefixIcon={<EdgelessIcon />}
              onSelect={onCopyEdgelessLink}
              data-testid="share-link-menu-copy-edgeless"
            >
              {t['com.cloud-notes.share-menu.copy.edgeless']()}
            </MenuItem>
            <MenuItem
              prefixIcon={<BlockIcon />}
              onSelect={onCopyBlockLink}
              disabled={blockIds.length + elementIds.length === 0}
            >
              {t['com.cloud-notes.share-menu.copy.block']()}
            </MenuItem>
          </>
        }
      >
        <MenuTrigger
          variant={secondary ? 'secondary' : 'primary'}
          className={clsx(styles.copyLinkTriggerStyle, {
            secondary: secondary,
          })}
          data-testid="share-menu-copy-link-button"
          suffixStyle={{ width: 20, height: 20 }}
          withoutHover
        />
      </Menu>
    </div>
  );
};
