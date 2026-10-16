// Bearer authentication for the sync socket.
//
// The edge gate (Caddy + the fleet's introspect-proxy) is the first line: the web
// app sits behind Authelia 2FA and the socket needs a Bearer token. This server
// does not trust that alone (defence in depth): it checks the same token itself,
// FAILING CLOSED -- no configured verifier means it will not start (main.mjs), an
// introspection error or an inactive token means the connection is refused.

/** `Authorization: Bearer x`, else `?access_token=x` (a browser WebSocket cannot set headers). */
export function bearerFrom(headers, params) {
  const h = headers?.get ? headers.get('authorization') : headers?.authorization;
  const m = typeof h === 'string' ? /^Bearer\s+(\S+)$/i.exec(h.trim()) : null;
  if (m) return m[1];
  const q = params?.get ? params.get('access_token') : null;
  return q && q.trim() ? q.trim() : null;
}

/**
 * A verifier is `async (token) => principal | null`. Modes:
 *  - static:     a fixed token set (development, tests)
 *  - introspect: RFC 7662 against `introspectUrl`; active:true and, when
 *                `requiredScope` is set, that scope; positive answers are cached
 *                for `cacheMs` (a revoked token stops within that window)
 */
export function makeVerifier({
  staticTokens = [],
  introspectUrl = '',
  requiredScope = '',
  cacheMs = 60_000,
  fetchImpl = globalThis.fetch,
  now = () => Date.now(),
} = {}) {
  const fixed = new Set(staticTokens.filter(Boolean));
  const cache = new Map(); // token -> { until, principal }

  return async function verify(token) {
    if (!token) return null;
    if (fixed.has(token)) return { sub: 'static' };
    if (!introspectUrl) return null;

    const hit = cache.get(token);
    if (hit && hit.until > now()) return hit.principal;

    let res;
    try {
      res = await fetchImpl(introspectUrl, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
        body: new URLSearchParams({ token }).toString(),
      });
    } catch {
      return null; // introspection unreachable: closed
    }
    if (!res.ok) return null;
    let body;
    try {
      body = await res.json();
    } catch {
      return null;
    }
    if (body?.active !== true) return null;
    if (requiredScope && !String(body.scope ?? '').split(/\s+/).includes(requiredScope)) return null;
    const principal = { sub: String(body.sub ?? body.username ?? 'unknown') };
    cache.set(token, { until: now() + cacheMs, principal });
    if (cache.size > 1000) cache.delete(cache.keys().next().value);
    return principal;
  };
}

export function verifierFromEnv(env = process.env, extra = {}) {
  const staticTokens = (env.SYNC_STATIC_TOKENS ?? '').split(',').map(s => s.trim()).filter(Boolean);
  const introspectUrl = env.SYNC_INTROSPECT_URL ?? '';
  if (staticTokens.length === 0 && !introspectUrl) {
    throw new Error('refusing to start: set SYNC_INTROSPECT_URL (production) or SYNC_STATIC_TOKENS (development)');
  }
  return makeVerifier({ staticTokens, introspectUrl, requiredScope: env.SYNC_REQUIRED_SCOPE ?? '', ...extra });
}
