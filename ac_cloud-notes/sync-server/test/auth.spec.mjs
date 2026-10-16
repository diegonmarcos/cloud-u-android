import { describe, expect, it } from 'vitest';

import { bearerFrom, makeVerifier, verifierFromEnv } from '../src/auth.mjs';

const hdr = o => new Headers(o);

describe('bearerFrom', () => {
  it('reads the Authorization header, else ?access_token=', () => {
    expect(bearerFrom(hdr({ authorization: 'Bearer abc' }), new URLSearchParams())).toBe('abc');
    expect(bearerFrom(hdr({ authorization: 'bearer abc' }), new URLSearchParams())).toBe('abc');
    expect(bearerFrom(hdr({}), new URLSearchParams('access_token=xyz'))).toBe('xyz');
    expect(bearerFrom(hdr({ authorization: 'Basic abc' }), new URLSearchParams())).toBeNull();
    expect(bearerFrom(hdr({}), new URLSearchParams('access_token='))).toBeNull();
    expect(bearerFrom(undefined, undefined)).toBeNull();
  });
});

const respond = (status, body) => async () => ({ ok: status < 400, status, json: async () => body });

describe('makeVerifier', () => {
  it('static tokens', async () => {
    const v = makeVerifier({ staticTokens: ['t1'] });
    expect(await v('t1')).toEqual({ sub: 'static' });
    expect(await v('nope')).toBeNull();
    expect(await v('')).toBeNull();
    expect(await v(null)).toBeNull();
  });

  it('introspection: active is accepted, inactive / error / unreachable / bad JSON is refused', async () => {
    const url = 'http://introspect/x';
    expect(await makeVerifier({ introspectUrl: url, fetchImpl: respond(200, { active: true, sub: 'diego' }) })('a')).toEqual({ sub: 'diego' });
    expect(await makeVerifier({ introspectUrl: url, fetchImpl: respond(200, { active: false }) })('a')).toBeNull();
    expect(await makeVerifier({ introspectUrl: url, fetchImpl: respond(200, {}) })('a')).toBeNull();
    expect(await makeVerifier({ introspectUrl: url, fetchImpl: respond(500, {}) })('a')).toBeNull();
    expect(await makeVerifier({ introspectUrl: url, fetchImpl: async () => { throw new Error('down'); } })('a')).toBeNull();
    expect(await makeVerifier({ introspectUrl: url, fetchImpl: async () => ({ ok: true, json: async () => { throw new Error('x'); } }) })('a')).toBeNull();
  });

  it('a required scope must be present', async () => {
    const f = respond(200, { active: true, sub: 's', scope: 'openid notes:sync' });
    expect(await makeVerifier({ introspectUrl: 'http://i', requiredScope: 'notes:sync', fetchImpl: f })('a')).toEqual({ sub: 's' });
    expect(await makeVerifier({ introspectUrl: 'http://i', requiredScope: 'admin', fetchImpl: f })('a')).toBeNull();
  });

  it('positive answers are cached for cacheMs, then asked again (a revoked token stops)', async () => {
    let calls = 0;
    let active = true;
    let t = 0;
    const v = makeVerifier({
      introspectUrl: 'http://i',
      cacheMs: 1000,
      now: () => t,
      fetchImpl: async () => { calls++; return { ok: true, json: async () => ({ active, sub: 'u' }) }; },
    });
    expect(await v('a')).not.toBeNull();
    expect(await v('a')).not.toBeNull();
    expect(calls).toBe(1);
    active = false;
    t = 1001;
    expect(await v('a')).toBeNull();
    expect(calls).toBe(2);
  });
});

describe('verifierFromEnv', () => {
  it('fails closed: no verifier configured means no server', () => {
    expect(() => verifierFromEnv({})).toThrow(/refusing to start/);
    expect(() => verifierFromEnv({ SYNC_STATIC_TOKENS: ' , ' })).toThrow(/refusing to start/);
  });
  it('accepts either source', async () => {
    expect(await verifierFromEnv({ SYNC_STATIC_TOKENS: 'a, b' })('b')).toEqual({ sub: 'static' });
    expect(typeof verifierFromEnv({ SYNC_INTROSPECT_URL: 'http://i' })).toBe('function');
  });
});
