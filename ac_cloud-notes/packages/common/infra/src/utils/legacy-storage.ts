/**
 * One-time copy of browser-storage entries written under the app's earlier
 * name, so a user's local workspace list, settings and feature flags survive
 * the rename of their storage keys. It only ever reads the old key and writes
 * the new one when that is still absent; old entries are left where they are.
 * Safe to call from several places: it runs once per page load.
 */
const LEGACY_KEYS: ReadonlyArray<readonly [legacy: string, current: string]> = [
  ['affine-local-workspace', 'cloud-notes-local-workspace'],
  ['affine-settings', 'cloud-notes-settings'],
];

const LEGACY_PREFIXES: ReadonlyArray<readonly [legacy: string, current: string]> = [
  ['global-state:affine-flag:', 'global-state:cloud-notes-flag:'],
];

let migrated = false;

export function migrateLegacyLocalStorage(
  storage: Storage | undefined = globalThis.localStorage
): void {
  if (migrated || !storage) return;
  migrated = true;
  try {
    for (const [legacy, current] of LEGACY_KEYS) {
      const value = storage.getItem(legacy);
      if (value !== null && storage.getItem(current) === null) {
        storage.setItem(current, value);
      }
    }
    const keys: string[] = [];
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i);
      if (key) keys.push(key);
    }
    for (const [legacy, current] of LEGACY_PREFIXES) {
      for (const key of keys) {
        if (!key.startsWith(legacy)) continue;
        const target = current + key.slice(legacy.length);
        const value = storage.getItem(key);
        if (value !== null && storage.getItem(target) === null) {
          storage.setItem(target, value);
        }
      }
    }
  } catch {
    // storage can be blocked or full; the app then starts with its defaults
  }
}
