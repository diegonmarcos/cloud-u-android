# @blocksuite/affine-graph

Cloud Notes' Obsidian graph (spec `a0_docs/eng-specs/cloud-notes-hybrid.md`, Phase 4).

- `buildGraph({ docs, links, tags })` - nodes are live docs and tags, edges are doc links and tag membership.
  Trashed docs, links to unknown docs, self links and duplicates are dropped; the output is sorted, so it is a golden-testable value.
- `localGraph(model, centerId, depth)` - the undirected BFS neighbourhood (depth 1-3 in the UI).
- `orphans(model)` - doc nodes without any edge.
- `forceLayout(model, options)` - deterministic (seeded) Fruchterman-Reingold with a spatial grid for the repulsion, so it stays near-linear for thousands of nodes.

Pure TypeScript with no dependency: the renderer lives in `packages/frontend/core/src/mobile/views/graph`.
