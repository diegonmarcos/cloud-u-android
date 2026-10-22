import { AIStarIcon } from '@blocksuite/cloud-notes/components/icons';
import { DocModeProvider } from '@blocksuite/cloud-notes/shared/services';
import {
  type SlashMenuActionItem,
  SlashMenuConfigExtension,
  type SlashMenuContext,
  type SlashMenuItem,
  type SlashMenuSubMenu,
} from '@blocksuite/cloud-notes/widgets/slash-menu';
import { MoreHorizontalIcon } from '@blocksuite/icons/lit';
import { html } from 'lit';

import { pageAIGroups } from '../_common/config';
import { handleInlineAskAIAction } from '../actions/doc-handler';
import type { AIItemConfig } from '../components/ai-item/types';
import {
  CLOUD_NOTES_AI_PANEL_WIDGET,
  type CloudNotesAIPanelWidget,
} from '../widgets/ai-panel/ai-panel';

export function AiSlashMenuConfigExtension() {
  const AIItems = pageAIGroups.flatMap(group => group.items);

  const iconWrapper = (icon: AIItemConfig['icon']) => {
    return html`<div style="color: var(--cloud-notes-primary-color)">
      ${typeof icon === 'function' ? html`${icon()}` : icon}
    </div>`;
  };

  const showWhenWrapper =
    (item?: AIItemConfig) =>
    ({ std }: SlashMenuContext) => {
      const root = std.host.store.root;
      if (!root) return false;
      const cloudNotesAIPanelWidget = std.view.getWidget(
        CLOUD_NOTES_AI_PANEL_WIDGET,
        root.id
      );
      if (cloudNotesAIPanelWidget === null) return false;

      const chain = std.host.command.chain();
      const docModeService = std.get(DocModeProvider);
      const editorMode = docModeService.getPrimaryMode(std.host.store.id);

      return item?.showWhen?.(chain, editorMode, std.host) ?? true;
    };

  const actionItemWrapper = (item: AIItemConfig): SlashMenuActionItem => ({
    ...basicItemConfig(item),
    action: ({ std }: SlashMenuContext) => {
      item?.handler?.(std.host);
    },
  });

  const subMenuWrapper = (item: AIItemConfig): SlashMenuSubMenu => {
    return {
      ...basicItemConfig(item),
      subMenu: (item.subItem ?? []).map<SlashMenuActionItem>(
        ({ type, handler }) => ({
          name: type,
          action: ({ std }) => handler?.(std.host),
        })
      ),
    };
  };

  const basicItemConfig = (item: AIItemConfig) => {
    return {
      name: item.name,
      icon: iconWrapper(item.icon),
      searchAlias: ['ai'],
      when: showWhenWrapper(item),
    };
  };

  let index = 0;
  const AIMenuItems: SlashMenuItem[] = [
    {
      name: 'Ask AI',
      icon: AIStarIcon,
      when: showWhenWrapper(),
      action: ({ std }) => {
        const root = std.host.store.root;
        if (!root) return;
        const cloudNotesAIPanelWidget = std.view.getWidget(
          CLOUD_NOTES_AI_PANEL_WIDGET,
          root.id
        ) as CloudNotesAIPanelWidget;
        handleInlineAskAIAction(cloudNotesAIPanelWidget.host);
      },
    },
    ...AIItems.filter(({ name }) =>
      ['Fix spelling', 'Fix grammar'].includes(name)
    ).map<SlashMenuActionItem>(item => ({
      ...actionItemWrapper(item),
      name: `${item.name} from above`,
      group: `1_CLOUD_NOTES AI@${index++}`,
    })),

    ...AIItems.filter(({ name }) =>
      ['Summarize', 'Continue writing'].includes(name)
    ).map<SlashMenuActionItem>(item => ({
      ...actionItemWrapper(item),
      group: `1_CLOUD_NOTES AI@${index++}`,
    })),

    {
      name: 'Action with above',
      icon: iconWrapper(MoreHorizontalIcon({ width: '24px', height: '24px' })),
      group: `1_CLOUD_NOTES AI@${index++}`,
      subMenu: [
        ...AIItems.filter(({ name }) =>
          ['Translate to', 'Change tone to'].includes(name)
        ).map(subMenuWrapper),

        ...AIItems.filter(({ name }) =>
          [
            'Improve writing',
            'Make it longer',
            'Make it shorter',
            'Generate outline',
            'Find actions',
          ].includes(name)
        ).map(actionItemWrapper),
      ],
    },
  ];

  return SlashMenuConfigExtension('ai', {
    items: AIMenuItems,
  });
}
