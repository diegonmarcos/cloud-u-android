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

describe('HTTP delta API (what the app\'s sync peer uses)', () => {
  const auth = { authorization: `Bearer ${TOKEN}` };
  const post = (s, name, update, headers = auth) =>
    fetch(`${s.httpURL}/v1/docs/${encodeURIComponent(name)}/updates`, { method: 'POST', headers, body: update });
  const diff = (s, name, sv, headers = auth) =>
    fetch(`${s.httpURL}/v1/docs/${encodeURIComponent(name)}/diff${sv ? `?sv=${Buffer.from(sv).toString('base64url')}` : ''}`, { headers });
  const docWith = text => {
    const d = new Y.Doc();
    d.getText('t').insert(0, text);
    return d;
  };

  it('refuses without a Bearer, answers 404 for an unknown document', async () => {
    const s = await start();
    const u = Y.encodeStateAsUpdate(docWith('x'));
    expect((await post(s, 'ws/a', u, {})).status).toBe(401);
    expect((await diff(s, 'ws/a', undefined, {})).status).toBe(401);
    expect((await diff(s, 'ws/never')).status).toBe(404);
    expect((await fetch(`${s.httpURL}/v1/docs/${encodeURIComponent('ws/a')}/nope`, { headers: auth })).status).toBe(404);
  });

  it('a posted update is acknowledged after it is stored, and read back byte-identical', async () => {
    const s = await start();
    const a = docWith('hello');
    const res = await post(s, 'ws/a', Y.encodeStateAsUpdate(a));
    expect(res.status).toBe(204);
    expect(Number(res.headers.get('x-doc-clock'))).toBeGreaterThan(0);

    const got = await diff(s, 'ws/a');
    expect(got.status).toBe(200);
    const b = new Y.Doc();
    Y.applyUpdate(b, new Uint8Array(await got.arrayBuffer()));
    sameState(a, b);
    expect(Number(got.headers.get('x-doc-clock'))).toBe(Number(res.headers.get('x-doc-clock')));
  });

  it('diff with a state vector returns only what the client misses', async () => {
    const s = await start();
    const a = docWith('x'.repeat(300));
    await post(s, 'ws/a', Y.encodeStateAsUpdate(a));
    const clientSv = Y.encodeStateVector(a); // a client that already has the first 300 characters
    const before = Y.encodeStateAsUpdate(a);
    a.getText('t').insert(300, ' and more');
    await post(s, 'ws/a', Y.encodeStateAsUpdate(a, clientSv));

    const full = new Uint8Array(await (await diff(s, 'ws/a')).arrayBuffer());
    const partial = new Uint8Array(await (await diff(s, 'ws/a', clientSv)).arrayBuffer());
    expect(partial.length).toBeLessThan(full.length / 2);

    // the client catches up from the partial diff alone
    const client = new Y.Doc();
    Y.applyUpdate(client, before);
    Y.applyUpdate(client, partial);
    expect(client.getText('t').toString()).toBe('x'.repeat(300) + ' and more');
  });

  it('a read does not move the clock; a write does', async () => {
    const s = await start();
    await post(s, 'ws/a', Y.encodeStateAsUpdate(docWith('x')));
    const list = async () => (await (await fetch(`${s.httpURL}/v1/docs`, { headers: auth })).json())[0].updatedAt;
    const t0 = await list();
    await diff(s, 'ws/a');
    await diff(s, 'ws/a');
    expect(await list()).toBe(t0);
    const a = docWith('x');
    a.getText('t').insert(1, 'y');
    await post(s, 'ws/a', Y.encodeStateAsUpdate(a));
    expect(await list()).toBeGreaterThan(t0);
  });

  it('a websocket client sees an HTTP-posted update live', async () => {
    const s = await start();
    const b = new Y.Doc();
    const pb = client(s, 'ws/live', b, TOKEN, { url: `${s.webSocketURL}/?access_token=${TOKEN}` });
    await synced(pb);
    await post(s, 'ws/live', Y.encodeStateAsUpdate(docWith('from http')));
    await new Promise(r => setTimeout(r, 300));
    expect(b.getText('t').toString()).toBe('from http');
  });

  it('rejects garbage as 400 and survives a restart', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'cn-sync-'));
    cleanups.push(async () => rmSync(dir, { recursive: true, force: true }));
    const db = join(dir, 'sync.sqlite');
    const s1 = createSyncServer({ port: 0, database: db, verify, debounce: 50 });
    await s1.listen();
    expect((await post(s1, 'ws/a', new Uint8Array([1, 2, 3, 4, 5]))).status).toBe(400);
    const a = docWith('durable');
    expect((await post(s1, 'ws/a', Y.encodeStateAsUpdate(a))).status).toBe(204);
    await s1.destroy();

    const s2 = await start(db);
    const got = await diff(s2, 'ws/a');
    expect(got.status).toBe(200);
    const b = new Y.Doc();
    Y.applyUpdate(b, new Uint8Array(await got.arrayBuffer()));
    expect(b.getText('t').toString()).toBe('durable');
  });
});
