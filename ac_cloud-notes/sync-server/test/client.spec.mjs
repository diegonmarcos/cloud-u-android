// The app's remote peer (packages/common/nbstore/src/impls/notes-sync) against the REAL server:
// what the sync engine does with a remote DocStorage, end to end, over HTTP.
import { afterEach, describe, expect, it } from 'vitest';
import * as Y from 'yjs';

import { NotesSyncDocStorage } from '../../packages/common/nbstore/src/impls/notes-sync/doc.ts';
import { makeVerifier } from '../src/auth.mjs';
import { createSyncServer } from '../src/server.mjs';

const TOKEN = 'client-token';
const cleanups = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()();
});

async function start(port = 0) {
  const server = createSyncServer({ port, database: ':memory:', verify: makeVerifier({ staticTokens: [TOKEN] }), debounce: 50 });
  await server.listen();
  cleanups.push(() => server.destroy());
  return server;
}

function storage(server, over = {}) {
  const s = new NotesSyncDocStorage({
    id: 'ws1',
    serverBaseUrl: server.httpURL,
    token: TOKEN,
    pollMs: 0,
    ...over,
  });
  cleanups.push(async () => s.connection.disconnect());
  return s;
}

const docWith = text => {
  const d = new Y.Doc();
  d.getText('t').insert(0, text);
  return d;
};
const textOf = bin => {
  const d = new Y.Doc();
  Y.applyUpdate(d, bin);
  return d.getText('t').toString();
};

describe('NotesSyncDocStorage against the sync server', () => {
  it('connects only with a valid Bearer', async () => {
    const s = await start();
    const good = storage(s);
    good.connection.connect();
    await good.connection.waitForConnected();
    expect(good.connection.status).toBe('connected');

    const bad = storage(s, { token: 'nope' });
    bad.connection.retryDelay = 1_000_000;
    bad.connection.connect();
    await new Promise(r => setTimeout(r, 400));
    expect(bad.connection.status).not.toBe('connected');
  });

  it('push is acknowledged after storage; another client reads it back byte-identical', async () => {
    const s = await start();
    const a = storage(s);
    const b = storage(s);
    const doc = docWith('written on device A');
    const { docId, timestamp } = await a.pushDocUpdate({ docId: 'd1', bin: Y.encodeStateAsUpdate(doc) });
    expect(docId).toBe('d1');
    expect(timestamp.getTime()).toBeGreaterThan(0);

    const rec = await b.getDoc('d1');
    expect(rec?.docId).toBe('d1');
    expect(textOf(rec.bin)).toBe('written on device A');
    expect(Buffer.from(rec.bin).equals(Buffer.from(Y.encodeStateAsUpdate(doc)))).toBe(true);
    expect(await b.getDoc('missing')).toBeNull();
  });

  it('getDocDiff returns only what the caller lacks, with the server state vector', async () => {
    const s = await start();
    const a = storage(s);
    const doc = docWith('x'.repeat(300));
    await a.pushDocUpdate({ docId: 'd1', bin: Y.encodeStateAsUpdate(doc) });
    const sv = Y.encodeStateVector(doc);
    doc.getText('t').insert(300, ' tail');
    await a.pushDocUpdate({ docId: 'd1', bin: Y.encodeStateAsUpdate(doc, sv) });

    const diff = await a.getDocDiff('d1', sv);
    expect(diff.missing.length).toBeLessThan(150);
    expect(Buffer.from(diff.state).equals(Buffer.from(Y.encodeStateVector(doc)))).toBe(true);
    const full = await a.getDocDiff('d1');
    expect(textOf(full.missing)).toBe('x'.repeat(300) + ' tail');
    expect(await a.getDocDiff('nope', sv)).toBeNull();
  });

  it('clocks: only this space\'s documents, and `after` filters', async () => {
    const s = await start();
    const mine = storage(s);
    const other = storage(s, { id: 'ws2' });
    await mine.pushDocUpdate({ docId: 'd1', bin: Y.encodeStateAsUpdate(docWith('1')) });
    await other.pushDocUpdate({ docId: 'dX', bin: Y.encodeStateAsUpdate(docWith('x')) });
    const first = await mine.getDocTimestamps();
    expect(Object.keys(first)).toEqual(['d1']);
    await new Promise(r => setTimeout(r, 20));
    await mine.pushDocUpdate({ docId: 'd2', bin: Y.encodeStateAsUpdate(docWith('2')) });
    const later = await mine.getDocTimestamps(first.d1);
    expect(Object.keys(later)).toEqual(['d2']);
    expect((await mine.getDocTimestamp('d1')).timestamp.getTime()).toBe(first.d1.getTime());
    expect(await mine.getDocTimestamp('zzz')).toBeNull();
  });

  it('a change by another client arrives as an update event; the client\'s own writes do not echo', async () => {
    const s = await start();
    const a = storage(s);
    const b = storage(s);
    a.connection.connect();
    await a.connection.waitForConnected();

    const seen = [];
    a.subscribeDocUpdate(u => seen.push(u.docId));

    await a.pushDocUpdate({ docId: 'own', bin: Y.encodeStateAsUpdate(docWith('mine')) });
    await a.pollOnce();
    expect(seen).toEqual([]); // its own write

    await b.pushDocUpdate({ docId: 'theirs', bin: Y.encodeStateAsUpdate(docWith('yours')) });
    await a.pollOnce();
    expect(seen).toEqual(['theirs']);
    await a.pollOnce();
    expect(seen).toEqual(['theirs']); // reported once
  });

  it('A6.4-shaped: an update made while the server is away is pushed when it returns, and nothing is lost', async () => {
    const first = await start();
    const port = new URL(first.httpURL).port;
    const a = storage(first, { serverBaseUrl: `http://127.0.0.1:${port}` });
    const doc = docWith('before');
    await a.pushDocUpdate({ docId: 'd1', bin: Y.encodeStateAsUpdate(doc) });
    await first.destroy();

    // offline edit: the push fails (so the sync peer keeps it queued and retries)
    const sv = Y.encodeStateVector(doc);
    doc.getText('t').insert(6, ' + offline edit');
    await expect(a.pushDocUpdate({ docId: 'd1', bin: Y.encodeStateAsUpdate(doc, sv) })).rejects.toBeTruthy();

    // the server comes back on the same port with the same data? an in-memory server loses it,
    // so the retry carries the WHOLE local doc, which is what the peer does after a failed push
    const second = createSyncServer({ port: Number(port), database: ':memory:', verify: makeVerifier({ staticTokens: [TOKEN] }), debounce: 50 });
    await second.listen();
    cleanups.push(() => second.destroy());
    await a.pushDocUpdate({ docId: 'd1', bin: Y.encodeStateAsUpdate(doc) });
    const b = storage(second, { serverBaseUrl: `http://127.0.0.1:${port}` });
    expect(textOf((await b.getDoc('d1')).bin)).toBe('before + offline edit');
  });
});
