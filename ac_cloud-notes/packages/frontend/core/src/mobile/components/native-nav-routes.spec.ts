import { describe, expect, test } from 'vitest';

import {
  drawerDocs,
  NATIVE_NAV_FIRST_PAGE,
  NATIVE_NAV_TARGETS,
  resolveNativeNav,
} from './native-nav-routes';

describe('native nav routes', () => {
  test('a section tap resolves to its first page', () => {
    expect(resolveNativeNav({ section: 'home' })).toEqual({
      kind: 'route',
      to: '/home',
    });
    expect(resolveNativeNav({ section: 'search', page: null })).toEqual({
      kind: 'route',
      to: '/search',
    });
  });

  test('every section has a first page that has a target', () => {
    for (const [section, page] of Object.entries(NATIVE_NAV_FIRST_PAGE)) {
      expect(NATIVE_NAV_TARGETS[`${section}/${page}`]).toBeDefined();
    }
  });

  test('planned targets never carry a route', () => {
    const planned = Object.values(NATIVE_NAV_TARGETS).filter(
      t => t.kind === 'planned'
    );
    expect(planned.length).toBeGreaterThan(0);
    for (const t of planned) expect(t).not.toHaveProperty('to');
  });

  test('unknown ids resolve to null', () => {
    expect(resolveNativeNav({ section: 'nope' })).toBeNull();
    expect(resolveNativeNav({ section: 'home', page: 'nope' })).toBeNull();
  });

  test('the drawer lists live pages, newest edit first, titled', () => {
    expect(
      drawerDocs([
        { id: 'old', title: 'Old', updatedDate: 1 },
        { id: 'gone', title: 'Trashed', trash: true, updatedDate: 9 },
        { id: 'new', title: '  New  ', updatedDate: 5 },
        { id: 'blank', title: ' ', updatedDate: 3 },
        { id: 'untitled', updatedDate: 2 },
      ])
    ).toEqual([
      { id: 'new', title: 'New' },
      { id: 'blank', title: 'Untitled' },
      { id: 'untitled', title: 'Untitled' },
      { id: 'old', title: 'Old' },
    ]);
  });

  test('the drawer list is capped', () => {
    const many = Array.from({ length: 100 }, (_, i) => ({
      id: `d${i}`,
      updatedDate: i,
    }));
    expect(drawerDocs(many, 40)).toHaveLength(40);
    expect(drawerDocs(many)[0].id).toBe('d99');
  });
});
