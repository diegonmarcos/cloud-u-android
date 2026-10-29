#!/usr/bin/env node
// readme-render.js - the ONE README renderer for every repo (#1303).
// SOURCE: _git-repos-master/readme-render.js. Every repo carries a byte copy as _dotfiles/readme/readme-render.cjs (written by
// `node repos-sot.js sync-readmes`); DO NOT EDIT the copies.
//
// A repo's README.md = template (_dotfiles/readme/template.md.tpl, hub or repo variant)
//                    + node.json  (its registry node, rendered from projects-repos.json; DO NOT EDIT)
//                    + readme.json (optional, hand-owned: title, intro, sections[] of repo-specific prose).
//
//   node _dotfiles/readme/readme-render.cjs [repo-root]           write README.md
//   node _dotfiles/readme/readme-render.cjs [repo-root] --check   exit 1 when README.md differs byte-for-byte
// Library (used by _git-repos-master repos-sot.js): nodeData(x, name), render(node, readme, tpl), migrate(md, kind).
'use strict';
const fs = require('fs');
const path = require('path');

const KIT = '_dotfiles/readme';
const HUB_SECTIONS = new Set(['Members', 'Usage', 'Clone depth', 'Hub submodule']); // the hub template owns these
const HUB_BASE = { 'lecole-42-___': 'cloud-shared-store/git' };

// ---- registry node -> node.json (only _git-repos-master calls this) ----
function treeLines(entries, depth) {
  const out = [];
  const sorted = es => [...es].filter(e => e.type === 'dir' || e.type === 'submodule')
    .sort((a, b) => a.name.localeCompare(b.name, 'en'));
  (function walk(es, pre, d) {
    const list = sorted(es);
    list.forEach((e, i) => {
      const last = i === list.length - 1;
      out.push(pre + (last ? '└── ' : '├── ') + e.name + (e.type === 'submodule' ? ' (submodule)' : '/'));
      if (d < depth && e.children) walk(e.children, pre + (last ? '    ' : '│   '), d + 1);
    });
  })(entries || [], '', 1);
  return out;
}
function nodeData(x, name) {
  const r = x.byName.get(name);
  if (!r) throw new Error(`${name}: not in the registry`);
  const byCode = new Map(x.nodes.map(n => [n.code, n]));
  const crumbs = (r.path || []).map(c => byCode.get(c)).filter(Boolean)
    .map(n => ({ code: n.code, kind: n.kind, label: n.label || n.name, icon: n.icon || '' }));
  const own = r.hub_of ? byCode.get(r.hub_of) : null;
  const up = r.up ? x.byName.get(r.up) : null;
  const isHub = r.kind === 'hub';
  return {
    schema: 'readme-node/v1',
    generated_by: '_git-repos-master readme-render.js nodeData() from projects-repos.json; DO NOT EDIT (node repos-sot.js sync-readmes)',
    name: r.name, label: r.label || r.name, code: r.code, kind: isHub ? 'hub' : 'repo',
    description: r.description || (own && own.description) || '',
    visibility: r.visibility, url: r.url, developer: r.developer || '', farm: r.farm || '',
    breadcrumb: crumbs,
    hub: up ? { name: up.name, code: up.code, url: up.url } : null,
    base_dir: HUB_BASE[r.name] || 'git',
    members: isHub ? x.repos.filter(m => m.up === r.name && m.status !== 'pending_creation')
      .map(m => ({ code: m.code, name: m.name, url: m.url, visibility: m.visibility, kind: m.kind, description: m.description || '' })) : [],
    tree: r.visibility === 'private' ? null : treeLines(r.tree && r.tree.entries, 3),
  };
}

// ---- existing README.md -> readme.json (one-time migration; lossless split on "## ") ----
function migrate(md, kind) {
  const lines = md.replace(/\r\n/g, '\n').split('\n');
  let title = null, i = 0;
  while (i < lines.length && !lines[i].trim()) i++;
  if (i < lines.length && /^# /.test(lines[i])) { title = lines[i].slice(2).trim(); i++; }
  const secs = []; let cur = { title: null, body: [] }; let fence = false;
  for (; i < lines.length; i++) {
    const l = lines[i];
    if (/^(```|~~~)/.test(l)) fence = !fence;
    if (!fence && /^## /.test(l)) { secs.push(cur); cur = { title: l.slice(3).trim(), body: [] }; } else cur.body.push(l);
  }
  secs.push(cur);
  const trim = a => a.join('\n').replace(/^\s*\n/, '').replace(/\s+$/, '');
  const out = { title };
  const intro = trim(secs[0].body); if (intro) out.intro = intro;
  const dropped = [];
  out.sections = secs.slice(1).filter(s => {
    if (kind === 'hub' && HUB_SECTIONS.has(s.title)) { dropped.push(s.title); return false; }
    return true;
  }).map(s => ({ title: s.title, body: trim(s.body) }));
  if (!out.title) delete out.title;
  return { readme: out, dropped };
}

// ---- render ----
const esc = s => String(s).replace(/\|/g, '\\|').replace(/\n/g, ' ');
const lock = v => v === 'private' ? '🔒 private' : '🌐 public';
function blocks(n, rd) {
  const crumb = n.breadcrumb.map(c => `${c.icon ? c.icon + ' ' : ''}${c.label} \`${c.code}\``).join(' › ');
  const sections = (rd.sections || []).map(s => `## ${s.title}\n\n${s.body}`.replace(/\s+$/, '')).join('\n\n');
  const members = n.members.map(m => `| \`${m.code}\` | [${m.name}](${m.url})${m.kind === 'hub' ? ' (hub)' : ''} | ${lock(m.visibility)} | ${esc(m.description)} |`).join('\n');
  return {
    title: rd.title || n.label, name: n.name, code: n.code, url: n.url, visibility: lock(n.visibility),
    developer: n.developer || '—', farm: n.farm ? `\`${n.farm}/\`` : '—', base_dir: n.base_dir,
    breadcrumb: crumb || '—', description: n.description, intro: rd.intro || '', sections,
    hub: n.hub ? `[${n.hub.name}](${n.hub.url}) \`${n.hub.code}\`` : '—',
    hub_name: n.hub ? n.hub.name : '', hub_url: n.hub ? n.hub.url : '',
    members, member_count: String(n.members.length),
    tree: n.tree ? (n.tree.length ? '```\n' + n.name + '/\n' + n.tree.join('\n') + '\n```' : '_No folders._')
      : '_Private repository: its folder tree is not published in the registry._',
  };
}
// template: {{key}} substitutes; {{#key}}...{{/key}} keeps the block only when key is non-empty
function render(node, readme, tpl) {
  const b = blocks(node, readme || {});
  // a template line that is only an empty placeholder disappears together with the blank line after it
  const lines = tpl.split('\n'), kept = [];
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^\{\{#?(\w+)\}\}(?:.*\{\{\/\1\}\})?$/);
    if (m && !b[m[1]]) { if (lines[i + 1] === '') i++; continue; }
    kept.push(lines[i]);
  }
  let s = kept.join('\n').replace(/\{\{#(\w+)\}\}([\s\S]*?)\{\{\/\1\}\}/g, (_, k, body) => (b[k] ? body : ''));
  s = s.replace(/\{\{(\w+)\}\}/g, (m, k) => (k in b ? b[k] : m));
  return s.replace(/\s*$/, '\n');
}
function renderRepo(root) {
  const kit = path.join(root, KIT);
  const rd = fs.existsSync(path.join(kit, 'readme.json')) ? JSON.parse(fs.readFileSync(path.join(kit, 'readme.json'), 'utf8')) : {};
  return render(JSON.parse(fs.readFileSync(path.join(kit, 'node.json'), 'utf8')), rd, fs.readFileSync(path.join(kit, 'template.md.tpl'), 'utf8'));
}

module.exports = { nodeData, render, renderRepo, migrate, treeLines, KIT, HUB_SECTIONS };

if (require.main === module) {
  const args = process.argv.slice(2), check = args.includes('--check');
  const root = path.resolve(args.find(a => a !== '--check') || path.join(__dirname, '..', '..'));
  const want = renderRepo(root), f = path.join(root, 'README.md');
  const have = fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null;
  if (check) {
    if (have !== want) { console.error(`README.md differs from the render of ${KIT}/ (edit ${KIT}/readme.json, then ${KIT}/render-readme.sh)`); process.exit(1); }
    console.log('README.md: ok (byte-equal to the render)');
  } else if (have !== want) { fs.writeFileSync(f, want); console.log(`wrote ${f}`); }
}
