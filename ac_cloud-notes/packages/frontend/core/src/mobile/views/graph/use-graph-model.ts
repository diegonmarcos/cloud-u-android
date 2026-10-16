import { DocsService } from '@affine/core/modules/doc';
import { DocsSearchService } from '@affine/core/modules/docs-search';
import { TagService } from '@affine/core/modules/tag';
import { buildGraph, type GraphModel } from '@blocksuite/affine-graph';
import { LiveData, useLiveData, useService } from '@toeverything/infra';
import { useMemo } from 'react';

/**
 * The workspace's graph (docs, doc links, tags), rebuilt whenever a title, a
 * trash flag, a link or a tag membership changes. Pure assembly: the builder
 * and its golden tests live in @blocksuite/affine-graph.
 */
export function useGraphModel(): GraphModel {
  const docList = useService(DocsService).list;
  const search = useService(DocsSearchService);
  const tagList = useService(TagService).tagList;

  const docs = useLiveData(
    useMemo(
      () =>
        LiveData.computed(get =>
          get(docList.docs$).map(d => {
            const meta = get(d.meta$);
            return { id: d.id, title: meta.title, trash: meta.trash };
          })
        ),
      [docList]
    )
  );
  const idsKey = docs.map(d => d.id).join(',');

  const refsBySource = useLiveData(
    useMemo(
      () =>
        LiveData.from<Map<string, { docId: string }[]>>(
          search.watchRefsBySourceFrom(idsKey ? idsKey.split(',') : []),
          new Map()
        ),
      [search, idsKey]
    )
  );

  const tags = useLiveData(
    useMemo(
      () =>
        LiveData.computed(get =>
          get(tagList.tags$).map(t => ({
            id: t.id,
            name: get(t.value$),
            docIds: get(t.pageIds$),
          }))
        ),
      [tagList]
    )
  );

  return useMemo(() => {
    const links: { from: string; to: string }[] = [];
    for (const [from, refs] of refsBySource) {
      for (const r of refs) links.push({ from, to: r.docId });
    }
    return buildGraph({ docs, links, tags });
  }, [docs, refsBySource, tags]);
}
