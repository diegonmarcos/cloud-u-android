import { beforeEach, describe, expect, test, vi } from 'vitest';

class MemoryStorage implements Storage {
  private readonly map = new Map<string, string>();
  get length() {
    return this.map.size;
  }
  clear() {
    this.map.clear();
  }
  getItem(key: string) {
    return this.map.get(key) ?? null;
  }
  key(index: number) {
    return [...this.map.keys()][index] ?? null;
  }
  removeItem(key: string) {
    this.map.delete(key);
  }
  setItem(key: string, value: string) {
    this.map.set(key, value);
  }
}

describe('migrateLegacyLocalStorage', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  test('copies the local workspace list and the settings to the new keys', async () => {
    const { migrateLegacyLocalStorage } = await import('../legacy-storage');
    const storage = new MemoryStorage();
    storage.setItem('affine-local-workspace', '["w1","w2"]');
    storage.setItem('affine-settings', '{"autoCheckUpdate":false}');
    migrateLegacyLocalStorage(storage);
    expect(storage.getItem('cloud-notes-local-workspace')).toBe('["w1","w2"]');
    expect(storage.getItem('cloud-notes-settings')).toBe(
      '{"autoCheckUpdate":false}'
    );
    // the old entries stay where they were
    expect(storage.getItem('affine-local-workspace')).toBe('["w1","w2"]');
  });

  test('never overwrites a value already stored under the new key', async () => {
    const { migrateLegacyLocalStorage } = await import('../legacy-storage');
    const storage = new MemoryStorage();
    storage.setItem('affine-local-workspace', '["old"]');
    storage.setItem('cloud-notes-local-workspace', '["new"]');
    migrateLegacyLocalStorage(storage);
    expect(storage.getItem('cloud-notes-local-workspace')).toBe('["new"]');
  });

  test('carries every feature flag over by prefix', async () => {
    const { migrateLegacyLocalStorage } = await import('../legacy-storage');
    const storage = new MemoryStorage();
    storage.setItem('global-state:affine-flag:a', 'true');
    storage.setItem('global-state:affine-flag:b', 'false');
    storage.setItem('global-state:other', 'x');
    migrateLegacyLocalStorage(storage);
    expect(storage.getItem('global-state:cloud-notes-flag:a')).toBe('true');
    expect(storage.getItem('global-state:cloud-notes-flag:b')).toBe('false');
    expect(storage.getItem('global-state:cloud-notes-flag:other')).toBeNull();
  });

  test('runs once per page load and tolerates blocked storage', async () => {
    const { migrateLegacyLocalStorage } = await import('../legacy-storage');
    const storage = new MemoryStorage();
    migrateLegacyLocalStorage(storage);
    storage.setItem('affine-settings', '{}');
    migrateLegacyLocalStorage(storage);
    expect(storage.getItem('cloud-notes-settings')).toBeNull();

    vi.resetModules();
    const again = await import('../legacy-storage');
    const blocked = {
      get length(): number {
        throw new Error('blocked');
      },
      getItem() {
        throw new Error('blocked');
      },
    } as unknown as Storage;
    expect(() => again.migrateLegacyLocalStorage(blocked)).not.toThrow();
  });
});
