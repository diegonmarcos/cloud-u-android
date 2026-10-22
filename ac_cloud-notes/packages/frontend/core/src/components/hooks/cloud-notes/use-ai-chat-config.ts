// packages/frontend/core/src/blocksuite/ai/hooks/useChatPanelConfig.ts
import { AIPlaygroundService } from '@cloud-notes/core/modules/ai-button/services/playground';
import { AIReasoningService } from '@cloud-notes/core/modules/ai-button/services/reasoning';
import { CollectionService } from '@cloud-notes/core/modules/collection';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { DocDisplayMetaService } from '@cloud-notes/core/modules/doc-display-meta';
import { DocsSearchService } from '@cloud-notes/core/modules/docs-search';
import {
  type SearchCollectionMenuAction,
  type SearchDocMenuAction,
  SearchMenuService,
  type SearchTagMenuAction,
} from '@cloud-notes/core/modules/search-menu/services';
import { TagService } from '@cloud-notes/core/modules/tag';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { createSignalFromObservable } from '@blocksuite/cloud-notes/shared/utils';
import { useFramework } from '@toeverything/infra';

export function useAIChatConfig() {
  const framework = useFramework();

  const reasoningService = framework.get(AIReasoningService);
  const playgroundService = framework.get(AIPlaygroundService);
  const docDisplayMetaService = framework.get(DocDisplayMetaService);
  const workspaceService = framework.get(WorkspaceService);
  const searchMenuService = framework.get(SearchMenuService);
  const docsSearchService = framework.get(DocsSearchService);
  const tagService = framework.get(TagService);
  const collectionService = framework.get(CollectionService);
  const docsService = framework.get(DocsService);

  const reasoningConfig = {
    enabled: reasoningService.enabled,
    setEnabled: reasoningService.setEnabled,
  };

  const playgroundConfig = {
    visible: playgroundService.visible,
  };

  const docDisplayConfig = {
    getIcon: (docId: string) => {
      return docDisplayMetaService.icon$(docId, { type: 'lit' }).value;
    },
    getTitle: (docId: string) => {
      return docDisplayMetaService.title$(docId).value;
    },
    getTitleSignal: (docId: string) => {
      const title$ = docDisplayMetaService.title$(docId);
      return createSignalFromObservable(title$, '');
    },
    getDocMeta: (docId: string) => {
      const docRecord = docsService.list.doc$(docId).value;
      return docRecord?.meta$.value ?? null;
    },
    getDocPrimaryMode: (docId: string) => {
      const docRecord = docsService.list.doc$(docId).value;
      return docRecord?.primaryMode$.value ?? 'page';
    },
    getDoc: (docId: string) => {
      const doc = workspaceService.workspace.docCollection.getDoc(docId);
      return doc?.getStore() ?? null;
    },
    getReferenceDocs: (docIds: string[]) => {
      const docs$ = docsSearchService.watchRefsFrom(docIds);
      return createSignalFromObservable(docs$, []);
    },
    getTags: () => {
      const tagMetas$ = tagService.tagList.tagMetas$;
      return createSignalFromObservable(tagMetas$, []);
    },
    getTagTitle: (tagId: string) => {
      const tag$ = tagService.tagList.tagByTagId$(tagId);
      return tag$.value?.value$.value ?? '';
    },
    getTagPageIds: (tagId: string) => {
      const tag$ = tagService.tagList.tagByTagId$(tagId);
      if (!tag$) return [];
      return tag$.value?.pageIds$.value ?? [];
    },
    getCollections: () => {
      const collectionMetas$ = collectionService.collectionMetas$;
      return createSignalFromObservable(collectionMetas$, []);
    },
    getCollectionTitle: (collectionId: string) => {
      return (
        collectionService.collectionMetas$.value.find(
          collection => collection.id === collectionId
        )?.name ?? ''
      );
    },
    getCollectionPageIds: (collectionId: string) => {
      const collection$ = collectionService.collection$(collectionId);
      // TODO: lack of documents that meet the collection rules
      return collection$?.value?.info$.value.allowList ?? [];
    },
  };

  const searchMenuConfig = {
    addContextAvailable: workspaceService.workspace.flavour !== 'local',
    getDocMenuGroup: (
      query: string,
      action: SearchDocMenuAction,
      abortSignal: AbortSignal
    ) => {
      return searchMenuService.getDocMenuGroup(query, action, abortSignal);
    },
    getTagMenuGroup: (
      query: string,
      action: SearchTagMenuAction,
      abortSignal: AbortSignal
    ) => {
      return searchMenuService.getTagMenuGroup(query, action, abortSignal);
    },
    getCollectionMenuGroup: (
      query: string,
      action: SearchCollectionMenuAction,
      abortSignal: AbortSignal
    ) => {
      return searchMenuService.getCollectionMenuGroup(
        query,
        action,
        abortSignal
      );
    },
  };

  return {
    reasoningConfig,
    docDisplayConfig,
    searchMenuConfig,
    playgroundConfig,
  };
}
