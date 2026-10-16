import { SQLite } from '@hocuspocus/extension-sqlite';
import { Server } from '@hocuspocus/server';
import Database from 'better-sqlite3';

import { bearerFrom } from './auth.mjs';

const toHeaders = h => ({ get: k => (Array.isArray(h[k.toLowerCase()]) ? h[k.toLowerCase()][0] : h[k.toLowerCase()]) });

/**
 * The sync server: one Yjs document per name, persisted in SQLite.
 *   GET /healthz           200 ok (no auth: the container healthcheck)
 *   WebSocket upgrade      401 unless the Bearer token verifies (header or ?access_token=)
 *   onAuthenticate         the same check again, for clients that authenticate in-band
 *   GET /v1/docs?after=ms  Bearer required: [{ name, updatedAt }] of the documents stored after
 *                          `after` (default 0), oldest first -- the clock list a client's pull needs
 *                          (Hocuspocus itself cannot list its documents)
 * Anything else answers 404: the server has no page of its own.
 */
export function createSyncServer({ port = 0, address = '127.0.0.1', database = ':memory:', verify, quiet = true, debounce = 2000 }) {
  if (typeof verify !== 'function') throw new Error('createSyncServer needs a verify(token) function');

  // doc_meta lives beside Hocuspocus' own `documents` table, in the same file
  const meta = new Database(database);
  meta.pragma('journal_mode = WAL');
  meta.exec('CREATE TABLE IF NOT EXISTS doc_meta (name TEXT PRIMARY KEY, updated_at INTEGER NOT NULL)');
  const touch = meta.prepare('INSERT INTO doc_meta (name, updated_at) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET updated_at = excluded.updated_at');
  const since = meta.prepare('SELECT name, updated_at AS updatedAt FROM doc_meta WHERE updated_at > ? ORDER BY updated_at, name');

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

    async onStoreDocument({ documentName }) {
      touch.run(documentName, Date.now());
    },

    async onRequest({ request, response }) {
      const url = new URL(request.url ?? '/', 'http://x');
      const path = url.pathname;
      if (path === '/healthz') {
        response.writeHead(200, { 'content-type': 'text/plain' });
        response.end('ok\n');
      } else if (path === '/v1/docs') {
        const principal = await verify(bearerFrom(toHeaders(request.headers), url.searchParams));
        if (!principal) {
          response.writeHead(401, { 'content-type': 'text/plain', 'www-authenticate': 'Bearer' });
          response.end('unauthorized\n');
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
