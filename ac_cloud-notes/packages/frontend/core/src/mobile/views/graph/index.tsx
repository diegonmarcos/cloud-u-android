import { useThemeColorV2 } from '@cloud-notes/component';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import {
  backlinks,
  filterGraph,
  forceLayout,
  type GraphNode,
  localGraph,
  orphans,
} from '@blocksuite/cloud-notes-graph';
import { useService } from '@toeverything/infra';
import { type CSSProperties, useCallback, useMemo, useState } from 'react';

import { PageHeader } from '../../components';
import { getLastDocId } from '../../components/native-nav-routes';
import { GraphCanvas } from './graph-canvas';
import { useGraphModel } from './use-graph-model';

const SIZE = 1000;

const chip = (on: boolean): CSSProperties => ({
  padding: '6px 12px',
  borderRadius: 16,
  border: '1px solid rgba(140,150,170,0.5)',
  background: on ? 'rgba(122,140,255,0.28)' : 'transparent',
  color: 'inherit',
  fontSize: 13,
});

const row: CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'center',
  padding: '6px 16px',
  flexWrap: 'wrap',
};

/**
 * Obsidian-style graph (Phase 4): the whole workspace (`mode="global"`) or the
 * neighbourhood of the note that was open (`mode="local"`, depth 1-3), with
 * tag nodes, an orphans filter, a highlight search and the backlinks list.
 */
export const GraphView = ({ mode }: { mode: 'global' | 'local' }) => {
  useThemeColorV2('layer/background/mobile/primary');
  const workbench = useService(WorkbenchService).workbench;
  const full = useGraphModel();

  const [depth, setDepth] = useState(2);
  const [showTags, setShowTags] = useState(true);
  const [showOrphans, setShowOrphans] = useState(mode === 'global');
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<string | null>(null);

  const wanted = mode === 'local' ? (picked ?? getLastDocId()) : null;
  // the centre only counts once it is a live doc of this workspace
  const centerId =
    wanted !== null && full.nodes.some(n => n.id === wanted) ? wanted : null;
  const centerKnown = centerId !== null;

  const model = useMemo(() => {
    let g = full;
    if (mode === 'local') {
      g = centerId ? localGraph(g, centerId, depth) : { nodes: [], edges: [] };
    }
    if (!showTags) g = filterGraph(g, n => n.kind !== 'tag');
    if (!showOrphans && mode === 'global') {
      const lone = new Set(orphans(g).map(n => n.id));
      g = filterGraph(g, n => !lone.has(n.id));
    }
    return g;
  }, [full, mode, centerId, depth, showTags, showOrphans]);

  const positions = useMemo(
    () => forceLayout(model, { width: SIZE, height: SIZE, seed: 1 }),
    [model]
  );

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    return new Set(
      q ? model.nodes.filter(n => n.label.toLowerCase().includes(q)).map(n => n.id) : []
    );
  }, [model, query]);

  const open = useCallback(
    (n: GraphNode) => {
      if (n.kind === 'tag') workbench.open(`/tag/${n.id.slice('tag:'.length)}`);
      else workbench.openDoc(n.id);
    },
    [workbench]
  );

  const docCount = model.nodes.filter(n => n.kind === 'doc').length;
  const incoming = centerId ? backlinks(full, centerId) : [];
  const outgoing = centerId
    ? full.edges
        .filter(e => e.kind === 'link' && e.source === centerId)
        .map(e => full.nodes.find(n => n.id === e.target))
        .filter((n): n is GraphNode => !!n)
    : [];

  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', height: '100%' }}
      data-testid={`graph-${mode}`}
    >
      <PageHeader back={false}>
        {mode === 'global' ? 'Graph' : 'Local graph'}
      </PageHeader>
      <div style={row}>
        <input
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="Highlight a note or tag"
          style={{ ...chip(false), flex: 1, minWidth: 140 }}
          data-testid="graph-search"
        />
        <button type="button" style={chip(showTags)} onClick={() => setShowTags(v => !v)}>
          Tags
        </button>
        {mode === 'global' ? (
          <button type="button" style={chip(showOrphans)} onClick={() => setShowOrphans(v => !v)}>
            Orphans
          </button>
        ) : (
          [1, 2, 3].map(d => (
            <button key={d} type="button" style={chip(depth === d)} onClick={() => setDepth(d)}>
              {d} {d === 1 ? 'hop' : 'hops'}
            </button>
          ))
        )}
        <span style={{ fontSize: 12, opacity: 0.7 }}>
          {docCount} notes, {model.edges.length} links
        </span>
      </div>

      {mode === 'local' && !centerKnown ? (
        <div style={{ padding: 16 }} data-testid="graph-pick">
          <p>Open a note first, then come back here: the local graph follows the note you were reading. Or pick one:</p>
          {full.nodes
            .filter(n => n.kind === 'doc')
            .slice(0, 30)
            .map(n => (
              <button
                key={n.id}
                type="button"
                style={{ ...chip(false), display: 'block', margin: '6px 0', width: '100%', textAlign: 'left' }}
                onClick={() => setPicked(n.id)}
              >
                {n.label}
              </button>
            ))}
          {full.nodes.every(n => n.kind !== 'doc') ? <p>There are no notes yet.</p> : null}
        </div>
      ) : model.nodes.length === 0 ? (
        <div style={{ padding: 16 }} data-testid="graph-empty">
          Nothing to draw yet: create notes and link them with [[ ]] or the @ menu, or tag them.
        </div>
      ) : (
        <div style={{ flex: 1, minHeight: 240 }}>
          <GraphCanvas
            model={model}
            positions={positions}
            centerId={centerId}
            matches={matches}
            onOpen={open}
            size={SIZE}
          />
        </div>
      )}

      {mode === 'local' && centerKnown ? (
        <div style={{ maxHeight: '28%', overflow: 'auto', padding: '4px 16px 96px' }} data-testid="graph-backlinks">
          <strong>Linked from ({incoming.length})</strong>
          {incoming.length === 0 ? <div style={{ opacity: 0.7 }}>No note links here yet.</div> : null}
          {incoming.map(n => (
            <button key={n.id} type="button" style={{ ...chip(false), display: 'block', margin: '4px 0' }} onClick={() => open(n)}>
              {n.label}
            </button>
          ))}
          <div style={{ marginTop: 8 }}>
            <strong>Links to ({outgoing.length})</strong>
          </div>
          {outgoing.map(n => (
            <button key={n.id} type="button" style={{ ...chip(false), display: 'block', margin: '4px 0' }} onClick={() => open(n)}>
              {n.label}
            </button>
          ))}
        </div>
      ) : (
        <div style={{ height: 88 }} />
      )}
    </div>
  );
};
