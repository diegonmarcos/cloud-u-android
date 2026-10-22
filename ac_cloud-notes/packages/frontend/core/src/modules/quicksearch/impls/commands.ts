import {
  type CloudNotesCommand,
  CloudNotesCommandRegistry,
  type CommandCategory,
  PreconditionStrategy,
} from '@cloud-notes/core/commands';
import type { DocMode } from '@blocksuite/cloud-notes/model';
import { Entity, LiveData } from '@toeverything/infra';
import Fuse from 'fuse.js';

import type { GlobalContextService } from '../../global-context';
import type { QuickSearchSession } from '../providers/quick-search-provider';
import type { QuickSearchGroup } from '../types/group';
import type { QuickSearchItem } from '../types/item';
import { highlighter } from '../utils/highlighter';

const categories = {
  'cloud-notes:recent': {
    id: 'command:cloud-notes:recent',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.recent' },
    score: 10,
  },
  'cloud-notes:navigation': {
    id: 'command:cloud-notes:navigation',
    label: {
      i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.navigation',
    },
    score: 10,
  },
  'cloud-notes:creation': {
    id: 'command:cloud-notes:creation',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.creation' },
    score: 10,
  },
  'cloud-notes:general': {
    id: 'command:cloud-notes:general',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.general' },
    score: 10,
  },
  'cloud-notes:layout': {
    id: 'command:cloud-notes:layout',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.layout' },
    score: 10,
  },
  'cloud-notes:pages': {
    id: 'command:cloud-notes:pages',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.pages' },
    score: 10,
  },
  'cloud-notes:edgeless': {
    id: 'command:cloud-notes:edgeless',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.edgeless' },
    score: 10,
  },
  'cloud-notes:collections': {
    id: 'command:cloud-notes:collections',
    label: {
      i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.collections',
    },
    score: 10,
  },
  'cloud-notes:settings': {
    id: 'command:cloud-notes:settings',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.settings' },
    score: 10,
  },
  'cloud-notes:updates': {
    id: 'command:cloud-notes:updates',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.updates' },
    score: 10,
  },
  'cloud-notes:help': {
    id: 'command:cloud-notes:help',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.help' },
    score: 10,
  },
  'editor:edgeless': {
    id: 'command:editor:edgeless',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.editor.edgeless' },
    score: 10,
  },
  'editor:insert-object': {
    id: 'command:editor:insert-object',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.editor.insert-object' },
    score: 10,
  },
  'editor:page': {
    id: 'command:editor:page',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.editor.page' },
    score: 10,
  },
  'cloud-notes:results': {
    id: 'command:cloud-notes:results',
    label: { i18nKey: 'com.cloud-notes.cmdk.cloud-notes.category.results' },
    score: 10,
  },
} satisfies Required<{
  [key in CommandCategory]: QuickSearchGroup & { id: `command:${key}` };
}>;

function filterCommandByContext(
  command: CloudNotesCommand,
  context: {
    docMode: DocMode | undefined;
  }
) {
  if (command.preconditionStrategy === PreconditionStrategy.Always) {
    return true;
  }
  if (command.preconditionStrategy === PreconditionStrategy.InEdgeless) {
    return context.docMode === 'edgeless';
  }
  if (command.preconditionStrategy === PreconditionStrategy.InPaper) {
    return context.docMode === 'page';
  }
  if (command.preconditionStrategy === PreconditionStrategy.InPaperOrEdgeless) {
    return !!context.docMode;
  }
  if (command.preconditionStrategy === PreconditionStrategy.Never) {
    return false;
  }
  if (typeof command.preconditionStrategy === 'function') {
    return command.preconditionStrategy();
  }
  return true;
}

function getAllCommand(context: { docMode: DocMode | undefined }) {
  const commands = CloudNotesCommandRegistry.getAll();
  return commands.filter(command => {
    return filterCommandByContext(command, context);
  });
}

export class CommandsQuickSearchSession
  extends Entity
  implements QuickSearchSession<'commands', CloudNotesCommand>
{
  constructor(private readonly contextService: GlobalContextService) {
    super();
  }

  query$ = new LiveData('');

  items$ = LiveData.computed(get => {
    const query = get(this.query$);
    const docMode =
      get(this.contextService.globalContext.docMode.$) ?? undefined;
    const commands = getAllCommand({ docMode });

    const fuse = new Fuse(commands, {
      keys: [{ name: 'label.title', weight: 2 }, 'label.subTitle'],
      includeMatches: true,
      includeScore: true,
      ignoreLocation: true,
      threshold: 0.0,
    });

    const result = query
      ? fuse.search(query)
      : commands.map(item => ({ item, matches: [], score: 0 }));

    return result.map<QuickSearchItem<'commands', CloudNotesCommand>>(
      ({ item, matches, score = 1 }) => {
        const normalizedRange = ([start, end]: [number, number]) =>
          [
            start,
            end + 1 /* in fuse, the `end` is different from the `substring` */,
          ] as [number, number];
        const titleMatches = matches
          ?.filter(match => match.key === 'label.title')
          .flatMap(match => match.indices.map(normalizedRange));
        const subTitleMatches = matches
          ?.filter(match => match.key === 'label.subTitle')
          .flatMap(match => match.indices.map(normalizedRange));

        return {
          id: 'command:' + item.id,
          source: 'commands',
          label: {
            title:
              highlighter(
                item.label.title,
                '<b>',
                '</b>',
                titleMatches ?? []
              ) ?? item.label.title,
            subTitle: item.label.subTitle
              ? (highlighter(
                  item.label.subTitle,
                  '<b>',
                  '</b>',
                  subTitleMatches ?? []
                ) ?? item.label.subTitle)
              : undefined,
          },
          group: categories[item.category],
          score:
            1 -
            score /* in fuse, the smaller the score, the better the match, so we need to reverse it */,
          icon: item.icon,
          keyBinding: item.keyBinding?.binding,
          payload: item,
        };
      }
    );
  });

  query(query: string) {
    this.query$.next(query);
  }
}
