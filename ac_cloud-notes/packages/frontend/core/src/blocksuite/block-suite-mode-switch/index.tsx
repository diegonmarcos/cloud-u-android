import { RadioGroup, type RadioItem } from '@cloud-notes/component';
import { registerCloudNotesCommand } from '@cloud-notes/core/commands';
import { EditorService } from '@cloud-notes/core/modules/editor';
import { ViewService, WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { useI18n } from '@cloud-notes/i18n';
import { track } from '@cloud-notes/track';
import type { DocMode } from '@blocksuite/cloud-notes/model';
import { EdgelessIcon, PageIcon } from '@blocksuite/icons/rc';
import {
  useLiveData,
  useService,
  useServiceOptional,
} from '@toeverything/infra';
import { useCallback, useEffect, useMemo } from 'react';

import { switchItem } from './style.css';
import { EdgelessSwitchItem, PageSwitchItem } from './switch-items';

export interface EditorModeSwitchProps {
  pageId: string;
  isPublic?: boolean;
  publicMode?: DocMode;
}

const EdgelessRadioItem: RadioItem = {
  value: 'edgeless',
  label: <EdgelessSwitchItem />,
  testId: 'switch-edgeless-mode-button',
  className: switchItem,
};
const PageRadioItem: RadioItem = {
  value: 'page',
  label: <PageSwitchItem />,
  testId: 'switch-page-mode-button',
  className: switchItem,
};

export const EditorModeSwitch = () => {
  const t = useI18n();
  const editor = useService(EditorService).editor;
  const trash = useLiveData(editor.doc.trash$);
  const currentMode = useLiveData(editor.mode$);
  const view = useServiceOptional(ViewService)?.view;
  const workbench = useServiceOptional(WorkbenchService)?.workbench;
  const activeView = useLiveData(workbench?.activeView$);
  const isActiveView = activeView?.id && activeView?.id === view?.id;

  const togglePage = useCallback(() => {
    if (currentMode === 'page' || trash) return;
    editor.setMode('page');
    editor.setSelector(undefined);
    track.$.header.actions.switchPageMode({ mode: 'page' });
  }, [currentMode, editor, trash]);

  const toggleEdgeless = useCallback(() => {
    if (currentMode === 'edgeless' || trash) return;
    editor.setMode('edgeless');
    editor.setSelector(undefined);
    track.$.header.actions.switchPageMode({ mode: 'edgeless' });
  }, [currentMode, editor, trash]);

  const onModeChange = useCallback(
    (mode: DocMode) => {
      mode === 'page' ? togglePage() : toggleEdgeless();
    },
    [toggleEdgeless, togglePage]
  );

  const shouldHide = useCallback(
    (mode: DocMode) => trash && currentMode !== mode,
    [currentMode, trash]
  );

  useEffect(() => {
    if (trash || currentMode === undefined || !isActiveView) return;
    return registerCloudNotesCommand({
      id: 'cloud-notes:doc-mode-switch',
      category: 'editor:page',
      label:
        currentMode === 'page'
          ? t['com.cloud-notes.cmdk.switch-to-edgeless']()
          : t['com.cloud-notes.cmdk.switch-to-page'](),
      icon: currentMode === 'page' ? <EdgelessIcon /> : <PageIcon />,
      keyBinding: {
        binding: 'Alt+KeyS',
        capture: true,
      },
      run: () => onModeChange(currentMode === 'edgeless' ? 'page' : 'edgeless'),
    });
  }, [currentMode, isActiveView, onModeChange, t, trash]);

  return (
    <PureEditorModeSwitch
      mode={currentMode}
      setMode={onModeChange}
      hidePage={shouldHide('page')}
      hideEdgeless={shouldHide('edgeless')}
    />
  );
};

export interface PureEditorModeSwitchProps {
  mode?: DocMode;
  setMode?: (mode: DocMode) => void;
  hidePage?: boolean;
  hideEdgeless?: boolean;
}

export const PureEditorModeSwitch = ({
  mode,
  setMode,
  hidePage,
  hideEdgeless,
}: PureEditorModeSwitchProps) => {
  const items = useMemo(
    () => [
      ...(hidePage ? [] : [PageRadioItem]),
      ...(hideEdgeless ? [] : [EdgelessRadioItem]),
    ],
    [hideEdgeless, hidePage]
  );
  return (
    <RadioGroup
      iconMode
      itemHeight={24}
      borderRadius={8}
      padding={4}
      gap={8}
      value={mode}
      items={items}
      onChange={setMode}
    />
  );
};
