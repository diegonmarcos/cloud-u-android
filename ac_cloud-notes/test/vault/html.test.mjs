import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { decl, fixture, src } from './helpers.mjs';

const hs = await src('html-sandbox.ts');
const page = fixture('sample', 'page.html');

test('default is offline: closed CSP, no remote host anywhere, scripts only inline', () => {
  const f = hs.buildSandboxFrame(page, decl.html, false);
  assert.match(f.csp, /default-src 'none'/);
  assert.match(f.csp, /connect-src 'none'/);
  assert.ok(!/https?:/.test(f.csp), 'no http(s) host in the default policy');
  assert.match(f.csp, /script-src 'unsafe-inline'(;|$)/);
});

test('sandbox attribute: scripts only, never same-origin / forms / popups / navigation', () => {
  const f = hs.buildSandboxFrame(page, decl.html, false);
  assert.equal(f.sandbox, 'allow-scripts');
  for (const bad of ['allow-same-origin', 'allow-forms', 'allow-popups', 'allow-top-navigation', 'allow-downloads']) {
    assert.ok(!f.sandbox.includes(bad), bad);
  }
});

test('the policy comes before every page script and the page itself is kept', () => {
  const f = hs.buildSandboxFrame(page, decl.html, false);
  const meta = f.srcdoc.indexOf('Content-Security-Policy');
  assert.ok(meta > 0 && meta < f.srcdoc.indexOf('<script'));
  assert.ok(f.srcdoc.includes('Suppressed knowledge'));
  assert.ok(hs.buildSandboxFrame('<p>x</p>', decl.html, false).srcdoc.includes('Content-Security-Policy'));
});

test('external references are listed for the toggle banner', () => {
  const f = hs.buildSandboxFrame(page, decl.html, false);
  assert.ok(f.external.includes('https://cdn.tailwindcss.com'));
  assert.ok(f.external.some(u => u.startsWith('https://fonts.googleapis.com/')));
});

test('toggle on: only the declared CDN hosts are added, connect-src stays closed', () => {
  const f = hs.buildSandboxFrame(page, decl.html, true);
  assert.match(f.csp, /script-src [^;]*https:\/\/cdn\.tailwindcss\.com/);
  assert.match(f.csp, /style-src [^;]*https:\/\/fonts\.googleapis\.com/);
  assert.match(f.csp, /font-src [^;]*https:\/\/fonts\.gstatic\.com/);
  assert.match(f.csp, /connect-src 'none'/);
  const hosts = new Set(f.csp.match(/https:\/\/[^\s;]+/g));
  const declared = new Set([...decl.html.cdn.script, ...decl.html.cdn.style, ...decl.html.cdn.font]);
  assert.deepEqual([...hosts].filter(h => !declared.has(h)), []);
  assert.equal(decl.html.cdn.default, false);
});

const book = '/home/user/cloud-me-pvt-notes/2.1___Wisdom/2.1.1_Book-Suppressed-Sacred-Knowledge/Suppressed_Sacred_Knowledge_Explorer.html';
test('the vault book opens (when the vault is checked out here)', { skip: !existsSync(book) }, () => {
  const f = hs.buildSandboxFrame(readFileSync(book, 'utf8'), decl.html, false);
  assert.ok(f.srcdoc.length > 60000);
  assert.ok(f.external.includes('https://cdn.tailwindcss.com'));
  assert.ok(!/https?:/.test(f.csp));
});
