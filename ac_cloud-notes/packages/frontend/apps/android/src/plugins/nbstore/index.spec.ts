// Phase 0 (cloud-notes-hybrid): round-trip test of the JS storage adapter.
// The native plugin (Kotlin -> Rust/SQLite) is replaced by an in-memory fake
// that persists to a JSON "disk" the way the .db file persists: it survives
// disconnect + a brand-new plugin instance ("process kill"). The assertion is
// that bytes written through NbStoreNativeDBApis read back identical after.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => {
  const disk = new Map<string, string>(); // path -> JSON, survives "kill"
  const b64 = (u: Uint8Array) => Buffer.from(u).toString('base64');
  const unb64 = (s: string) => new Uint8Array(Buffer.from(s, 'base64'));
  const makePlugin = () => {
    const open = new Map<string, any>();
    const state = (id: string) => {
      const s = open.get(id);
      if (!s) throw new Error('not connected');
      return s;
    };
    return {
      connect: async ({ id }: any) => {
        open.set(id, JSON.parse(disk.get(id) ?? '{"snaps":{},"ups":{}}'));
      },
      disconnect: async ({ id }: any) => {
        disk.set(id, JSON.stringify(state(id)));
        open.delete(id);
      },
      pushUpdate: async ({ id, docId, data }: any) => {
        const s = state(id);
        const timestamp = 1_700_000_000_000 + Object.keys(s.ups).length;
        (s.ups[docId] ??= []).push({ docId, bin: data, timestamp });
        return { timestamp };
      },
      getDocUpdates: async ({ id, docId }: any) => ({
        updates: state(id).ups[docId] ?? [],
      }),
      setDocSnapshot: async ({ id, docId, bin, timestamp }: any) => {
        state(id).snaps[docId] = { docId, bin, timestamp };
        return { success: true };
      },
      getDocSnapshot: async ({ id, docId }: any) =>
        state(id).snaps[docId] ?? {}, // Capacitor resolves bare resolve() as {}
    };
  };
  return { disk, b64, unb64, makePlugin, plugin: { current: makePlugin() } };
});

vi.mock('@cloud-notes/core/modules/workspace-engine', () => ({
  base64ToUint8Array: h.unb64,
  uint8ArrayToBase64: async (u: Uint8Array) => h.b64(u),
}));
vi.mock('@cloud-notes/mobile-shared/nbstore/payload', () => ({
  decodePayload: async (s: string) => h.unb64(s),
  MOBILE_BLOB_FILE_PREFIX: '__CLOUD_NOTES_BLOB_FILE__:',
}));
vi.mock('@cloud-notes/nbstore', () => ({
  parseUniversalId: (id: string) => ({
    peer: 'local',
    type: 'workspace',
    id,
  }),
}));
// type-only import in index.ts, but the bundler keeps it as a side-effect import
vi.mock('@cloud-notes/nbstore/sqlite', () => ({}));
vi.mock('@capacitor/core', () => ({
  registerPlugin: () =>
    new Proxy(
      {},
      { get: (_t, k: string) => (h.plugin.current as any)[k] }
    ),
}));

import { NbStoreNativeDBApis as db } from './index';

// every byte value, twice, so any text-encoding / truncation bug shows
const payload = Uint8Array.from([
  ...Array.from({ length: 256 }, (_, i) => i),
  ...Array.from({ length: 256 }, (_, i) => 255 - i),
]);

describe('NbStore storage adapter round trip', () => {
  beforeEach(() => {
    h.disk.clear();
    h.plugin.current = h.makePlugin();
  });

  it('snapshot survives disconnect + new plugin instance byte-identical', async () => {
    await db.connect('ws-1');
    const ts = new Date(1_700_000_123_456);
    expect(
      await db.setDocSnapshot('ws-1', { docId: 'd1', bin: payload, timestamp: ts })
    ).toBe(true);
    await db.disconnect('ws-1');

    h.plugin.current = h.makePlugin(); // process kill: only the "file" remains
    await db.connect('ws-1');
    const snap = await db.getDocSnapshot('ws-1', 'd1');
    expect(snap).not.toBeNull();
    expect(Array.from(snap!.bin)).toEqual(Array.from(payload));
    expect(snap!.timestamp.getTime()).toBe(ts.getTime());
    expect(snap!.docId).toBe('d1');
  });

  it('pushed updates survive reopen byte-identical', async () => {
    await db.connect('ws-2');
    const t = await db.pushUpdate('ws-2', 'd2', payload);
    await db.disconnect('ws-2');

    h.plugin.current = h.makePlugin();
    await db.connect('ws-2');
    const ups = await db.getDocUpdates('ws-2', 'd2');
    expect(ups).toHaveLength(1);
    expect(Array.from(ups[0].bin)).toEqual(Array.from(payload));
    expect(ups[0].timestamp.getTime()).toBe(t.getTime());
  });

  it('unknown doc reads back as null, not an error', async () => {
    await db.connect('ws-3');
    expect(await db.getDocSnapshot('ws-3', 'missing')).toBeNull();
  });
});
