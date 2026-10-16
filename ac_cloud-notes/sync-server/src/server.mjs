import { SQLite } from '@hocuspocus/extension-sqlite';
import { Server } from '@hocuspocus/server';
import Database from 'better-sqlite3';
import * as Y from 'yjs';

import { bearerFrom } from './auth.mjs';

const toHeaders = h => ({ get: k => (Array.isArray(h[k.toLowerCase()]) ? h[k.toLowerCase()][0] : h[k.toLowerCase()]) });

/**
 * The sync server: one Yjs document per name, persisted in SQLite.
 *   GET /healthz           200 ok (no auth: the container healthcheck)
 *   WebSocket upgrade      401 unless the Bearer token verifies (header or ?access_token=)
 *   onAuthenticate         the same check again, for clients that authenticate in-band
 *   GET /v1/docs?after=ms  Bearer required: [{ name, updatedAt }] of the documents CHANGED after
 *                          `after` (default 0), oldest first -- the clock list a client's pull needs
 *                          (Hocuspocus itself cannot list its documents)
 *   GET /v1/docs/<name>/diff?sv=<base64url state vector>
 *                          Bearer required: the Yjs update a client with that state is missing
 *                          (everything when `sv` is absent); 404 for an unknown document;
 *                          `x-doc-clock` carries the document's clock, `x-state-vector` the server's
 *                          state vector (base64url). Plain fetch, so a browser or a
 *                          WebView can send the Authorization header a WebSocket cannot.
 *   POST /v1/docs/<name>/updates
 *                          Bearer required: body = one Yjs update; answers 204 only after the
 *                          document is PERSISTED (the acknowledgement a sync peer relies on),
 *                          `x-doc-clock` = the new clock. Open websocket clients see it live.
 * Anything else answers 404: the server has no page of its own.
 */
export function createSyncServer({ port = 0, address = '127.0.0.1', database = ':memory:', verify, quiet = true, debounce = 2000 }) {
  if (typeof verify !== 'function') throw new Error('createSyncServer needs a verify(token) function');

  // doc_meta lives beside Hocuspocus' own `documents` table, in the same file
  const meta = new Database(database);
  meta.pragma('journal_mode = WAL');
  meta.exec('CREATE TABLE IF NOT EXISTS doc_meta (name TEXT PRIMARY KEY, updated_at INTEGER NOT NULL, sv BLOB)');
  try { meta.exec('ALTER TABLE doc_meta ADD COLUMN sv BLOB'); } catch { /* already there */ }
  const row = meta.prepare('SELECT updated_at AS updatedAt, sv FROM doc_meta WHERE name = ?');
  const put = meta.prepare('INSERT INTO doc_meta (name, updated_at, sv) VALUES (?, ?, ?) ON CONFLICT(name) DO UPDATE SET updated_at = excluded.updated_at, sv = excluded.sv');
  const since = meta.prepare('SELECT name, updated_at AS updatedAt FROM doc_meta WHERE updated_at > ? ORDER BY updated_at, name');
  /** The clock moves only when the document's state does: a read (a direct connection storing an unchanged doc) leaves it alone. */
  const touch = (name, doc) => {
    const sv = Buffer.from(Y.encodeStateVector(doc));
    const prev = row.get(name);
    if (prev?.sv && Buffer.from(prev.sv).equals(sv)) return prev.updatedAt;
    const now = Math.max(Date.now(), (prev?.updatedAt ?? 0) + 1);
    put.run(name, now, sv);
    return now;
  };

  const unauthorized = response => {
    response.writeHead(401, { 'content-type': 'text/plain', 'www-authenticate': 'Bearer' });
    response.end('unauthorized\n');
  };
  const MAX_BODY = 16 * 1024 * 1024;
  const readBody = request =>
    new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      request.on('data', c => {
        size += c.length;
        if (size > MAX_BODY) { reject(new Error('too large')); request.destroy(); } else chunks.push(c);
      });
      request.on('end', () => resolve(Buffer.concat(chunks)));
      request.on('error', reject);
    });

  async function handleDoc(request, response, url) {
    const principal = await verify(bearerFrom(toHeaders(request.headers), url.searchParams));
    if (!principal) return unauthorized(response);
    const parts = url.pathname.split('/'); // ['', 'v1', 'docs', <name>, 'diff' | 'updates']
    let name;
    try { name = decodeURIComponent(parts[3] ?? ''); } catch { name = ''; }
    const action = parts[4];
    const end = (code, headers = {}, body = '') => { response.writeHead(code, headers); response.end(body); };
    if (!name || parts.length !== 5 || (action !== 'diff' && action !== 'updates')) {
      return end(404, { 'content-type': 'text/plain' }, 'not found\n');
    }

    if (action === 'diff' && request.method === 'GET') {
      const known = row.get(name);
      if (!known) return end(404, { 'content-type': 'text/plain' }, 'unknown document\n');
      let sv;
      const svParam = url.searchParams.get('sv');
      if (svParam) {
        try { sv = new Uint8Array(Buffer.from(svParam, 'base64url')); } catch { return end(400, {}, 'bad sv\n'); }
      }
      const conn = await server.hocuspocus.openDirectConnection(name, { user: principal });
      try {
        const update = Buffer.from(Y.encodeStateAsUpdate(conn.document, sv));
        const clock = row.get(name)?.updatedAt ?? known.updatedAt;
        return end(200, {
          'content-type': 'application/octet-stream',
          'cache-control': 'no-store',
          'x-doc-clock': String(clock),
          'x-state-vector': Buffer.from(Y.encodeStateVector(conn.document)).toString('base64url'),
        }, update);
      } finally {
        await conn.disconnect();
      }
    }

    if (action === 'updates' && request.method === 'POST') {
      let body;
      try { body = await readBody(request); } catch { return end(413, {}, 'too large\n'); }
      if (body.length === 0) return end(400, {}, 'empty update\n');
      const conn = await server.hocuspocus.openDirectConnection(name, { user: principal });
      try {
        try {
          await conn.transact(doc => Y.applyUpdate(doc, new Uint8Array(body)));
        } catch {
          return end(400, {}, 'not a yjs update\n');
        }
      } finally {
        await conn.disconnect(); // persists before it returns: the 204 below is an acknowledgement
      }
      return end(204, { 'x-doc-clock': String(row.get(name)?.updatedAt ?? Date.now()) });
    }
    return end(405, { allow: 'GET, POST' }, 'method not allowed\n');
  }

  const server = new Server({
    port,
    address,
    quiet,
    debounce, // how long a burst of edits waits before it is stored
    stopOnSignals: false,
    extensions: [new SQLite({ database })],

    async onUpgrade({ request, socket }) {
      const url = new URL(request.url ?? '/', 'http://x');
      const token = bearerFrom(toHeaders(request.headers), url.searchParams);
      const principal = await verify(token);
      if (!principal) {
        socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
        socket.destroy();
        throw null; // empty rejection: Hocuspocus stops here without rethrowing
      }
    },

    async onAuthenticate({ token, requestHeaders, requestParameters }) {
      const principal = await verify(bearerFrom(requestHeaders, requestParameters) ?? token);
      if (!principal) throw new Error('not authorized');
      return { user: principal };
    },

    async onStoreDocument({ documentName, document }) {
      touch(documentName, document);
    },

    async onRequest({ request, response }) {
      const url = new URL(request.url ?? '/', 'http://x');
      const path = url.pathname;
      if (path === '/healthz') {
        response.writeHead(200, { 'content-type': 'text/plain' });
        response.end('ok\n');
      } else if (path.startsWith('/v1/docs/')) {
        await handleDoc(request, response, url);
      } else if (path === '/v1/docs') {
        const principal = await verify(bearerFrom(toHeaders(request.headers), url.searchParams));
        if (!principal) {
          unauthorized(response);
        } else {
          const after = Number(url.searchParams.get('after') ?? 0);
          response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
          response.end(JSON.stringify(since.all(Number.isFinite(after) ? after : 0)));
        }
      } else {
        response.writeHead(404, { 'content-type': 'text/plain' });
        response.end('not found\n');
      }
      throw null; // handled: skip Hocuspocus' default welcome page
    },
  });
  const destroy = server.destroy.bind(server);
  server.destroy = async () => {
    await destroy();
    meta.close();
  };
  return server;
}
