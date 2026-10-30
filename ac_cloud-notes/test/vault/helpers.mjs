import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
export const APP = resolve(here, '..', '..');
export const SRC = join(APP, 'packages/frontend/core/src/mobile/vault');
export const FIX = join(here, '..', 'fixtures', 'vault');
export const decl = JSON.parse(readFileSync(join(SRC, 'vault-decl.json'), 'utf8'));
export const src = name => import(join(SRC, name));
export const fixture = (...p) => readFileSync(join(FIX, ...p), 'utf8');

/** ConfigReader over a real folder (what the app does over the native plugin). */
export const folderReader = root => ({
  async tryReadText(p) {
    const f = join(root, p);
    return existsSync(f) && statSync(f).isFile() ? readFileSync(f, 'utf8') : null;
  },
  async listNames(d) {
    const f = join(root, d);
    if (!existsSync(f)) return [];
    return readdirSync(f, { withFileTypes: true }).map(e => ({
      name: e.name,
      isDir: e.isDirectory(),
      size: e.isFile() ? statSync(join(f, e.name)).size : 0,
    }));
  },
});

export function walk(root, dir = '') {
  const out = [];
  for (const e of readdirSync(join(root, dir), { withFileTypes: true })) {
    const p = dir ? `${dir}/${e.name}` : e.name;
    if (e.isDirectory()) out.push(...walk(root, p));
    else out.push(p);
  }
  return out;
}
