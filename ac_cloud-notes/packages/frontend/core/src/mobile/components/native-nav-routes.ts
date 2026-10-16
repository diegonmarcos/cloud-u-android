// Cloud Notes (hybrid roadmap, Phase 0b): the web half of the native bottom
// nav. libs:bottomnav's island + page strips live in the Compose shell and are
// declared in build.json::ui; they know only section/page IDS. This table is
// the one place those ids become AFFiNE workbench routes or actions, so the
// native shell never learns a URL. `planned` targets have no screen in our
// AFFiNE copy yet (see a0_docs/eng-specs/cloud-notes-hybrid.md): they toast
// instead of navigating, so a tap is never silent and never lands on a 404.

export const NATIVE_NAV_EVENT = 'cloud-notes:nav';

export interface NativeNavDetail {
  section: string;
  /** page id inside the section, or null for the section's first page */
  page?: string | null;
}

export type NativeNavTarget =
  | { kind: 'route'; to: string }
  | { kind: 'action'; action: 'new-page' | 'new-board' }
  | { kind: 'planned'; phase: number; what: string };

/** key = `<section>/<page>`; every build.json::ui page id has exactly one row. */
export const NATIVE_NAV_TARGETS: Record<string, NativeNavTarget> = {
  'home/recent': { kind: 'route', to: '/home' },
  'home/daily': { kind: 'route', to: '/journals' },
  'pages/all': { kind: 'route', to: '/all' },
  'pages/tags': { kind: 'route', to: '/tag' },
  'pages/collections': { kind: 'route', to: '/collection' },
  'pages/new': { kind: 'action', action: 'new-page' },
  'canvas/boards': { kind: 'route', to: '/all' },
  'canvas/new': { kind: 'action', action: 'new-board' },
  'graph/global': { kind: 'planned', phase: 4, what: 'Global graph' },
  'graph/local': { kind: 'planned', phase: 4, what: 'Local graph' },
  'graph/tags': { kind: 'route', to: '/tag' },
  'search/search': { kind: 'route', to: '/search' },
  'search/ask': { kind: 'planned', phase: 7, what: 'Ask (AI)' },
};

/** First page of each section: what tapping the island item opens. */
export const NATIVE_NAV_FIRST_PAGE: Record<string, string> = {
  home: 'recent',
  pages: 'all',
  canvas: 'boards',
  graph: 'global',
  search: 'search',
};

export function resolveNativeNav(
  detail: NativeNavDetail
): NativeNavTarget | null {
  const page = detail.page || NATIVE_NAV_FIRST_PAGE[detail.section];
  if (!page) return null;
  return NATIVE_NAV_TARGETS[`${detail.section}/${page}`] ?? null;
}

/** True inside the Compose shell, which sets this before the bundle boots. */
export function hasNativeNav(): boolean {
  return (
    typeof window !== 'undefined' &&
    (window as unknown as { cloudNotesNativeNav?: boolean })
      .cloudNotesNativeNav === true
  );
}
