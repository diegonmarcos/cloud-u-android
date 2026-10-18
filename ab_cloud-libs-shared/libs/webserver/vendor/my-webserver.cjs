const { createServer } = require('node:http');
const { readFile, stat, lstat, readlink, readdir, mkdir, writeFile } = require('node:fs/promises');
const { join, extname, resolve, sep, dirname } = require('node:path');
const { homedir } = require('node:os');
const { execFile } = require('node:child_process');
const { timingSafeEqual } = require('node:crypto');
const { readFileSync } = require('node:fs');
const process = require('node:process');
// node:sea — present since Node 20, but only "active" (isSea() === true) when
// this file is running as a compiled Single Executable Application binary
// (node --experimental-sea-config + postject). `marked.min.js` and
// `github-markdown-dark.css` are embedded into the binary as SEA assets at
// build time (see my-webserver.sea-config.json) so the
// shipped release artifact is one file with no sidecar libs to fetch
// separately. Plain `node my-webserver.cjs` (local dev)
// still works — isSea() is false there and getLibAsset() falls back to a
// normal fs read from LIB_DIR below.
// CommonJS (.cjs), not ESM: Node's SEA main script is loaded as CJS even
// under --experimental-sea-config — `import` syntax fails at parse time
// ("Cannot use import statement outside a module") once postject-injected,
// confirmed 2026-08-10 by reproducing the exact build+inject steps locally.
const { isSea, getAsset } = require('node:sea');

const PORT = parseInt(process.argv[2] || '8000');
const ROOT = process.argv[3] || process.env.HOME || '.';
const LIB_DIR = join(homedir(), '.local/lib/httpd');

// SEA-embedded assets (see import comment above). All three lib files are
// baked into the binary, so the compiled artifact needs no populated LIB_DIR
// at all. browse.html used to be excluded ("small/rare enough to just read
// from disk") — but the only thing that ever populated LIB_DIR was the
// home.file deployment in the old web-server-md-eruda.nix module, which was
// orphaned when this tool moved to a prebuilt-binary asset. That left `/`
// serving a bare "browse.html not found" 404 on every install (2026-08-17).
// LIB_DIR is still consulted as a fallback below, so a hand-placed override
// keeps working for local iteration on the browse UI.
const SEA_ASSETS = new Set(['marked.min.js', 'github-markdown-dark.css', 'browse.html', 'js-yaml.min.js']);

async function getLibAsset(libFile) {
  if (SEA_ASSETS.has(libFile) && isSea()) {
    try {
      return Buffer.from(getAsset(libFile));
    } catch { /* fall through to fs read */ }
  }
  return readFile(join(LIB_DIR, libFile)).catch(() => null);
}
const VERBOSE = process.env.HTTPD_VERBOSE !== '0';  // default ON; set HTTPD_VERBOSE=0 to silence
const WRITE = process.env.HTTPD_WRITE === '1';  // default OFF; this server otherwise browses all of $HOME read-only

// join(ROOT, p) happily escapes ROOT on a `../` path — verified by
// /__api__/ls?path=../usr listing outside the served root. Resolve first,
// then require the result to still be inside ROOT. Returns null when it
// isn't, and every caller treats null as 404.
const RESOLVED_ROOT = resolve(ROOT);
function resolveInRoot(p) {
  const candidate = resolve(RESOLVED_ROOT, '.' + sep + (p || ''));
  if (candidate === RESOLVED_ROOT || candidate.startsWith(RESOLVED_ROOT + sep)) return candidate;
  return null;
}

// Write mode alone would expose every file under ROOT ($HOME), including
// shell profiles and ~/.ssh, which execute or authenticate on next login.
// Writes are therefore confined to an explicit allowlist of ROOT-relative
// directories; reads are deliberately unaffected. Fails closed: write mode
// with no allowlist permits nothing.
// Entries may be given as absolute filesystem paths (the natural way to set
// an env var) or as ROOT-relative paths — either way, resolve() against
// ROOT and then require containment in ROOT, same enforcement resolveInRoot
// does for request paths, just without forcing relative interpretation of a
// leading "/" (that forcing is correct for untrusted request paths, but
// entries here come from server configuration, not client input).
const WRITE_ROOTS = (process.env.HTTPD_WRITE_ROOTS || '')
  .split(':').filter(Boolean);
function resolveConfiguredRoot(p) {
  // Accept an entry written either as a real absolute path or as one relative
  // to ROOT, and try them in that order. Both orderings have a trap:
  // resolve(ROOT, "/allowed") discards the base and yields "/allowed" (outside
  // ROOT, entry dropped, allowlist empty, every write 403s), while forcing
  // relative interpretation of "<ROOT>/allowed" yields the doubled
  // "<ROOT>/<ROOT>/allowed" — which is still under ROOT, so it passes
  // containment and silently allowlists a directory nobody asked for.
  // Absolute-first is the only order that resolves both correctly, since a
  // non-ROOT absolute path fails containment and falls through to relative.
  const candidates = [resolve(p), resolve(RESOLVED_ROOT, '.' + sep + p)];
  for (const candidate of candidates) {
    if (candidate === RESOLVED_ROOT || candidate.startsWith(RESOLVED_ROOT + sep)) return candidate;
  }
  return null;
}
const RESOLVED_WRITE_ROOTS = WRITE_ROOTS.map(resolveConfiguredRoot).filter(Boolean);

// Shared secret for the write API, read once at startup from a 0600 file
// rendered by home-manager activation out of the sops-encrypted secrets.yaml.
// The CSRF header only constrains browsers; any local process can forge it.
// On Android every app has its own UID, so a 0600 file is a real boundary
// against other apps while still being readable by the tools that need it.
const WRITE_TOKEN_FILE = process.env.HTTPD_WRITE_TOKEN_FILE || '';
function loadWriteToken() {
  if (!WRITE_TOKEN_FILE) return '';
  try {
    return readFileSync(WRITE_TOKEN_FILE, 'utf8').trim();
  } catch {
    return '';
  }
}
const WRITE_TOKEN = loadWriteToken();

function resolveWritable(p) {
  const candidate = resolveInRoot(p);
  if (!candidate) return null;
  for (const entryDir of RESOLVED_WRITE_ROOTS) {
    if (candidate === entryDir || candidate.startsWith(entryDir + sep)) return candidate;
  }
  return null;
}

const ERUDA_SCRIPT = `<script src="https://cdn.jsdelivr.net/npm/eruda"></script><script>eruda.init();</script>`;

// ── ANSI colours for console (auto-disabled when stdout isn't a TTY) ──────
const TTY = process.stdout.isTTY;
const C = TTY ? {
  dim: '\x1b[2m', reset: '\x1b[0m', bold: '\x1b[1m',
  red: '\x1b[31m', green: '\x1b[32m', yellow: '\x1b[33m',
  blue: '\x1b[34m', magenta: '\x1b[35m', cyan: '\x1b[36m', gray: '\x1b[90m',
} : Object.fromEntries(['dim','reset','bold','red','green','yellow','blue','magenta','cyan','gray'].map(k => [k, '']));

function fmtBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

// Embed JSON content into an inline <script> tag SAFELY. The HTML parser
// closes a <script> on the first `</script>` it sees in the raw stream, so
// any JSON value containing that substring (or U+2028/U+2029, which JS
// treats as line terminators inside string literals) breaks the page.
// Standard fix used by every framework that inlines JSON in HTML.
function safeJsonForScript(value) {
  let s = JSON.stringify(value);
  s = s.replace(/<\/script/gi, '<\\/script');
  s = s.replace(/<!--/g, '<\\!--');
  s = s.split('\u2028').join('\\u2028');
  s = s.split('\u2029').join('\\u2029');
  return s;
}

const MIME = {
  '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript',
  '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2',
  '.ttf': 'font/ttf', '.pdf': 'application/pdf', '.webp': 'image/webp',
  '.mp4': 'video/mp4', '.webm': 'video/webm', '.txt': 'text/plain',
  '.xml': 'text/xml', '.mjs': 'application/javascript',
  '.md': 'text/markdown',
  // Code & config — serve as text so iframes render them
  '.ts': 'text/plain', '.tsx': 'text/plain', '.rs': 'text/plain',
  '.py': 'text/plain', '.sh': 'text/plain', '.fish': 'text/plain',
  '.nix': 'text/plain', '.yaml': 'text/plain', '.yml': 'text/plain',
  '.toml': 'text/plain', '.conf': 'text/plain', '.ini': 'text/plain',
  '.env': 'text/plain', '.log': 'text/plain', '.lock': 'text/plain',
  '.secrets': 'text/plain', '.service': 'text/plain', '.timer': 'text/plain',
  '.sql': 'text/plain', '.graphql': 'text/plain', '.tf': 'text/plain',
  '.scss': 'text/plain', '.less': 'text/plain',
};

// Fallback: if extension not in MIME map, try to detect text vs binary
// Web panels reachable as /app.<name>, each served out of its own project's
// dist/. Kept as data so adding one is an entry, not a code path; overridable
// with MY_WEBSERVER_APPS ("name=/path,name=/path") for a checkout that does not
// live where this default expects it.
const APPS = (() => {
  const home = process.env.HOME || '.';
  const apps = {
    watchdog: join(home, 'git/cloud-u-linux/da_c3-watchdog/web/dist'),
  };
  for (const pair of (process.env.MY_WEBSERVER_APPS || '').split(',')) {
    const i = pair.indexOf('=');
    if (i > 0) apps[pair.slice(0, i).trim()] = pair.slice(i + 1).trim();
  }
  return apps;
})();

function guessMime(ext, content) {
  if (MIME[ext]) return MIME[ext];
  // If content is provided and looks like text, serve as text/plain
  if (content && !content.includes('\0')) return 'text/plain';
  return 'application/octet-stream';
}

function dirListing(urlPath, entries) {
  const rows = entries.map(e => {
    const slash = e.isDir ? '/' : '';
    const href = urlPath === '/' ? `/${e.name}${slash}` : `${urlPath}/${e.name}${slash}`;
    const icon = e.isDir ? '&#128193;' : e.name.endsWith('.md') ? '&#128220;' : '&#128196;';
    return `<tr><td>${icon}</td><td><a href="${href}">${e.name}${slash}</a></td></tr>`;
  }).join('\n');

  return `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Index of ${urlPath}</title>
<style>
  body{background:#1a1a2e;color:#e0e0e0;font-family:monospace;padding:20px;margin:0}
  h1{color:#e94560;font-size:1.2rem}
  table{border-collapse:collapse;width:100%}
  td{padding:4px 12px}
  a{color:#0f3460;text-decoration:none;color:#56c5ff}
  a:hover{text-decoration:underline;color:#e94560}
  tr:hover{background:rgba(255,255,255,0.05)}
</style></head><body>
<h1>Index of ${urlPath}</h1>
<table>${urlPath !== '/' ? '<tr><td>&#128193;</td><td><a href="../">../</a></td></tr>' : ''}
${rows}</table>
${ERUDA_SCRIPT}
</body></html>`;
}

function markdownPage(urlPath, mdContent) {
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${urlPath}</title>
<link rel="stylesheet" href="/__lib__/github-markdown-dark.css">
<style>
  body{background:#0d1117;box-sizing:border-box;min-width:200px;max-width:980px;margin:0 auto;padding:45px}
  @media(max-width:767px){body{padding:15px}}
  .markdown-body{background:#0d1117}
  .nav-bar{margin-bottom:16px;padding-bottom:8px;border-bottom:1px solid #30363d;font-family:monospace;font-size:0.85rem}
  .nav-bar a{color:#56c5ff;text-decoration:none}
  .nav-bar a:hover{text-decoration:underline;color:#e94560}
  .nav-bar .sep{color:#484f58;margin:0 4px}
  .raw-link{float:right;color:#8b949e;font-size:0.8rem}
  .raw-link:hover{color:#56c5ff}
</style></head><body class="markdown-body">
<div class="nav-bar">
  ${breadcrumb(urlPath)}
  <a class="raw-link" href="${urlPath}?raw">view raw</a>
</div>
<div id="content"></div>
<script src="/__lib__/marked.min.js"></script>
<script>
  const rawMarkdown = ${safeJsonForScript(mdContent)};
  document.getElementById('content').innerHTML = marked.parse(rawMarkdown);
</script>
${ERUDA_SCRIPT}
</body></html>`;
}

// ── SvelteKit adapter-static SPA fallback ──────────────────────────────────
// adapter-static's `fallback: '200.html'` is the standard marker a custom
// Node server is expected to serve for any route path that isn't a real
// file — SvelteKit's own client router then takes over from
// location.pathname. See https://svelte.dev/docs/kit/single-page-apps.
// Walk up from the 404'd path to find the nearest project dir (marked by
// containing a 200.html) — this lets ANY prerendered SvelteKit project
// under $ROOT get correct SPA fallback with zero per-project config.
async function findAncestorFallback(rootFsDir, urlPath) {
  let dir = urlPath.replace(/\/[^/]*$/, '') || '/';
  while (true) {
    const candidate = join(rootFsDir, dir, '200.html');
    if (await stat(candidate).catch(() => null)) return { fallbackPath: candidate, projectUrlDir: dir };
    if (dir === '/' || dir === '') return null;
    dir = dir.replace(/\/[^/]*$/, '') || '/';
  }
}

// adapter-static's 200.html always emits ROOT-ABSOLUTE asset paths
// (href="/_app/...") regardless of `paths.relative`, because it must work
// for any unknown route depth. Since this server hosts many projects under
// one root, root-absolute paths resolve against the WRONG (server) root —
// rewrite them to be absolute from the project's own mount directory.
function rewriteFallbackAbsolutePaths(html, projectUrlDir) {
  if (projectUrlDir === '/' || projectUrlDir === '') return html; // already at server root
  return html.replace(/(href|src)="\/(?!\/)/g, `$1="${projectUrlDir}/`);
}

function breadcrumb(urlPath) {
  const parts = urlPath.split('/').filter(Boolean);
  let crumbs = '<a href="/">~</a>';
  let href = '';
  for (const part of parts.slice(0, -1)) {
    href += '/' + part;
    crumbs += `<span class="sep">/</span><a href="${href}/">${part}</a>`;
  }
  if (parts.length > 0) {
    crumbs += `<span class="sep">/</span>${parts[parts.length - 1]}`;
  }
  return crumbs;
}

// Unified structured data renderer — exact same logic as cloud-data/index.html
// Handles: .json, .yaml, .secrets — with copy buttons, typed values, cards, tables
function structuredPage(urlPath, rawContent, format) {
  const fileName = urlPath.split('/').pop();
  const isSecret = format === 'secrets';
  const badgeColor = isSecret ? '#ff6b6b' : '#5bc0eb';
  const badgeBg = isSecret ? 'rgba(255,107,107,0.15)' : 'rgba(91,192,235,0.15)';
  const badgeBorder = isSecret ? 'rgba(255,107,107,0.3)' : 'rgba(91,192,235,0.3)';
  const badgeText = isSecret ? 'DECRYPTED' : format.toUpperCase();

  return `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${urlPath}</title>
<style>
:root{--bg:#1a1a2e;--bg-card:#16213e;--bg-head:#0f3460;--bg-row-alt:#1b2a4a;--border:#2a3a5e;--text:#e0e0e0;--text-dim:#8899aa;--accent:#00d68f;--link:#5bc0eb;--mono:'Courier New',Consolas,monospace}
*{margin:0;padding:0;box-sizing:border-box}
body{background:var(--bg);color:var(--text);font-family:var(--mono);padding:24px 32px}
h2{font-size:18px;color:var(--accent);margin-bottom:20px;padding-bottom:8px;border-bottom:1px solid var(--border)}
.badge{font-size:10px;color:${badgeColor};background:${badgeBg};border:1px solid ${badgeBorder};border-radius:3px;padding:2px 6px;letter-spacing:1px;vertical-align:middle}
.nav{margin-bottom:16px;padding-bottom:8px;border-bottom:1px solid var(--border);font-size:12px}
.nav a{color:var(--link);text-decoration:none}.nav a:hover{text-decoration:underline}
.nav .sep{color:#484f58;margin:0 4px}
.section{margin-bottom:28px}
.section h3{font-size:14px;color:var(--link);margin-bottom:10px;text-transform:uppercase;letter-spacing:0.5px}
table{width:100%;border-collapse:collapse;font-size:12px;margin-bottom:8px}
thead th{background:var(--bg-head);color:var(--text);padding:8px 12px;text-align:left;font-weight:bold;border:1px solid var(--border);position:sticky;top:0;white-space:nowrap}
tbody td{padding:6px 12px;border:1px solid var(--border);vertical-align:top;max-width:400px;overflow-wrap:break-word}
tbody tr:nth-child(even){background:var(--bg-row-alt)}
tbody tr:hover{background:rgba(0,214,143,0.05);cursor:pointer}
tbody tr.row-copied{background:rgba(0,214,143,0.2);transition:background 0.3s}
.card{background:var(--bg-card);border:1px solid var(--border);border-radius:6px;padding:14px 18px;margin-bottom:12px}
.card h4{font-size:13px;color:var(--accent);margin-bottom:8px}
.card table{margin-bottom:0}
/* Collapsing is <details>, not a JS widget: it is keyboard accessible, it has
   correct semantics for a screen reader, and modern browsers auto-expand a
   closed <details> when Ctrl-F finds text inside it — none of which a
   div-and-onclick toggle gets without work. */
details.section>summary,details.card>summary{cursor:pointer;list-style:none;user-select:none}
details.section>summary::-webkit-details-marker,details.card>summary::-webkit-details-marker{display:none}
details.section>summary::before,details.card>summary::before{content:'▸';display:inline-block;width:12px;color:var(--text-dim);transition:transform 0.12s ease}
details[open]>summary::before{transform:rotate(90deg)}
details.section>summary{font-size:14px;color:var(--link);text-transform:uppercase;letter-spacing:0.5px;margin-bottom:10px}
details.card>summary{font-size:13px;color:var(--accent);margin-bottom:8px}
.count{color:var(--text-dim);font-weight:normal;text-transform:none;letter-spacing:0}
th.sortable{cursor:pointer}
th.sortable::after{content:'↕';color:var(--text-dim);margin-left:6px;font-size:10px}
th[data-dir=asc]::after{content:'▲';color:var(--accent)}
th[data-dir=desc]::after{content:'▼';color:var(--accent)}
.filter-bar{margin-bottom:16px}
.filter-bar input{width:100%;max-width:420px;padding:6px 10px;font-family:var(--mono);font-size:12px;background:var(--bg-card);color:var(--text);border:1px solid var(--border);border-radius:4px}
.filter-bar input:focus{outline:none;border-color:var(--accent)}
.filter-bar .hits{color:var(--text-dim);font-size:11px;margin-left:10px}
.filtered-out{display:none}
.val-array{font-size:11px;color:var(--text-dim)}
.val-object{font-size:11px;color:var(--text-dim);font-style:italic}
.val-null{color:#555}
.val-number{color:#f0a500}
.val-bool-true{color:var(--accent)}
.val-bool-false{color:#ff6b6b}
.copy-btn{background:none;border:1px solid var(--border);border-radius:3px;color:var(--text-dim);cursor:pointer;font-size:14px;padding:2px 6px;margin-left:8px;vertical-align:middle;transition:all 0.15s;font-family:var(--mono)}
.copy-btn:hover{color:var(--accent);border-color:var(--accent);background:rgba(0,214,143,0.1)}
.copy-btn.copied{color:var(--accent);border-color:var(--accent)}
pre.fallback{background:var(--bg-card);padding:16px;border-radius:6px;border:1px solid var(--border);white-space:pre-wrap;word-break:break-all;font-size:12px;overflow:auto;max-height:80vh}
</style></head><body>
<div class="nav">${breadcrumb(urlPath)} <a style="float:right;color:var(--text-dim);font-size:11px" href="${urlPath}?raw">view raw</a></div>
<h2>${fileName} <span class="badge">${badgeText}</span></h2>
<div class="filter-bar"><input type="text" id="docfilter" placeholder="Filter rows..." autocomplete="off"><span class="hits" id="filterhits"></span></div>
<div id="root"></div>
${format === 'yaml' ? '<script src="/__lib__/js-yaml.min.js"></script>' : ''}
<script>
const raw = ${safeJsonForScript(rawContent)};
const isSecret = ${isSecret};

function escHtml(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function prettyKey(k) { return k.replace(/_/g,' ').replace(/\\b\\w/g,c=>c.toUpperCase()); }

function copyToClipboard(btn, text) {
  navigator.clipboard.writeText(text).then(()=>{
    btn.classList.add('copied'); btn.innerHTML='\\u2713';
    setTimeout(()=>{btn.classList.remove('copied');btn.innerHTML='\\u2398';},1500);
  });
}

document.addEventListener('dblclick',(e)=>{
  const tr=e.target.closest('tbody tr');
  if(!tr)return;
  const cells=tr.querySelectorAll('td');
  if(cells.length<2)return;
  const valCell=cells[1];
  const text=Array.from(valCell.childNodes).filter(n=>!n.classList||!n.classList.contains('copy-btn')).map(n=>n.textContent).join('').trim();
  if(!text)return;
  navigator.clipboard.writeText(text).then(()=>{
    tr.classList.add('row-copied');
    setTimeout(()=>tr.classList.remove('row-copied'),800);
  });
});

function renderValue(val, depth) {
  depth = depth || 0;
  const span = document.createElement('span');
  if (val===null||val===undefined) { span.className='val-null'; span.textContent='—'; }
  else if (typeof val==='boolean') { span.className=val?'val-bool-true':'val-bool-false'; span.textContent=String(val); }
  else if (typeof val==='number') { span.className='val-number'; span.textContent=String(val); }
  else if (Array.isArray(val)) {
    if (!val.length) { span.className='val-null'; span.textContent='—'; }
    else if (val[0]!==null&&typeof val[0]==='object') return renderArrayTable(val, depth);
    else { span.className='val-array'; span.textContent=val.join(', '); }
  } else if (typeof val==='object') {
    // Recurse. This used to be JSON.stringify(val) unconditionally, which is
    // why anything nested more than two levels deep arrived as a wall of
    // escaped text instead of a table — the watchdog snapshots are depth 5.
    // Capped, because the point is readability: past a few levels a table of
    // tables is worse than the raw JSON, and an unbounded recurse would also
    // hang on a self-referential object.
    if (depth>=3) { span.className='val-object'; span.textContent=JSON.stringify(val); }
    else return renderKVTable(val, depth+1);
  }
  else { span.textContent=String(val); }
  return span;
}

function renderArrayTable(arr, depth) {
  depth = depth || 0;
  if (!arr.length) return document.createTextNode('(empty)');
  // arr[0]===null matters: typeof null is 'object', so the old test sent a
  // [null, ...] array down the table path and Object.keys(null) threw.
  if (arr[0]===null||typeof arr[0]!=='object') { const d=document.createElement('div'); d.className='val-array'; d.textContent=arr.join(', '); return d; }
  const keys=[...new Set(arr.flatMap(obj=>Object.keys(obj)))].filter(k=>k!=='_meta');
  const table=document.createElement('table');
  const thead=document.createElement('thead');
  const hr=document.createElement('tr');
  keys.forEach(k=>{const th=document.createElement('th');th.textContent=prettyKey(k);hr.appendChild(th);});
  thead.appendChild(hr); table.appendChild(thead);
  const tbody=document.createElement('tbody');
  arr.forEach(obj=>{
    const tr=document.createElement('tr');
    keys.forEach(k=>{const td=document.createElement('td');td.appendChild(renderValue(obj[k], depth+1));tr.appendChild(td);});
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  makeSortable(table);
  return table;
}

// Click a header to sort. Numeric only when the WHOLE cell is a number —
// otherwise "1.2.3" and "10 items" would sort as 1.2 and 10 and quietly
// reorder rows by a value that is not what the cell says.
function makeSortable(table) {
  const head = table.tHead; if (!head || !head.rows.length) return;
  const cells = Array.from(head.rows[0].cells);
  cells.forEach(function (th, idx) {
    th.classList.add('sortable');
    th.addEventListener('click', function () {
      const dir = th.dataset.dir === 'asc' ? 'desc' : 'asc';
      cells.forEach(function (c) { delete c.dataset.dir; });
      th.dataset.dir = dir;
      const tb = table.tBodies[0]; if (!tb) return;
      const isNum = function (v) { return /^-?\d+(\.\d+)?$/.test(v); };
      const val = function (tr) { return tr.cells[idx] ? tr.cells[idx].textContent.trim() : ''; };
      Array.from(tb.rows).sort(function (a, b) {
        const x = val(a), y = val(b);
        const c = (isNum(x) && isNum(y))
          ? parseFloat(x) - parseFloat(y)
          : x.localeCompare(y, undefined, { numeric: true });
        return dir === 'asc' ? c : -c;
      }).forEach(function (r) { tb.appendChild(r); });
    });
  });
}

function renderKVTable(obj, depth) {
  depth = depth || 0;
  const table=document.createElement('table');
  const tbody=document.createElement('tbody');
  for (const [k,v] of Object.entries(obj)) {
    const tr=document.createElement('tr');
    const th=document.createElement('td');
    th.innerHTML='<strong>'+escHtml(prettyKey(k))+'</strong>';
    tr.appendChild(th);
    const td=document.createElement('td');
    td.appendChild(renderValue(v, depth));
    // Copy button on every row (secrets + data)
    const btn=document.createElement('button');
    btn.className='copy-btn'; btn.innerHTML='\\u2398'; btn.title='Copy value';
    const rawVal=typeof v==='object'?JSON.stringify(v):String(v??'');
    btn.addEventListener('click',()=>copyToClipboard(btn,rawVal));
    td.appendChild(btn);
    tr.appendChild(td);
    tbody.appendChild(tr);
  }
  table.appendChild(tbody); return table;
}

// Anything longer than this starts collapsed.
const BIG = 20;
function sizeOf(v) {
  if (Array.isArray(v)) return v.length;
  if (v !== null && typeof v === 'object') return Object.keys(v).length;
  return 0;
}
function addCount(summary, v) {
  const n = sizeOf(v);
  if (!n) return;
  const span = document.createElement('span');
  span.className = 'count';
  span.textContent = '  (' + n + ')';
  summary.appendChild(span);
}

function renderObjectSections(container, obj) {
  for (const [key,val] of Object.entries(obj)) {
    if (key==='_meta') continue;
    const section=document.createElement('details'); section.className='section'; section.open=true;
    const h3=document.createElement('summary'); h3.textContent=prettyKey(key); section.appendChild(h3);
    // The count goes in the summary so a collapsed section is never mistaken
    // for an absent one — the same reason a truncated search says it truncated.
    addCount(h3, val);
    // Only fold away what is actually in the way. The watchdog snapshot's
    // "files" is 358 entries; scrolling past it to reach the metrics is the
    // complaint. Anything small stays open, so nothing hides unexpectedly.
    if (sizeOf(val) > BIG) section.open=false;
    if (Array.isArray(val)) { section.appendChild(renderArrayTable(val)); }
    else if (typeof val==='object'&&val!==null) {
      // Partition by what each sub-key actually holds. This used to pick the
      // layout for the WHOLE object from subKeys[0] alone, then apply it to
      // every sub-key. Real objects are heterogeneous: the watchdog snapshot
      // has 38 sub-keys of which the first (battery) is an object and 18 are
      // plain numbers — so all 38 went down the card path, and renderKVTable
      // on a number is Object.entries(88.0) === [], i.e. an empty card. Every
      // headline metric (cpu 88.0, mem 97.3, slice_pct 91.4) rendered as a
      // heading over a blank table. Nothing was malformed; the shape was
      // inferred from a sample of one.
      const subKeys=Object.keys(val).filter(k=>k!=='_meta');
      const scalars=subKeys.filter(k=>val[k]===null||typeof val[k]!=='object');
      const nested=subKeys.filter(k=>val[k]!==null&&typeof val[k]==='object');
      if (scalars.length) {
        const flat={}; scalars.forEach(k=>{flat[k]=val[k];});
        section.appendChild(renderKVTable(flat));
      }
      nested.forEach(sk=>{
        const card=document.createElement('details'); card.className='card'; card.open=true;
        const h4=document.createElement('summary'); h4.textContent=sk; card.appendChild(h4);
        addCount(h4, val[sk]);
        if (sizeOf(val[sk]) > BIG) card.open=false;
        card.appendChild(Array.isArray(val[sk])?renderArrayTable(val[sk]):renderKVTable(val[sk]));
        section.appendChild(card);
      });
      if (!subKeys.length) {
        const p=document.createElement('p'); p.className='val-null'; p.textContent='(empty)';
        section.appendChild(p);
      }
    } else { const p=document.createElement('p'); p.appendChild(renderValue(val)); section.appendChild(p); }
    container.appendChild(section);
  }
}

function renderData(container, data) {
  if (Array.isArray(data)) { container.appendChild(renderArrayTable(data)); }
  else if (typeof data==='object'&&data!==null) {
    const vals=Object.values(data);
    const isFlat=vals.length&&vals.every(v=>typeof v!=='object'||v===null);
    if (isFlat) { container.appendChild(renderKVTable(data)); }
    else { renderObjectSections(container, data); }
  } else { container.appendChild(document.createTextNode(String(data))); }
}

// Real YAML, via the vendored js-yaml — the same route lib/marked.min.js
// already takes. What this replaces was six lines of regex that matched only
// top-level "key: value" and set ok=false on the FIRST line it did not
// understand, dumping the whole document as plain text. Every nested mapping,
// every list, every --- separator, every block scalar hit that. The watchdog
// YAML snapshots are depth 6 with a 358-entry list, so they always did.
//
// The quieter half was worse: when it DID match it corrupted silently.
// The v-or-null idiom turned enabled:false and retries:0 into null, rendering
// them as an em-dash, indistinguishable from unset; and the isNaN coercion
// turned version:1.20 into 1.2 and 1e5 into 100000. A raw dump is obviously
// raw — wrong values that look parsed are not.
// Filter hides non-matching ROWS and then any section left with nothing to
// show, and force-opens whatever still matches — a hit inside a collapsed
// section you cannot see is the same as no hit at all.
function applyFilter(q) {
  const root = document.getElementById('root');
  const rows = root.querySelectorAll('tbody tr');
  let hits = 0;
  rows.forEach(function (tr) {
    const hit = !q || tr.textContent.toLowerCase().indexOf(q) !== -1;
    tr.classList.toggle('filtered-out', !hit);
    if (q && hit) hits++;
  });
  root.querySelectorAll('details').forEach(function (d) {
    if (!q) { d.classList.remove('filtered-out'); return; }
    const visible = d.querySelectorAll('tbody tr:not(.filtered-out)').length;
    d.classList.toggle('filtered-out', visible === 0);
    if (visible) d.open = true;
  });
  document.getElementById('filterhits').textContent =
    q ? (hits + (hits === 1 ? ' row' : ' rows')) : '';
}

let filterDebounce;
document.getElementById('docfilter').addEventListener('input', function (e) {
  clearTimeout(filterDebounce);
  const v = e.target.value.trim().toLowerCase();
  filterDebounce = setTimeout(function () { applyFilter(v); }, 120);
});

try {
  const fmt = ${JSON.stringify(format)};
  const data = fmt === 'yaml' ? jsyaml.load(raw) : JSON.parse(raw);
  // A YAML document may legitimately be empty; renderData handles arrays and
  // scalars at the root, so anything else defined is renderable as-is.
  if (data === undefined) throw new Error('empty document');
  renderData(document.getElementById('root'), data);
} catch(e) {
  document.getElementById('root').innerHTML='<pre class="fallback">'+escHtml(raw)+'</pre>';
}
</script>
${ERUDA_SCRIPT}
</body></html>`;
}

async function serveBrowse(res, initPath) {
  // getLibAsset, not a bare LIB_DIR read: browse.html is SEA-embedded, and
  // the compiled binary is the only way this ships.
  const data = await getLibAsset('browse.html');
  if (!data) { res.writeHead(404); return res.end('browse.html not found'); }
  let html = data.toString('utf8');
  // Inject the initial path so no redirect/hash needed
  html = html.replace('__INIT_PATH__', JSON.stringify(initPath));
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  return res.end(html);
}

// ── Mutating-route guards ──────────────────────────────────────────────────
// The loopback IP check earlier in the handler stops remote attackers, but
// NOT a malicious page open in the user's own browser: the browser will
// happily fire a same-origin-looking POST to 127.0.0.1 on the user's behalf
// (classic CSRF). Defend with a custom request header (x-httpd-write): the
// fetch/XHR spec forces a CORS preflight (OPTIONS) for any request carrying
// a non-"simple" header, and since this server never answers that preflight
// with permissive Access-Control-Allow-* headers, the browser blocks the
// actual request before it ever reaches us. A plain <form> POST cannot set
// custom headers at all, so that vector is closed too. Do NOT "simplify"
// this away — without the custom header, any website could silently write
// files or push commits through the user's browser.
function checkMutationAllowed(req) {
  if (req.method !== 'POST') return { ok: false, status: 405, error: 'method not allowed, use POST' };
  if (req.headers['x-httpd-write'] !== '1') return { ok: false, status: 403, error: 'missing x-httpd-write header' };
  const ct = (req.headers['content-type'] || '').toString();
  if (!ct.toLowerCase().startsWith('application/json')) {
    return { ok: false, status: 403, error: 'content-type must be application/json' };
  }
  const origin = req.headers['origin'];
  if (origin) {
    try {
      const originHost = new URL(origin).hostname;
      if (originHost !== 'localhost' && originHost !== '127.0.0.1') {
        return { ok: false, status: 403, error: 'origin not allowed' };
      }
    } catch {
      return { ok: false, status: 403, error: 'invalid origin header' };
    }
  }
  // Shared-secret token check, on top of the CSRF header above. Fails closed:
  // an empty WRITE_TOKEN (missing/unreadable HTTPD_WRITE_TOKEN_FILE) means
  // "no writes", not "no check needed".
  if (WRITE_TOKEN.length === 0) {
    return { ok: false, status: 403, error: 'write token not configured' };
  }
  const provided = (req.headers['x-httpd-token'] || '').toString();
  const providedBuf = Buffer.from(provided);
  const expectedBuf = Buffer.from(WRITE_TOKEN);
  const tokenMatches = providedBuf.length === expectedBuf.length &&
    timingSafeEqual(providedBuf, expectedBuf);
  if (!tokenMatches) {
    return { ok: false, status: 403, error: 'invalid write token' };
  }
  return { ok: true };
}

const MAX_BODY_BYTES = 32 * 1024 * 1024; // 32 MB hard cap

function readJsonBody(req) {
  return new Promise((resolvePromise, rejectPromise) => {
    const chunks = [];
    let total = 0;
    let tooBig = false;
    req.on('data', (chunk) => {
      if (tooBig) return;
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        tooBig = true;
        rejectPromise({ status: 413, error: 'request body too large (>32MB)' });
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (tooBig) return;
      const raw = Buffer.concat(chunks).toString('utf8');
      try {
        resolvePromise(raw ? JSON.parse(raw) : {});
      } catch {
        rejectPromise({ status: 400, error: 'malformed JSON body' });
      }
    });
    req.on('error', () => {
      if (!tooBig) rejectPromise({ status: 400, error: 'error reading request body' });
    });
  });
}

// execFile-based (never a shell string) git runner — arguments are always
// passed as an array so nothing is shell-interpreted. Never rejects: any
// failure comes back as { ok: false, ... } so a git failure can never become
// an unhandled promise rejection that crashes the process.
function runGit(args, cwd) {
  return new Promise((resolvePromise) => {
    execFile('git', args, { cwd, timeout: 300000, maxBuffer: 32 * 1024 * 1024 }, (err, stdout, stderr) => {
      resolvePromise({
        ok: !err,
        code: err && typeof err.code === 'number' ? err.code : (err ? 1 : 0),
        output: `$ git ${args.join(' ')}\n${stdout || ''}${stderr || ''}`,
      });
    });
  });
}

// ── Per-request logging ────────────────────────────────────────────────────
// Captured at request start, finalised on res 'finish'. Emits one line per
// request unless HTTPD_VERBOSE=0. Format:
//   [HH:MM:SS] METHOD STATUS  duration  size  RENDER  /path
function logRequest(req, res, ctx) {
  if (!VERBOSE) return;
  const t0 = process.hrtime.bigint();
  res.on('finish', () => {
    const dur = Number(process.hrtime.bigint() - t0) / 1e6;
    const ts = new Date().toISOString().slice(11, 19);
    const status = res.statusCode;
    const statusColor = status < 300 ? C.green : status < 400 ? C.cyan : status < 500 ? C.yellow : C.red;
    const ip = req.socket.remoteAddress?.replace(/^::ffff:/, '') || '?';
    const size = res.getHeader('content-length') ?? ctx.size ?? 0;
    const ct = (res.getHeader('content-type') || '').toString().split(';')[0] || '-';
    const render = ctx.render || '-';
    console.log(
      `${C.gray}[${ts}]${C.reset} ` +
      `${C.bold}${(req.method || '-').padEnd(4)}${C.reset} ` +
      `${statusColor}${status}${C.reset}  ` +
      `${C.dim}${dur.toFixed(1).padStart(6)}ms${C.reset}  ` +
      `${C.dim}${fmtBytes(Number(size)).padStart(8)}${C.reset}  ` +
      `${C.magenta}${render.padEnd(8)}${C.reset} ` +
      `${C.cyan}${req.url}${C.reset}` +
      (ctx.note ? `  ${C.dim}(${ctx.note})${C.reset}` : '') +
      (ip !== '127.0.0.1' ? `  ${C.dim}from ${ip}${C.reset}` : '')
    );
  });
}

const server = createServer(async (req, res) => {
  // ctx is filled progressively as we route; logRequest reads it on 'finish'
  const ctx = { render: '-', size: 0, note: null };
  logRequest(req, res, ctx);
  try {
    // App-level firewall: reject non-loopback requests
    const ip = req.socket.remoteAddress;
    if (ip !== '127.0.0.1' && ip !== '::1' && ip !== '::ffff:127.0.0.1') {
      ctx.render = 'forbid'; ctx.note = `non-loopback ip=${ip}`;
      res.writeHead(403, { 'content-type': 'text/plain' });
      return res.end('Forbidden');
    }

    const url = new URL(req.url, 'http://localhost');
    const urlPath = decodeURIComponent(url.pathname);

    // ── Browse SPA (backward compat) ──
    if (urlPath === '/__browse__' || urlPath === '/__browse__/') {
      ctx.render = 'browse';
      return serveBrowse(res, '/');
    }

    // ── API: directory listing ──
    // The machine's own metrics, so the hub can ask over HTTP instead of
    // opening an ssh session and running a collector script on the far end.
    //
    // This does NOT sample anything: my-watchdog is the publisher, it writes
    // one file every two seconds, and this hands that file over. Two things
    // follow from that being the whole implementation — a peer without the
    // watchdog running says so rather than serving a stale or invented
    // snapshot, and this route can never disagree with what the machine's own
    // panel is showing, because it is literally the same bytes.
    //
    // Deliberately outside the served root: the snapshot lives in the runtime
    // directory, and the alternative — asking people to serve /run — is a far
    // worse trade than one hard-coded read-only path.
    if (urlPath === '/__api__/watchdog') {
      ctx.render = 'api-watchdog';
      // Same order my-watchdog resolves: XDG_RUNTIME_DIR, then /run/user/<uid>,
      // then /tmp. A service started outside a login session has no
      // XDG_RUNTIME_DIR, and that is the common case on a VM.
      const uid = process.getuid ? process.getuid() : 1000;
      const candidates = [
        // The fleet layout: a root sampler (vm-pilot's system unit) publishes here.
        '/run/my-watchdog/my-konsole-watchdog.json',
        process.env.XDG_RUNTIME_DIR && `${process.env.XDG_RUNTIME_DIR}/my-konsole-watchdog.json`,
        `/run/user/${uid}/my-konsole-watchdog.json`,
        `/tmp/my-konsole-${uid}/my-konsole-watchdog.json`,
      ].filter(Boolean);
      let body = null;
      let snap = candidates[candidates.length - 1];
      for (const c of candidates) {
        const t = await readFile(c, 'utf8').catch(() => null);
        if (t !== null) { body = t; snap = c; break; }
      }
      if (body === null) {
        res.writeHead(503, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({
          error: 'no snapshot',
          detail: `${snap} is not readable — is my-watchdog running on this machine?`,
        }));
      }
      // no-store: it is two seconds old by construction and a cache would
      // serve yesterday's numbers under today's timestamp.
      res.writeHead(200, {
        'content-type': 'application/json',
        'cache-control': 'no-store',
        'access-control-allow-origin': '*',
      });
      return res.end(body);
    }

    if (urlPath === '/__api__/ls') {
      ctx.render = 'api-ls';
      const dirPath = url.searchParams.get('path') || '/';
      const fsDir = resolveInRoot(dirPath);
      if (!fsDir) {
        res.writeHead(404, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Not a directory' }));
      }
      const info = await stat(fsDir).catch(() => null);
      if (!info || !info.isDirectory()) {
        res.writeHead(404, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Not a directory' }));
      }
      // Dotfiles stay hidden unless asked for. ROOT is $HOME and reads are not
      // confined to the write allowlist, so the dot filter is the only thing
      // standing between an unauthenticated GET and ~/.ssh, ~/.aws and every
      // shell profile. Opt-in per request, never sticky server-side: a client
      // that does not ask cannot be handed them by something else's setting.
      // Note this only ever hid them from LISTINGS — an explicit path always
      // resolved, so this grants no read that was not already reachable.
      const showDot = url.searchParams.get('dot') === '1';
      const items = await readdir(fsDir);
      const entries = [];
      for (const name of items) {
        if (!showDot && name.startsWith('.')) continue;
        const full = join(fsDir, name);
        const s = await stat(full).catch(() => null);
        if (s) { entries.push({ name, isDir: s.isDirectory() }); continue; }
        // stat() FOLLOWS symlinks, so a dangling one throws ENOENT and used to
        // be dropped here — the entry simply vanished from the listing, which
        // is indistinguishable from "the file was never there". That cost real
        // debugging time on a link that was one ../ short of its target: the
        // shell showed the file, the browser showed nothing, and neither said
        // why. lstat() stats the LINK rather than what it points at, so a
        // broken one can be listed as what it actually is.
        const l = await lstat(full).catch(() => null);
        if (l && l.isSymbolicLink()) {
          entries.push({ name, isDir: false, broken: true, target: await readlink(full).catch(() => null) });
        }
      }
      entries.sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name));
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(entries));
    }

    // ── API: read file ──
    if (urlPath === '/__api__/read') {
      ctx.render = 'api-read';
      const filePath = url.searchParams.get('path') || '/';
      const fsFile = resolveInRoot(filePath);
      if (!fsFile) {
        res.writeHead(404, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Not a file' }));
      }
      const info = await stat(fsFile).catch(() => null);
      if (!info || info.isDirectory()) {
        res.writeHead(404, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Not a file' }));
      }
      if (info.size > 5 * 1024 * 1024) {
        res.writeHead(413, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'File too large (>5MB)' }));
      }
      const content = await readFile(fsFile, 'utf8').catch(() => null);
      if (content === null) {
        res.writeHead(500, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Cannot read file' }));
      }
      const ext = extname(fsFile).toLowerCase();
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ path: filePath, ext, size: info.size, content }));
    }

    // ── API: search ──
    if (urlPath === '/__api__/search') {
      ctx.render = 'api-search';
      const q = (url.searchParams.get('q') || '').toLowerCase();
      const mode = url.searchParams.get('mode') || 'filename';
      const base = url.searchParams.get('path') || '/';
      // The Dot files toggle governs BOTH showing and searching: on means dot
      // entries are listed AND walked into, off means neither. One switch, one
      // meaning, in the listing and in the search.
      //
      // It looked broken with the toggle ON for a reason unrelated to this
      // flag: the walk was depth-first under a 3s deadline, so turning dot on
      // enlarged the tree while the walk still spent the whole budget down the
      // first branch it entered. Dot results were reachable in principle and
      // never reached in practice. The breadth-first queue below is the fix;
      // this flag was always doing what it said.
      const showDot = url.searchParams.get('dot') === '1';
      if (!q) {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end('[]');
      }
      const fsBase = resolveInRoot(base);
      if (!fsBase) {
        res.writeHead(404, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Not a directory' }));
      }
      const results = [];
      // The only stop condition here used to be `results.length > 200` — a
      // bound on MATCHES, not on WORK. Searches are rooted at ROOT, which is
      // $HOME, so any query matching fewer than 200 things recursed the whole
      // home directory: every git checkout, node_modules, nix profile and
      // waydroid image. Reproduced 2026-08-25: an idle server at 0%, one
      // search for a term matching nothing, client gone after 6s — and the
      // process sat at 90% of a core with ZERO open connections, still
      // walking, because nothing ties the walk to the request that started it.
      // ~5,800 stat() calls/sec, event loop plus all four libuv fs workers
      // saturated. The sidebar debounces on input, so typing a word launches
      // several overlapping walks and they stack: observed 198%.
      //
      // Three bounds, kept separate because they fail in different ways:
      //   budget  — the walk terminates whatever the tree turns out to be
      //   seen    — stat() FOLLOWS symlinks, so one link pointing back up the
      //             tree is an infinite descent no budget would explain
      //   aborted — a walk nobody is waiting for should not still be running
      let budget = 50000;
      const seen = new Set();
      let aborted = false;
      req.on('close', () => { aborted = true; });
      // A WALL-CLOCK deadline, which is the bound that actually matches the
      // promise made to the user: "this will not bog down the machine". budget
      // bounds entries, not seconds — a cold cache on a loaded disk can spend
      // minutes on 50k stat()s, and that is indistinguishable from the runaway
      // this is all meant to prevent. Whichever trips first wins.
      //
      // This is explicitly NOT an index. Nothing is cached, nothing is crawled
      // in the background, nothing persists between requests — that is the
      // whole difference from baloo, which keeps a database warm by rescanning
      // the filesystem forever. A search here costs one bounded walk and then
      // the process goes back to idle at 0% CPU.
      const SEARCH_MS = Number(process.env.HTTPD_SEARCH_MS || 3000);
      const deadline = Date.now() + SEARCH_MS;
      let timedOut = false;
      function spent() {
        if (Date.now() > deadline) { timedOut = true; return true; }
        return false;
      }
      // BREADTH-first, not depth-first. Under a deadline the order is the whole
      // ballgame: depth-first dives into whichever directory it meets first and
      // spends the entire budget down one branch, so a root-scoped search for
      // "konsole" returned a single buried match and stopped — measured, and
      // deterministic, which made it look like the only match rather than the
      // first one reached. Breadth-first spends the same budget on the
      // shallowest paths, which are the ones you meant. Truncation then
      // degrades gracefully instead of arbitrarily.
      async function walk(dir, rel) {
        const queue = [[dir, rel]];
        while (queue.length) {
        if (aborted || spent() || budget <= 0 || results.length > 200) return;
        const [cur, curRel] = queue.shift();
        const names = await readdir(cur).catch(() => []);
        for (const name of names) {
          if (aborted || spent() || budget <= 0) return;
          if (!showDot && name.startsWith('.')) continue;
          budget--;
          const childRel = curRel ? curRel + '/' + name : name;
          const childFs = join(cur, name);
          const s = await stat(childFs).catch(() => null);
          if (!s) continue;
          if (s.isDirectory()) {
            // 'folder' is 'filename' restricted to directories: same match, but
            // the file branch below ignores it entirely, so a folder search
            // still DESCENDS through non-matching dirs and just does not report
            // them. Narrowing the walk instead would stop at the first
            // non-matching parent and miss every nested hit.
            if ((mode === 'filename' || mode === 'folder') && name.toLowerCase().includes(q)) {
              results.push({ path: childRel, isDir: true });
            }
            // Keyed by identity, not by path: two paths that reach the same
            // directory through a symlink are the same directory.
            const key = s.dev + ':' + s.ino;
            if (!seen.has(key)) {
              seen.add(key);
              queue.push([childFs, childRel]);
            }
          } else {
            if (mode === 'filename' && name.toLowerCase().includes(q)) {
              results.push({ path: childRel, isDir: false });
            } else if (mode === 'content') {
              const info = await stat(childFs).catch(() => null);
              if (info && info.size < 1024 * 1024) {
                const txt = await readFile(childFs, 'utf8').catch(() => null);
                if (txt && txt.toLowerCase().includes(q)) {
                  results.push({ path: childRel, isDir: false });
                }
              }
            }
          }
        }
        }
      }
      await walk(fsBase, '');
      // Never let a bounded walk look like an exhaustive one.
      const partial = timedOut || budget <= 0 || results.length > 200;
      // console.log, not log(): this file has logRequest/logParts and no bare
      // log(). The typo only threw when `partial` was true — i.e. only once a
      // search actually hit the budget — so it survived every small-directory
      // test and 500'd the first real search from $HOME.
      if (partial) console.log(`[search] "${q}" (${mode}) stopped early — ${results.length} results, reason: ${timedOut ? SEARCH_MS + 'ms deadline' : budget <= 0 ? 'entry budget' : 'result cap'}`);
      res.writeHead(200, {
        'content-type': 'application/json',
        'x-search-partial': partial ? '1' : '0',
        // Results describe the filesystem at this instant. A cached 304 would
        // show a stale tree that no longer matches what the sidebar lists.
        'cache-control': 'no-store',
      });
      return res.end(JSON.stringify(results));
    }

    // ── API: write file (opt-in, CSRF-guarded) ──
    if (urlPath === '/__api__/write') {
      ctx.render = 'api-write';
      if (!WRITE) {
        res.writeHead(403, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'write mode disabled (start with HTTPD_WRITE=1)' }));
      }
      if (RESOLVED_WRITE_ROOTS.length === 0) {
        res.writeHead(403, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'no write roots configured (set HTTPD_WRITE_ROOTS)' }));
      }
      const guard = checkMutationAllowed(req);
      if (!guard.ok) {
        res.writeHead(guard.status, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: guard.error }));
      }
      let body;
      try {
        body = await readJsonBody(req);
      } catch (e) {
        res.writeHead(e.status || 400, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: e.error || 'bad request' }));
      }
      const hasContent = typeof body.content === 'string';
      const hasB64 = typeof body.b64 === 'string';
      if (typeof body.path !== 'string' || (hasContent === hasB64)) {
        res.writeHead(400, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'body must have "path" and exactly one of "content"/"b64"' }));
      }
      const fsTarget = resolveWritable(body.path);
      if (!fsTarget) {
        res.writeHead(403, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'path is outside the write allowlist (HTTPD_WRITE_ROOTS)' }));
      }
      const buf = hasContent ? Buffer.from(body.content, 'utf8') : Buffer.from(body.b64, 'base64');
      await mkdir(dirname(fsTarget), { recursive: true });
      await writeFile(fsTarget, buf);
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ ok: true, path: body.path, bytes: buf.length }));
    }

    // ── API: git add + commit + push (scoped, never an arbitrary shell) ──
    if (urlPath === '/__api__/git') {
      ctx.render = 'api-git';
      if (!WRITE) {
        res.writeHead(403, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'write mode disabled (start with HTTPD_WRITE=1)' }));
      }
      if (RESOLVED_WRITE_ROOTS.length === 0) {
        res.writeHead(403, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'no write roots configured (set HTTPD_WRITE_ROOTS)' }));
      }
      const guard = checkMutationAllowed(req);
      if (!guard.ok) {
        res.writeHead(guard.status, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: guard.error }));
      }
      let body;
      try {
        body = await readJsonBody(req);
      } catch (e) {
        res.writeHead(e.status || 400, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: e.error || 'bad request' }));
      }
      if (typeof body.repo !== 'string' || typeof body.message !== 'string' ||
          !Array.isArray(body.paths) || body.paths.length === 0 ||
          !body.paths.every((p) => typeof p === 'string')) {
        res.writeHead(400, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'body must have "repo" (string), "message" (string), "paths" (non-empty string array)' }));
      }
      const fsRepo = resolveWritable(body.repo);
      if (!fsRepo) {
        res.writeHead(403, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'path is outside the write allowlist (HTTPD_WRITE_ROOTS)' }));
      }
      const gitDirInfo = await stat(join(fsRepo, '.git')).catch(() => null);
      if (!gitDirInfo) {
        res.writeHead(400, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'not a git repository (no .git)' }));
      }
      try {
        const logParts = [];
        const addResult = await runGit(['add', '-A', '--', ...body.paths], fsRepo);
        logParts.push(addResult.output);
        if (!addResult.ok) {
          res.writeHead(500, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ ok: false, log: logParts.join('\n') }));
        }
        const statusResult = await runGit(['status', '--porcelain', '--', ...body.paths], fsRepo);
        logParts.push(statusResult.output);
        if (!statusResult.ok) {
          res.writeHead(500, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ ok: false, log: logParts.join('\n') }));
        }
        if (!statusResult.output.split('\n').slice(1).some((l) => l.trim())) {
          res.writeHead(200, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ ok: true, skipped: 'nothing to commit' }));
        }
        // Trailing pathspec on commit is REQUIRED: without it git commits the
        // entire staged index, and these worktrees routinely carry unrelated
        // staged work from other in-flight edits.
        const commitResult = await runGit(['commit', '-m', body.message, '--', ...body.paths], fsRepo);
        logParts.push(commitResult.output);
        if (!commitResult.ok) {
          res.writeHead(500, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ ok: false, log: logParts.join('\n') }));
        }
        const pushResult = await runGit(['push'], fsRepo);
        logParts.push(pushResult.output);
        if (!pushResult.ok) {
          res.writeHead(500, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ ok: false, log: logParts.join('\n') }));
        }
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ ok: true, log: logParts.join('\n') }));
      } catch (gitErr) {
        res.writeHead(500, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ ok: false, log: gitErr && gitErr.message ? gitErr.message : String(gitErr) }));
      }
    }

    // Serve lib assets from ~/.local/lib/httpd/
    if (urlPath.startsWith('/__lib__/')) {
      ctx.render = 'lib';
      const libFile = urlPath.slice('/__lib__/'.length);
      // Only allow known lib files (no path traversal). Derived from
      // SEA_ASSETS rather than repeating it: adding an asset used to mean
      // editing three lists (this one, SEA_ASSETS, sea-config.json), and
      // missing this one fails as a 404 at runtime rather than at build.
      if (!SEA_ASSETS.has(libFile)) {
        res.writeHead(404, { 'content-type': 'text/plain' });
        return res.end('Not found');
      }
      const data = await getLibAsset(libFile);
      if (!data) {
        res.writeHead(404, { 'content-type': 'text/plain' });
        return res.end('Lib file not found');
      }
      const ext = extname(libFile).toLowerCase();
      res.writeHead(200, {
        'content-type': guessMime(ext),
        'cache-control': 'public, max-age=86400',
      });
      return res.end(data);
    }

    // /app.<name> -- a short, memorable URL for a web panel that lives inside
    // its own project's dist/, not under the served root's tree. Declared in
    // APPS rather than matched by pattern: a rule that turns any /app.X into a
    // filesystem path is a directory-traversal surface, and these are meant to
    // be a handful of named things, not a namespace.
    const appMatch = urlPath.match(/^\/app\.([a-z0-9-]+)(\/.*)?$/);
    if (appMatch && APPS[appMatch[1]]) {
      ctx.render = 'app';
      const appRoot = resolve(APPS[appMatch[1]]);
      const rel = (appMatch[2] || '/').replace(/^\/+/, '') || 'index.html';
      const target = resolve(appRoot, rel);
      // The same containment check the served root gets: an app root is a
      // smaller box than $HOME, which makes escaping it worth more, not less.
      if (target !== appRoot && !target.startsWith(appRoot + '/')) {
        res.writeHead(403, { 'content-type': 'text/plain' });
        return res.end('Forbidden');
      }
      const data = await readFile(target).catch(() => null);
      if (!data) {
        res.writeHead(404, { 'content-type': 'text/plain' });
        return res.end('Not found');
      }
      res.writeHead(200, {
        // *.js.json is a SCRIPT that assigns a global, not a document to be
        // fetched -- that is the whole point of the extension. Browsers refuse
        // to execute a <script> served as application/json, so name it what it
        // behaves as rather than what it is spelled as.
        'content-type': target.endsWith('.js.json')
          ? 'text/javascript; charset=utf-8'
          : guessMime(extname(target).toLowerCase()),
        // A live panel that is allowed to go stale is a lying panel.
        'cache-control': 'no-store',
      });
      return res.end(data);
    }

    let fsPath = resolveInRoot(urlPath);
    if (!fsPath) {
      ctx.render = 'notfound'; ctx.note = `path outside root: ${urlPath}`;
      res.writeHead(404, { 'content-type': 'text/plain' });
      return res.end('Not found');
    }
    let info = await stat(fsPath).catch(() => null);

    // SvelteKit route paths have no extension (e.g. /scene/demo-day) but
    // prerender to a sibling .html file (scene/demo-day.html) — try that
    // before falling back to the SPA shell.
    if (!info && extname(fsPath) === '') {
      const htmlCandidate = `${fsPath}.html`;
      const htmlInfo = await stat(htmlCandidate).catch(() => null);
      if (htmlInfo) { fsPath = htmlCandidate; info = htmlInfo; }
    }

    if (!info) {
      const fallback = await findAncestorFallback(ROOT, urlPath);
      if (fallback) {
        const html = await readFile(fallback.fallbackPath, 'utf8');
        const rewritten = rewriteFallbackAbsolutePaths(html, fallback.projectUrlDir)
          .replace(/<\/body>/i, `${ERUDA_SCRIPT}</body>`);
        ctx.render = 'spa-fallback'; ctx.note = `200.html @ ${fallback.projectUrlDir}`;
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
        return res.end(rewritten);
      }
      ctx.render = 'notfound'; ctx.note = `path missing: ${fsPath}`;
      res.writeHead(404, { 'content-type': 'text/plain' });
      return res.end('Not found');
    }
    ctx.size = info.size || 0;

    if (info.isDirectory()) {
      // Try index.html first
      const indexPath = join(fsPath, 'index.html');
      const hasIndex = await stat(indexPath).catch(() => null);

      if (hasIndex) {
        ctx.render = 'index'; ctx.note = `${join(urlPath, 'index.html')}`;
        let html = await readFile(indexPath, 'utf8');
        // Directory served without a trailing slash (e.g. /dist instead of
        // /dist/) breaks relative asset paths ("./_app/...") used by static
        // SPA builds (SvelteKit adapter-static, Vite, etc.) — the browser
        // resolves "./" against the parent, not this dir. Inject a <base>
        // instead of a redirect so the URL bar keeps exactly what was typed.
        if (!/<base[\s>]/i.test(html)) {
          const dirUrl = urlPath.endsWith('/') ? urlPath : urlPath + '/';
          html = html.replace(/<head(\s[^>]*)?>/i, (m) => `${m}<base href="${dirUrl}">`);
        }
        html = html.replace(/<\/body>/i, `${ERUDA_SCRIPT}</body>`);
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
        return res.end(html);
      }

      ctx.render = 'browse-dir';
      return serveBrowse(res, urlPath);
    }

    // Serve file
    const ext = extname(fsPath).toLowerCase();
    const mime = guessMime(ext);

    // ?raw query: serve any text file as plain text
    if (url.searchParams.has('raw') && ext !== '.md') {
      const data = await readFile(fsPath, 'utf8').catch(() => null);
      if (data !== null) {
        ctx.render = 'raw'; ctx.note = `ext=${ext} size=${fmtBytes(info.size)}`;
        res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
        return res.end(data);
      }
    }

    const acceptsHtml = (req.headers.accept || '').includes('text/html');
    const wantsRaw = url.searchParams.has('raw');

    // Secrets rendering — KV table with copy buttons
    if ((fsPath.endsWith('.secrets') || fsPath.endsWith('.secrets.json')) && acceptsHtml && !wantsRaw) {
      const content = await readFile(fsPath, 'utf8');
      ctx.render = 'secrets'; ctx.note = `size=${fmtBytes(content.length)}`;
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(structuredPage(urlPath, content, 'secrets'));
    }

    if (ext === '.json' && acceptsHtml && !wantsRaw) {
      const content = await readFile(fsPath, 'utf8');
      ctx.render = 'json-html'; ctx.note = `${fmtBytes(content.length)} of JSON → structured HTML`;
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(structuredPage(urlPath, content, 'json'));
    }

    if ((ext === '.yaml' || ext === '.yml') && acceptsHtml && !wantsRaw) {
      const content = await readFile(fsPath, 'utf8');
      ctx.render = 'yaml-html'; ctx.note = `${fmtBytes(content.length)} of YAML → structured HTML`;
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(structuredPage(urlPath, content, 'yaml'));
    }

    // Markdown rendering
    if (ext === '.md') {
      const mdContent = await readFile(fsPath, 'utf8');
      if (url.searchParams.has('raw')) {
        ctx.render = 'raw-md'; ctx.note = `size=${fmtBytes(mdContent.length)}`;
        res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
        return res.end(mdContent);
      }
      ctx.render = 'md-html'; ctx.note = `${fmtBytes(mdContent.length)} → marked.js`;
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(markdownPage(urlPath, mdContent));
    }

    if (ext === '.html') {
      let html = await readFile(fsPath, 'utf8');
      html = html.replace(/<\/body>/i, `${ERUDA_SCRIPT}</body>`);
      ctx.render = 'html'; ctx.note = 'eruda injected';
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(html);
    }

    const data = await readFile(fsPath);
    const finalMime = mime === 'application/octet-stream'
      ? guessMime(ext, data.toString('utf8', 0, Math.min(data.length, 512)))
      : mime;
    ctx.render = 'static'; ctx.note = `mime=${finalMime} size=${fmtBytes(data.length)}`;
    res.writeHead(200, { 'content-type': finalMime });
    res.end(data);
  } catch (err) {
    ctx.render = 'error'; ctx.note = err && err.message ? err.message : String(err);
    if (VERBOSE) console.error(`${C.red}[error]${C.reset} ${req.url}: ${err && err.stack || err}`);
    res.writeHead(500, { 'content-type': 'text/plain' });
    res.end('Internal error');
  }
});

// ── Startup banner ────────────────────────────────────────────────────────
// Verbose by default. Set HTTPD_VERBOSE=0 to silence per-request logs (banner
// always prints — tells you the server is alive). Tries to surface enough
// context that a developer can debug a startup misconfiguration without
// grep'ing the source.
function startupBanner() {
  const url = `http://127.0.0.1:${PORT}`;
  const lines = [
    '',
    `${C.bold}${C.green}━━━ my-webserver ━━━${C.reset}`,
    `  ${C.dim}url       ${C.reset}${C.cyan}${url}/${C.reset}`,
    `  ${C.dim}root      ${C.reset}${ROOT}`,
    `  ${C.dim}lib_dir   ${C.reset}${LIB_DIR}`,
    `  ${C.dim}pid       ${C.reset}${process.pid}`,
    `  ${C.dim}node      ${C.reset}${process.version}  ${C.dim}(${process.platform}/${process.arch})${C.reset}`,
    `  ${C.dim}verbose   ${C.reset}${VERBOSE ? C.green + 'on' + C.reset + C.dim + ' (HTTPD_VERBOSE=0 to silence)' + C.reset : C.gray + 'off' + C.reset}`,
    `  ${C.dim}write     ${C.reset}${WRITE ? C.green + 'on' + C.reset + C.dim + ' (HTTPD_WRITE=1)' + C.reset : C.gray + 'off' + C.reset + C.dim + ' (set HTTPD_WRITE=1 to enable)' + C.reset}`,
    `  ${C.dim}write_roots${C.reset} ${RESOLVED_WRITE_ROOTS.length ? C.green + RESOLVED_WRITE_ROOTS.join(', ') + C.reset : C.gray + 'none set — writes effectively disabled (set HTTPD_WRITE_ROOTS=dir1:dir2)' + C.reset}`,
    `  ${C.dim}token     ${C.reset}${WRITE_TOKEN.length > 0 ? C.green + `loaded (${WRITE_TOKEN.length} chars)` + C.reset : C.gray + 'MISSING — writes disabled' + C.reset}`,
    `  ${C.dim}firewall  ${C.reset}loopback only ${C.dim}(127.0.0.1, ::1)${C.reset}`,
    `  ${C.dim}max size  ${C.reset}5 MB ${C.dim}(API /__api__/read)${C.reset}`,
    '',
    `  ${C.bold}routes${C.reset}`,
    `    ${C.cyan}/${C.reset}                                  serve files from ${C.dim}\$ROOT${C.reset}`,
    `    ${C.cyan}/<dir>/${C.reset}                            index.html if present, else SPA browse`,
    `    ${C.cyan}/<route-no-ext>${C.reset}                    <route>.html if present (SvelteKit prerender)`,
    `    ${C.cyan}/<unmatched-under-project>${C.reset}         nearest ancestor 200.html (SvelteKit SPA fallback)`,
    `    ${C.cyan}/<file>.{json,yaml,yml,secrets}${C.reset}    structured HTML render`,
    `    ${C.cyan}/<file>.md${C.reset}                         marked.js render`,
    `    ${C.cyan}/<file>?raw${C.reset}                        plain text passthrough`,
    `    ${C.cyan}/__browse__/${C.reset}                       SPA browser`,
    `    ${C.cyan}/__api__/ls?path=...${C.reset}               directory listing JSON`,
    `    ${C.cyan}/__api__/read?path=...${C.reset}             file content JSON (≤5MB)`,
    `    ${C.cyan}/__api__/search?q=...&mode=filename|folder|content&dot=1${C.reset}  recursive search`,
    `    ${C.cyan}/__lib__/{marked.min.js,github-markdown-dark.css,browse.html}${C.reset}`,
    '',
    `  ${C.bold}log columns${C.reset}  ${C.dim}timestamp method status duration size render-mode url${C.reset}`,
    `${C.bold}${C.green}━━━━━━━━━━━━━━━━━━━━━━━━━━━━${C.reset}`,
    '',
  ];
  console.log(lines.join('\n'));
}

server.listen(PORT, '127.0.0.1', () => {
  startupBanner();
});

// A failed listen used to be an UNHANDLED 'error' event: EventEmitter rethrows
// it, so the whole diagnosis was a raw Node stack trace ending in
// "Error: listen EADDRINUSE: address already in use 127.0.0.1:8000" and exit 1.
// Readable enough in a terminal where you can just try another port; much less
// so anywhere the process is started FOR you and its stderr is a log pane —
// which is every non-terminal launcher, and the reason #288 went looking.
//
// The listener also has to exist for a second reason: with none attached, the
// throw happens inside Node's internals AFTER startupBanner has been scheduled,
// so on a slow start the banner can print first and the server reads as alive
// right up until the process is gone.
//
// Exits non-zero in every branch. A local file server that could not take its
// port has nothing useful left to do, and a supervisor must be able to tell
// that from a clean shutdown.
server.on('error', (err) => {
  const hint = {
    EADDRINUSE:
      `port ${PORT} is already taken on 127.0.0.1 — something else is serving ` +
      `there. Start this with a different port: my-webserver <port> [root]`,
    EACCES:
      `not allowed to bind port ${PORT}. Ports below 1024 need privileges; ` +
      `pick one above it: my-webserver <port> [root]`,
    EADDRNOTAVAIL:
      `127.0.0.1 could not be bound — this machine has no usable loopback ` +
      `interface, which is unusual and is not something this server can fix`,
  }[err && err.code];

  console.error('');
  console.error(`${C.bold}${C.red}━━━ my-webserver did not start ━━━${C.reset}`);
  console.error(`  ${C.dim}reason    ${C.reset}${(err && err.code) || 'unknown'}: ${err && err.message}`);
  console.error(`  ${C.dim}wanted    ${C.reset}${C.cyan}http://127.0.0.1:${PORT}/${C.reset}`);
  console.error(`  ${C.dim}root      ${C.reset}${ROOT}`);
  if (hint) console.error(`  ${C.dim}what now  ${C.reset}${hint}`);
  console.error(`${C.bold}${C.red}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${C.reset}`);
  console.error('');
  process.exit(1);
});

// Graceful-shutdown summary (Ctrl-C friendly)
let totalRequests = 0;
server.on('request', () => { totalRequests++; });
function shutdown(sig) {
  console.log(`\n${C.dim}[${new Date().toISOString().slice(11,19)}]${C.reset} ${C.yellow}${sig} received${C.reset} — handled ${C.bold}${totalRequests}${C.reset} request(s); shutting down`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
