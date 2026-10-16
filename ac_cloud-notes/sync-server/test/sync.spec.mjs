import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { HocuspocusProvider } from '@hocuspocus/provider';
import { afterEach, describe, expect, it } from 'vitest';
import * as Y from 'yjs';

import { makeVerifier } from '../src/auth.mjs';
import { createSyncServer } from '../src/server.mjs';

const TOKEN = 'good-token';
const verify = makeVerifier({ staticTokens: [TOKEN] });
const cleanups = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()();
});

async function start(database = ':memory:') {
  const server = createSyncServer({ port: 0, database, verify, debounce: 50 });
  await server.listen();
  cleanups.push(() => server.destroy());
  return server;
}

function client(server, name, doc, token = TOKEN, extra = {}) {
  const p = new HocuspocusProvider({
    url: server.webSocketURL,
    name,
    document: doc,
    token,
    ...extra,
  });
  cleanups.push(async () => p.destroy());
  return p;
}

const synced = p =>
  new Promise((resolve, reject) => {
    if (p.isSynced) return resolve();
    p.on('synced', () => resolve());
    p.on('authenticationFailed', e => reject(new Error(`auth failed: ${e?.reason ?? ''}`)));
  });

const sameState = (a, b) =>
  expect(Buffer.from(Y.encodeStateAsUpdate(a)).equals(Buffer.from(Y.encodeStateAsUpdate(b)))).toBe(true);

describe('A6.2 the socket is refused without a valid Bearer', () => {
  it('serves /healthz and nothing else over HTTP', async () => {
    const s = await start();
    const ok = await fetch(`${s.httpURL}/healthz`);
    expect(ok.status).toBe(200);
    expect(await ok.text()).toBe('ok\n');
    expect((await fetch(`${s.httpURL}/`)).status).toBe(404);
  });

  it('upgrade without a token answers 401', async () => {
    const s = await start();
    const res = await new Promise(resolve => {
      const ws = new WebSocket(s.webSocketURL);
      ws.onopen = () => resolve('open');
      ws.onerror = () => resolve('refused');
      ws.onclose = () => resolve('refused');
    });
    expect(res).toBe('refused');
  });

  it('upgrade with a wrong token is refused, with a good ?access_token= it opens', async () => {
    const s = await start();
    const attempt = token =>
      new Promise(resolve => {
        const ws = new WebSocket(`${s.webSocketURL}/?access_token=${token}`);
        ws.onopen = () => { ws.close(); resolve('open'); };
        ws.onerror = () => resolve('refused');
        ws.onclose = () => resolve('refused');
      });
    expect(await attempt('wrong')).toBe('refused');
    expect(await attempt(TOKEN)).toBe('open');
  });

  it('a provider with a good token syncs; with a bad one it never does', async () => {
    const s = await start();
    const a = new Y.Doc();
    a.getText('t').insert(0, 'hello');
    const pa = client(s, 'doc-1', a, TOKEN, { url: `${s.webSocketURL}/?access_token=${TOKEN}` });
    await synced(pa);

    const b = new Y.Doc();
    const pb = client(s, 'doc-1', b, TOKEN, { url: `${s.webSocketURL}/?access_token=${TOKEN}` });
    await synced(pb);
    expect(b.getText('t').toString()).toBe('hello');

    const c = new Y.Doc();
    const pc = client(s, 'doc-1', c, 'bad', { url: `${s.webSocketURL}/?access_token=bad`, maxAttempts: 1 });
    await new Promise(r => setTimeout(r, 600));
    expect(pc.isSynced).toBe(false);
    expect(c.getText('t').toString()).toBe('');
  });
});

describe('A6.1 two clients that edit offline converge', () => {
  it('10 interleaved edits each, then reconnect: byte-identical state, no lost edit', async () => {
    const s = await start();
    const url = `${s.webSocketURL}/?access_token=${TOKEN}`;
    const a = new Y.Doc();
    const b = new Y.Doc();
    // offline: no provider yet; each side only has its own edits
    for (let i = 0; i < 10; i++) {
      a.getArray('log').push([`a${i}`]);
      b.getArray('log').push([`b${i}`]);
      a.getText('t').insert(a.getText('t').length, `A${i}`);
      b.getText('t').insert(0, `B${i}`);
    }
    const pa = client(s, 'doc-offline', a, TOKEN, { url });
    await synced(pa);
    const pb = client(s, 'doc-offline', b, TOKEN, { url });
    await synced(pb);
    // let the server fan the merged state back to a
    await new Promise(r => setTimeout(r, 300));

    sameState(a, b);
    const log = a.getArray('log').toArray();
    expect(log).toHaveLength(20);
    for (let i = 0; i < 10; i++) {
      expect(log).toContain(`a${i}`);
      expect(log).toContain(`b${i}`);
    }
    expect(a.getText('t').length).toBe(b.getText('t').length);
    expect(a.getText('t').length).toBe(2 * ('A0'.length * 10));
  });
});

describe('A6.3 acknowledged updates survive a server restart', () => {
  it('stop the server, start a new one on the same file: the document is whole', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'cn-sync-'));
    cleanups.push(async () => rmSync(dir, { recursive: true, force: true }));
    const db = join(dir, 'sync.sqlite');

    const s1 = createSyncServer({ port: 0, database: db, verify, debounce: 50 });
    await s1.listen();
    const a = new Y.Doc();
    const pa = new HocuspocusProvider({ url: `${s1.webSocketURL}/?access_token=${TOKEN}`, name: 'doc-persist', document: a, token: TOKEN });
    await synced(pa);
    for (let i = 0; i < 25; i++) a.getArray('n').push([i]);
    await new Promise(r => setTimeout(r, 200));
    pa.destroy();
    await s1.destroy(); // flushes pending stores

    const s2 = await start(db);
    const b = new Y.Doc();
    const pb = client(s2, 'doc-persist', b, TOKEN, { url: `${s2.webSocketURL}/?access_token=${TOKEN}` });
    await synced(pb);
    expect(b.getArray('n').toArray()).toEqual(Array.from({ length: 25 }, (_, i) => i));
    sameState(a, b);
  });
});

describe('document clock list (GET /v1/docs)', () => {
  it('needs a Bearer, lists stored documents oldest first, honours ?after=', async () => {
    const s = await start();
    expect((await fetch(`${s.httpURL}/v1/docs`)).status).toBe(401);
    expect((await fetch(`${s.httpURL}/v1/docs`, { headers: { authorization: 'Bearer wrong' } })).status).toBe(401);

    const auth = { headers: { authorization: `Bearer ${TOKEN}` } };
    expect(await (await fetch(`${s.httpURL}/v1/docs`, auth)).json()).toEqual([]);

    const url = `${s.webSocketURL}/?access_token=${TOKEN}`;
    const a = new Y.Doc();
    a.getText('t').insert(0, 'one');
    const pa = client(s, 'ws/doc-a', a, TOKEN, { url });
    await synced(pa);
    await new Promise(r => setTimeout(r, 400)); // the (shortened) store debounce
    const mid = Date.now();
    await new Promise(r => setTimeout(r, 50));
    const b = new Y.Doc();
    b.getText('t').insert(0, 'two');
    const pb = client(s, 'ws/doc-b', b, TOKEN, { url });
    await synced(pb);
    await new Promise(r => setTimeout(r, 400));

    const all = await (await fetch(`${s.httpURL}/v1/docs`, auth)).json();
    expect(all.map(d => d.name)).toEqual(['ws/doc-a', 'ws/doc-b']);
    expect(all[0].updatedAt).toBeLessThanOrEqual(all[1].updatedAt);
    const later = await (await fetch(`${s.httpURL}/v1/docs?after=${mid}`, auth)).json();
    expect(later.map(d => d.name)).toEqual(['ws/doc-b']);
  });
});
