// Drives headless Chrome over the DevTools protocol at the owner's phone (Samsung Galaxy A37: 384 x 832 dp,
// device pixel ratio 2.8125, a 1080 x 2340 px screenshot) with MOBILE emulation on, so the page's own
// <meta viewport> is honoured exactly as on the phone. No dependency: Node's built-in WebSocket.
//
//   node render-a37.mjs <chrome> <drive-url> <diag-url> <out-dir> <expect-file> <expect-dir> <expect-md-heading>
//
// Prints one JSON line per check: {"id":..,"ok":..,"msg":..}; exit 1 when any is not ok.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [chromeBin, driveUrl, diagUrl, outDir, expectFile, expectDir, expectHeading] = process.argv.slice(2);
const W = 384, H = 832, DPR = 2.8125;
mkdirSync(outDir, { recursive: true });
const results = [];
const check = (id, ok, msg) => { results.push({ id, ok: !!ok, msg }); console.log(JSON.stringify({ id, ok: !!ok, msg })); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const profile = mkdtempSync(join(tmpdir(), 'c3ws-chrome-'));
const chrome = spawn(chromeBin, ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--hide-scrollbars',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
const finish = (code) => { try { chrome.kill('SIGKILL'); } catch {} process.exit(code); };
process.on('uncaughtException', (e) => { check('RUN', false, 'the render run crashed: ' + (e && e.stack || e)); finish(1); });

let port = 0;
for (let i = 0; i < 100 && !port; i++) {
  await sleep(100);
  try { port = parseInt(readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0], 10); } catch {}
}
if (!port) { check('RUN', false, 'Chrome did not open a DevTools port'); finish(1); }

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = targets.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pending = new Map(); const requested = new Set(); let loaded = null;
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
  if (d.method === 'Network.requestWillBeSent') requested.add(d.params.request.url);
  if (d.method === 'Page.loadEventFired' && loaded) loaded();
};
const send = (method, params = {}) => new Promise((res) => { const id = ++seq; pending.set(id, res); ws.send(JSON.stringify({ id, method, params })); });
const evalJs = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); if (r.result.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails)); return r.result.result.value; };
const until = async (expr, ms = 15000) => { const t = Date.now(); while (Date.now() - t < ms) { try { if (await evalJs(expr)) return true; } catch {} await sleep(100); } return false; };
const go = async (url) => { const p = new Promise((r) => { loaded = r; }); await send('Page.navigate', { url }); await Promise.race([p, sleep(15000)]); loaded = null; };
const shot = async (name) => {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  const buf = Buffer.from(r.result.data, 'base64'); writeFileSync(join(outDir, name + '.png'), buf);
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20), bytes: buf.length };
};

await send('Page.enable'); await send('Network.enable');
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: DPR, mobile: true, screenWidth: W, screenHeight: H });
await send('Emulation.setTouchEmulationEnabled', { enabled: true });

// ── R1: / is the Linux frontend: tree LEFT, viewer RIGHT, toolbar on TOP ──
await go(driveUrl);
const ready = await until(`document.querySelectorAll('#file-list .file-entry').length > 0`);
check('R1a', ready, ready ? 'the folder tree lists the served root' : 'the folder tree stayed empty');
const geo = await evalJs(`(() => { const r = (s) => { const e = document.querySelector(s); if (!e) return null; const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom, w: b.width, h: b.height, vis: getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none' }; };
  return { title: document.title, nav: !!document.querySelector('nav'), tabs: document.body.innerHTML.includes('__tab__'), side: r('#sidebar'), right: r('#right-panel'), bar: r('#viewer-toolbar'), viewer: r('#viewer'),
    search: r('#search'), dot: r('#showdot'), raw: r('#raw-toggle'), hamburger: r('#hamburger'), list: [...document.querySelectorAll('#file-list .file-entry')].map((e) => e.innerText.trim()),
    vw: window.innerWidth, vh: window.innerHeight, sw: document.documentElement.scrollWidth }; })()`);
check('R1b', geo.title === 'File Browser' && !geo.nav && !geo.tabs, `the page is the Linux File Browser and nothing else (title=${JSON.stringify(geo.title)}, custom nav=${geo.nav})`);
check('R1c', geo.side && geo.side.vis && geo.side.l <= 1 && geo.side.w >= geo.vw - 2, `on a phone the folder tree is the full-width panel (tree ${Math.round(geo.side?.l)}..${Math.round(geo.side?.r)} of ${geo.vw}); the viewer takes over once a file opens`)
check('R1d', geo.bar && geo.viewer && geo.bar.vis && geo.bar.b <= geo.viewer.t + 1 && geo.bar.t <= geo.right.t + 1 && geo.bar.w > 100, `toolbar is on TOP of the viewer (toolbar ${Math.round(geo.bar?.t)}..${Math.round(geo.bar?.b)}, viewer from ${Math.round(geo.viewer?.t)})`);
check('R1e', geo.search && geo.dot && geo.raw && geo.search.w > 20, 'the toolbar controls exist: search box, Dot files switch, Raw switch');
check('R1f', expectDir && geo.list.some((x) => x.includes(expectDir)), `the tree lists the served root's folders (${geo.list.slice(0, 4).join(' | ')})`);
check('R1g', geo.vw === W && geo.sw <= geo.vw, `the page lays out at the device width, no forced desktop viewport: innerWidth=${geo.vw} (want ${W}), scrollWidth=${geo.sw}`)
const s1 = await shot('tree-left-viewer-right-toolbar-top');
check('R1h', s1.w === 1080 && s1.h === 2340 && s1.bytes > 15000, `the screenshot is A37-sized and drawn: ${s1.w}x${s1.h} px, ${s1.bytes} bytes`);

// ── R2: open a Markdown file: the RIGHT pane renders it with the vendored renderer ──
await evalJs(`(() => { const items = [...document.querySelectorAll('#file-list .file-entry')]; const d = items.find((e) => e.innerText.includes(${JSON.stringify(expectDir)})); d && d.click(); })()`);
await until(`[...document.querySelectorAll('#file-list .file-entry')].some((e) => e.innerText.includes(${JSON.stringify(expectFile)}))`);
await evalJs(`(() => { const f = [...document.querySelectorAll('#file-list .file-entry')].find((e) => e.innerText.includes(${JSON.stringify(expectFile)})); f && f.click(); })()`);
const rendered = await until(`(() => { const f = document.querySelector('#viewer'); try { return !!f.contentDocument.querySelector('.markdown-body h1'); } catch (e) { return false; } })()`);
check('R2a', rendered, rendered ? 'a Markdown file is rendered in the right-hand viewer (marked + github-markdown-dark)' : 'the viewer did not render the Markdown file');
const heading = await evalJs(`(() => { try { return document.querySelector('#viewer').contentDocument.querySelector('.markdown-body h1').innerText; } catch (e) { return null; } })()`);
check('R2b', heading === expectHeading, `the rendered heading is the document's (${JSON.stringify(heading)})`);
const vp = await evalJs(`(() => { const b = document.querySelector('#viewer').getBoundingClientRect(), s = document.querySelector('#sidebar').getBoundingClientRect(); return { vl: b.left, sr: s.width ? s.right : 0, vw: b.width, hid: getComputedStyle(document.querySelector('#sidebar')).display }; })()`);
check('R2c', vp.sr <= 1 && vp.vw >= W - 2, `a file opened, the tree closed and the viewer fills the phone width (viewer ${Math.round(vp.vl)}+${Math.round(vp.vw)}; tree hidden: ${vp.hid})`)
const s2 = await shot('markdown-in-right-viewer');
check('R2d', s2.w === 1080 && s2.h === 2340 && s2.bytes > 15000, `second screenshot ${s2.w}x${s2.h} px, ${s2.bytes} bytes`);

// ── R3: the diagnostics page on :8003 is still its own page ──
await go(diagUrl);
await until(`document.body && document.body.innerText.length > 40`);
const diagText = await evalJs(`document.body.innerText.length`);
const s3 = await shot('diagnostics-8003');
check('R3', diagText > 40 && s3.w === 1080 && s3.h === 2340, `:8003 draws the diagnostics page (${diagText} chars, ${s3.w}x${s3.h} px)`);

// ── R4: nothing is fetched from off the phone ──
const off = [...requested].filter((u) => !/^(http:\/\/127\.0\.0\.1[:\/]|about:|data:|blob:)/.test(u));
check('R4', off.length === 0, off.length ? 'off-phone requests: ' + off.slice(0, 3).join(', ') : `no request left the phone (${requested.size} requests, all 127.0.0.1)`);

finish(results.every((r) => r.ok) ? 0 : 1);
