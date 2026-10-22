import { expect, test } from 'vitest';

import {
  buildAuthenticationDeepLink,
  buildOpenAppUrlRoute,
  normalizeOpenAppSignInNextParam,
} from '../utils';

test('buildAuthenticationDeepLink', () => {
  const payload = { code: '1', next: '/workspace/123' };
  const url = buildAuthenticationDeepLink({
    scheme: 'cloud-notes',
    method: 'open-app-signin',
    payload,
    server: 'https://app.cloud-notes.local',
  });

  const parsed = new URL(url);

  expect(parsed.protocol).toBe('cloud-notes:');
  expect(parsed.hostname).toBe('authentication');
  expect(parsed.searchParams.get('method')).toBe('open-app-signin');
  expect(parsed.searchParams.get('payload')).toBe(JSON.stringify(payload));
  expect(parsed.searchParams.get('server')).toBe('https://app.cloud-notes.local');
});

test('buildOpenAppUrlRoute', () => {
  const urlToOpen = 'cloud-notes://authentication?method=oauth&payload=%7B%7D';
  const route = buildOpenAppUrlRoute(urlToOpen);

  const parsed = new URL(route, 'https://app.cloud-notes.local');
  expect(parsed.pathname).toBe('/open-app/url');
  expect(parsed.searchParams.get('url')).toBe(urlToOpen);
});

test('normalizeOpenAppSignInNextParam', () => {
  expect(
    normalizeOpenAppSignInNextParam(
      '/workspace/123',
      'https://app.cloud-notes.local'
    )
  ).toBe('/workspace/123');

  expect(
    normalizeOpenAppSignInNextParam(
      'https://app.cloud-notes.local/workspace/123?foo=1#bar',
      'https://app.cloud-notes.local'
    )
  ).toBe('/workspace/123?foo=1#bar');

  expect(
    normalizeOpenAppSignInNextParam(
      'https://evil.example/workspace/123',
      'https://app.cloud-notes.local'
    )
  ).toBeUndefined();

  expect(
    normalizeOpenAppSignInNextParam(
      '//evil.example/workspace/123',
      'https://app.cloud-notes.local'
    )
  ).toBeUndefined();

  expect(
    normalizeOpenAppSignInNextParam(
      '/redirect-proxy?redirect_uri=https://evil.example',
      'https://app.cloud-notes.local'
    )
  ).toBeUndefined();
});
