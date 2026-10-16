import { SQLite } from '@hocuspocus/extension-sqlite';
import { Server } from '@hocuspocus/server';

import { bearerFrom } from './auth.mjs';

const toHeaders = h => ({ get: k => (Array.isArray(h[k.toLowerCase()]) ? h[k.toLowerCase()][0] : h[k.toLowerCase()]) });

/**
 * The sync server: one Yjs document per name, persisted in SQLite.
 *   GET /healthz           200 ok (no auth: the container healthcheck)
 *   WebSocket upgrade      401 unless the Bearer token verifies (header or ?access_token=)
 *   onAuthenticate         the same check again, for clients that authenticate in-band
 * Anything else answers 404: the server has no page of its own.
 */
export function createSyncServer({ port = 0, address = '127.0.0.1', database = ':memory:', verify, quiet = true }) {
  if (typeof verify !== 'function') throw new Error('createSyncServer needs a verify(token) function');

  const server = new Server({
    port,
    address,
    quiet,
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

    async onRequest({ request, response }) {
      const path = new URL(request.url ?? '/', 'http://x').pathname;
      if (path === '/healthz') {
        response.writeHead(200, { 'content-type': 'text/plain' });
        response.end('ok\n');
      } else {
        response.writeHead(404, { 'content-type': 'text/plain' });
        response.end('not found\n');
      }
      throw null; // handled: skip Hocuspocus' default welcome page
    },
  });
  return server;
}
