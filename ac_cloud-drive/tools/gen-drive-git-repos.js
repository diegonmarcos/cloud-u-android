#!/usr/bin/env node
// #1073 data/drive-git-repos.json is GENERATED from
//   data/drive-git-family.json  (the drive-owned overlay: which repos, label, seed, notes)
//   data/repos.sot.json         (byte copy of git-repos-master/repos.json: identity, visibility, pending, renames)
//
//   node tools/gen-drive-git-repos.js          write data/drive-git-repos.json
//   node tools/gen-drive-git-repos.js --check  exit 1 (and say why) if the file on disk differs
//
// Pure node, no dependencies; test/test-drive-repos-sot.sh runs --check.
'use strict';
const fs = require('fs');
const path = require('path');
const data = path.join(__dirname, '..', 'data');
const rd = f => JSON.parse(fs.readFileSync(path.join(data, f), 'utf8'));
const ghName = url => url.replace(/^.*[/:]/, '').replace(/\.git$/, '');
const ghOwner = url => (url.match(/[:/]([^/:]+)\/[^/]+$/) || [])[1] || '';

// arrays of plain strings stay on one line (["a", "b"]), as the hand-written file had them
const inline = text => text.replace(/\[\n\s+("(?:[^"\\]|\\.)*"(?:,\n\s+"(?:[^"\\]|\\.)*")*)\n\s*\]/g, (_, body) => '[' + body.split(/,\n\s+/).join(', ') + ']');

function generate() {
  const sot = rd('repos.sot.json'), fam = rd('drive-git-family.json');
  const byGh = new Map(sot.repos.map(r => [ghName(r.url), r]));
  const gitea = (fam.instances || []).find(i => i.kind === 'mirror');
  const giteaOwner = gitea ? gitea.org : '';
  const out = {};
  for (const [k, v] of Object.entries(fam)) {
    if (k !== 'family') { out[k] = v; continue; }
    out.repos = fam.family.map(f => {
      const s = byGh.get(f.name);
      if (!s) throw new Error(`${f.name}: not in the repo registry (repos.sot.json) - add it there, not here`);
      if (s.status === 'pending_creation') throw new Error(`${f.name}: pending_creation in the registry - drop it from drive-git-family.json until the repo exists`);
      if (f.seed && s.private) throw new Error(`${f.name}: private in the registry but seed:true (an anonymous clone of a private repo always fails)`);
      const renamed = [...(s.renamed_from || [])];
      if (s.name !== f.name && !renamed.includes(s.name)) renamed.push(s.name);
      const r = { name: f.name, label: f.label || f.name, github_owner: ghOwner(s.url), gitea_owner: giteaOwner };
      if (s.private) r.private = true; else r.public = true;
      if (f.seed) r.seed = true;
      if (f.notes) r.notes = f.notes;
      if (renamed.length) r.renamed_from = renamed;
      return r;
    });
  }
  return inline(JSON.stringify(out, null, 1)) + '\n';
}

try {
  const want = generate(), file = path.join(data, 'drive-git-repos.json');
  if (process.argv.includes('--check')) {
    const have = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
    if (have !== want) { console.error('data/drive-git-repos.json is out of date with drive-git-family.json + repos.sot.json: run node tools/gen-drive-git-repos.js'); process.exit(1); }
    console.log('drive-git-repos.json: up to date');
  } else { fs.writeFileSync(file, want); console.log('wrote data/drive-git-repos.json'); }
} catch (e) { console.error('gen-drive-git-repos: ' + e.message); process.exit(1); }
