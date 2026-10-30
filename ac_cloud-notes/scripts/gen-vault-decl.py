#!/usr/bin/env python3
"""Generate the web mirror of build.json::vault and the plugin mapping table.

Source of truth: ac_cloud-notes/build.json::vault. Outputs (never edit them):
  packages/frontend/core/src/mobile/vault/vault-decl.json   read by the Vault view
  docs/vault-plugin-mapping.md                              the Obsidian plugin mapping table
Usage: gen-vault-decl.py [--check]   (--check exits 1 when an output differs; test/test-vault.sh uses it)
"""
import json, os, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
DECL_OUT = 'packages/frontend/core/src/mobile/vault/vault-decl.json'
DOC_OUT = 'docs/vault-plugin-mapping.md'
LABEL = {'builtin': 'built-in', 'planned': 'planned', 'na': 'not applicable'}


def render():
    vault = json.load(open(os.path.join(ROOT, 'build.json'), encoding='utf-8'))['vault']
    decl = {'_generated': 'DO NOT EDIT: generated from build.json::vault by scripts/gen-vault-decl.py', **vault}
    rows = ['<!-- DO NOT EDIT: generated from build.json::vault by scripts/gen-vault-decl.py -->',
            '# Obsidian plugin mapping (Cloud Notes vault view)', '',
            vault['plugins_doc'], '']
    for kind, title in (('core', 'Core plugins'), ('community', 'Community plugins')):
        rows += [f'## {title}', '', '| Obsidian plugin | Status | Cloud Notes feature |', '|---|---|---|']
        for p in vault['plugins']:
            if p['kind'] == kind:
                rows.append(f"| `{p['id']}` | {LABEL[p['status']]} | {p['feature']} |")
        rows.append('')
    counts = {s: sum(1 for p in vault['plugins'] if p['status'] == s) for s in LABEL}
    rows.append('Totals: ' + ', '.join(f'{LABEL[s]} {n}' for s, n in counts.items()) + '.')
    return {DECL_OUT: json.dumps(decl, indent=2) + '\n', DOC_OUT: '\n'.join(rows) + '\n'}


if __name__ == '__main__':
    out = render()
    bad = 0
    for rel, text in out.items():
        path = os.path.join(ROOT, rel)
        have = open(path, encoding='utf-8').read() if os.path.exists(path) else None
        if '--check' in sys.argv:
            if have != text:
                print('DRIFT: ' + rel + ' differs from build.json::vault (run scripts/gen-vault-decl.py)')
                bad = 1
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, 'w', encoding='utf-8').write(text)
    sys.exit(bad)
