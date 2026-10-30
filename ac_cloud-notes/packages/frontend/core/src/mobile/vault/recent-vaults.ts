// The list of recently opened vaults, newest first. Storage is injected (localStorage in
// the app, a Map in tests), the cap comes from build.json::vault.open.recent_max.

export type VaultKind = 'saf' | 'path' | 'store';

export interface RecentVault {
  /** SAF tree URI, absolute path, or store:<relative path> */
  root: string;
  name: string;
  kind: VaultKind;
  openedAt: number;
}

export interface KeyValueStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export const kindOfRoot = (root: string): VaultKind =>
  root.startsWith('content://') ? 'saf' : root.startsWith('store:') ? 'store' : 'path';

export function readRecent(store: KeyValueStore, key: string): RecentVault[] {
  try {
    const raw = JSON.parse(store.getItem(key) ?? '[]') as unknown;
    if (!Array.isArray(raw)) return [];
    return raw
      .filter(
        (r): r is RecentVault =>
          typeof r === 'object' && r !== null && typeof r.root === 'string' && typeof r.name === 'string'
      )
      .map(r => ({ root: r.root, name: r.name, kind: kindOfRoot(r.root), openedAt: Number(r.openedAt) || 0 }));
  } catch {
    return [];
  }
}

export function touchRecent(
  store: KeyValueStore,
  key: string,
  max: number,
  vault: { root: string; name: string },
  now = Date.now()
): RecentVault[] {
  const list = [
    { ...vault, kind: kindOfRoot(vault.root), openedAt: now },
    ...readRecent(store, key).filter(r => r.root !== vault.root),
  ].slice(0, Math.max(1, max));
  store.setItem(key, JSON.stringify(list));
  return list;
}

export function forgetRecent(store: KeyValueStore, key: string, root: string): RecentVault[] {
  const list = readRecent(store, key).filter(r => r.root !== root);
  store.setItem(key, JSON.stringify(list));
  return list;
}
