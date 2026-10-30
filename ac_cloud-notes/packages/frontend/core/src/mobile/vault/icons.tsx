import type { ReactElement } from 'react';

// Small stroke icons for the ribbon and panes (24x24 grid). Names are the ones
// build.json::vault.layout.ribbon[].icon uses.
const PATHS: Record<string, string> = {
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm9 16-4-4',
  jump: 'M4 12h12m-5-5 5 5-5 5M20 5v14',
  graph: 'M6 7a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm12 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM8 21a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM8 6l8 5M16 14l-6 4',
  canvas: 'M3 4h7v7H3zm11 0h7v4h-7zm0 8h7v8h-7zM3 15h7v5H3z',
  terminal: 'M4 5h16v14H4zm4 4 3 3-3 3m5 0h4',
  gear: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zm8 3-2-.7a6 6 0 0 0-.6-1.4l1-1.9-2.1-2.1-1.9 1a6 6 0 0 0-1.4-.6L12 4l-.7 2.3a6 6 0 0 0-1.4.6l-1.9-1-2.1 2.1 1 1.9a6 6 0 0 0-.6 1.4L4 12l2.3.7a6 6 0 0 0 .6 1.4l-1 1.9 2.1 2.1 1.9-1a6 6 0 0 0 1.4.6L12 20l.7-2.3a6 6 0 0 0 1.4-.6l1.9 1 2.1-2.1-1-1.9a6 6 0 0 0 .6-1.4z',
  vault: 'M4 5h16v14H4zm8 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm0 2v4',
  file: 'M6 3h8l4 4v14H6zm8 0v4h4',
  chevron: 'm9 6 6 6-6 6',
  plus: 'M12 5v14M5 12h14',
  close: 'm6 6 12 12M18 6 6 18',
  menu: 'M4 6h16M4 12h16M4 18h16',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zm10-3a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  edit: 'M4 20h4L19 9l-4-4L4 16zm9-13 4 4',
  panelLeft: 'M3 4h18v16H3zm6 0v16',
  panelRight: 'M3 4h18v16H3zm12 0v16',
};

export const Icon = ({ name, size = 20 }: { name: string; size?: number }): ReactElement => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d={PATHS[name] ?? PATHS.file} />
  </svg>
);
