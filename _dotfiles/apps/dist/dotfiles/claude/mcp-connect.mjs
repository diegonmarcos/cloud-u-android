#!/usr/bin/env node
// mcp-connect — stdio <-> streamable-HTTP bridge for the fleet MCP servers.
//
// OWNER DECISION 31: "first use private if a mesh member, then fallback to
// public using bearer". Claude Code's http entries have no fallback, so every
// fleet entry in .mcp.json is instead:
//
//   { "type": "stdio", "command": "node",
//     "args": [".claude/mcp-connect.mjs", "--mesh", <private url>, "--public", <public url>] }
//
//   1. Probe --mesh (short timeout). Any HTTP answer => this host is a WireGuard
//      mesh member: bridge to it with NO auth (mesh membership is the gate).
//   2. Otherwise bridge to --public with `Authorization: Bearer <token>`. The
//      edge (Caddy @bearer) accepts exactly this header; X-Fleet-Authorization
//      is rejected (403, probed 2026-10-17).
//
// Token, first found wins (never committed, never logged):
//   $FLEET_BEARER, $AUTHELIA_BEARER_TOKEN, else the JSON printed by the sibling
//   mcp-auth-headers.sh (vault checkout / client_credentials mint).
//
// NO OAuth / DCR. Zero dependencies (Node >= 22 fetch). Diagnostics -> stderr.
import { spawnSync } from 'node:child_process';
import * as path from 'node:path';

// Node's fetch ignores HTTPS_PROXY unless NODE_USE_ENV_PROXY=1 is set at
// startup (cloud containers egress only through the proxy), so re-exec once.
if ((process.env.HTTPS_PROXY || process.env.https_proxy) && !process.env.NODE_USE_ENV_PROXY) {
  const r = spawnSync(process.execPath, ['--no-warnings', ...process.argv.slice(1)], {
    stdio: 'inherit', env: { ...process.env, NODE_USE_ENV_PROXY: '1' },
  });
  process.exit(r.status ?? 1);
}

const log = (...a) => process.stderr.write(`mcp-connect: ${a.join(' ')}\n`);
const argv = process.argv.slice(2);
const arg = (k) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : undefined; };
const MESH = arg('--mesh');
const PUBLIC = arg('--public');
const PROBE_MS = Number(process.env.MCP_MESH_PROBE_MS || 1500);
if (!MESH && !PUBLIC) { log('usage: mcp-connect.mjs [--mesh URL] [--public URL]'); process.exit(2); }

async function meshReachable(url) {
  if (!url || process.env.MCP_FORCE_PUBLIC === '1') return false;
  try { await fetch(url, { method: 'GET', signal: AbortSignal.timeout(PROBE_MS) }); return true; }
  catch { return false; }
}

function bearer() {
  const t = process.env.FLEET_BEARER || process.env.AUTHELIA_BEARER_TOKEN;
  if (t) return `Bearer ${t}`;
  const helper = path.join(path.dirname(new URL(import.meta.url).pathname), 'mcp-auth-headers.sh');
  try {
    const r = spawnSync('sh', [helper], { encoding: 'utf8', timeout: 15000, stdio: ['ignore', 'pipe', 'inherit'] });
    return JSON.parse(r.stdout || '{}').Authorization;
  } catch { return undefined; }
}

let url, headers = {};
if (await meshReachable(MESH)) {
  url = MESH; log(`mesh -> ${MESH}`);
} else if (PUBLIC) {
  url = PUBLIC;
  const a = bearer();
  if (a) headers.Authorization = a; else log('no FLEET_BEARER/AUTHELIA_BEARER_TOKEN — the edge will 403');
  log(`public -> ${PUBLIC}${a ? ' (bearer)' : ''}`);
} else {
  log(`mesh ${MESH} unreachable and no --public route`); process.exit(1);
}

// Some fleet servers omit "jsonrpc"; strict stdio clients reject that.
const out = (m) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...m }) + '\n');
let sessionId;

async function send(msg) {
  const h = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...headers };
  if (sessionId) h['Mcp-Session-Id'] = sessionId;
  try {
    const res = await fetch(url, { method: 'POST', headers: h, body: JSON.stringify(msg) });
    const sid = res.headers.get('mcp-session-id'); if (sid) sessionId = sid;
    if (res.status === 202 || res.status === 204) return;
    const ct = res.headers.get('content-type') || '';
    if (!res.ok) throw new Error(`HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);
    if (ct.includes('text/event-stream')) {
      const dec = new TextDecoder(); let buf = '', data = [];
      for await (const chunk of res.body) {
        buf += dec.decode(chunk, { stream: true });
        let i;
        while ((i = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, i).replace(/\r$/, ''); buf = buf.slice(i + 1);
          if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
          else if (line === '' && data.length) { out(JSON.parse(data.join('\n'))); data = []; }
        }
      }
      if (data.length) out(JSON.parse(data.join('\n')));
    } else {
      const txt = await res.text();
      if (txt.trim()) { const j = JSON.parse(txt); (Array.isArray(j) ? j : [j]).forEach(out); }
    }
  } catch (e) {
    log(String(e.message || e));
    if (msg && msg.id !== undefined) out({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: `mcp-connect: ${e.message || e}` } });
  }
}

// initialize must complete before anything else (it sets the session id);
// after that requests run concurrently, as the stdio transport allows.
let ready = null, buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => {
  buf += d; let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
    if (!line) continue;
    let msg; try { msg = JSON.parse(line); } catch { log('bad json on stdin'); continue; }
    if (msg.method === 'initialize') ready = send(msg);
    else (ready ?? Promise.resolve()).then(() => send(msg));
  }
});
process.stdin.on('end', async () => {
  await ready;
  if (sessionId) fetch(url, { method: 'DELETE', headers: { ...headers, 'Mcp-Session-Id': sessionId }, signal: AbortSignal.timeout(2000) }).catch(() => {});
});
