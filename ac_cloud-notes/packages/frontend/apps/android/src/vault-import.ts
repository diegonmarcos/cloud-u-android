// Phase 1 (cloud-notes-hybrid): import a whole Obsidian vault / Markor folder.
//
// ExternalFilePlugin lists one folder level and reads one text file at a time;
// AFFiNE's Obsidian importer resolves [[wikilinks]] only across the files of ONE
// call (it builds a title/path lookup over them). So the folder is walked here,
// every note is read, and the notes go to the importer together, each File
// carrying the vault-relative `webkitRelativePath` the importer keys on. The
// walk takes its file system as an argument: the spec drives it with a fake,
// the app with the Capacitor plugin.
import type {
  ExternalDirListing,
  ExternalFileReadResult,
} from './plugins/external-file';

export interface VaultFS {
  listDir(options: { path: string }): Promise<ExternalDirListing>;
  readFile(options: { path: string }): Promise<ExternalFileReadResult>;
}

export interface VaultLimits {
  maxNotes: number;
  maxDepth: number;
}

export const VAULT_LIMITS: VaultLimits = { maxNotes: 5000, maxDepth: 12 };

export interface CollectedNote {
  /** vault-relative, '/'-separated, includes the file name */
  path: string;
  content: string;
}

export interface VaultCollection {
  notes: CollectedNote[];
  /** every entry that was not imported, with the reason: nothing is dropped silently */
  skipped: { path: string; reason: string }[];
  /** true when a limit stopped the walk early */
  truncated: boolean;
}

const NOTE = /\.(md|markdown)$/i;
// Obsidian's own state and trash are not notes (the native listing hides dot
// entries already; this keeps the rule true for any other file system).
const isHidden = (name: string) => name.startsWith('.');

export async function collectVault(
  fs: VaultFS,
  root: string,
  limits: VaultLimits = VAULT_LIMITS
): Promise<VaultCollection> {
  const base = root.endsWith('/') ? root : `${root}/`;
  const out: VaultCollection = { notes: [], skipped: [], truncated: false };
  const queue: { dir: string; depth: number }[] = [{ dir: base, depth: 0 }];

  while (queue.length > 0) {
    const { dir, depth } = queue.shift()!;
    let listing: ExternalDirListing;
    try {
      listing = await fs.listDir({ path: dir });
    } catch (err) {
      out.skipped.push({
        path: dir.slice(base.length),
        reason: err instanceof Error ? err.message : 'folder could not be listed',
      });
      continue;
    }
    for (const entry of listing.entries) {
      const rel = dir.slice(base.length) + entry.name;
      if (isHidden(entry.name)) {
        out.skipped.push({ path: rel, reason: 'hidden' });
      } else if (entry.isDir) {
        if (depth + 1 > limits.maxDepth) {
          out.skipped.push({ path: rel, reason: 'deeper than the depth limit' });
          out.truncated = true;
        } else {
          queue.push({ dir: `${dir}${entry.name}/`, depth: depth + 1 });
        }
      } else if (!NOTE.test(entry.name)) {
        out.skipped.push({ path: rel, reason: 'not a note (text-only import)' });
      } else if (out.notes.length >= limits.maxNotes) {
        out.skipped.push({ path: rel, reason: 'over the note limit' });
        out.truncated = true;
      } else {
        try {
          const file = await fs.readFile({ path: `${dir}${entry.name}` });
          out.notes.push({ path: rel, content: file.content });
        } catch (err) {
          out.skipped.push({
            path: rel,
            reason: err instanceof Error ? err.message : 'could not be read',
          });
        }
      }
    }
  }
  out.notes.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return out;
}

/** The Files AFFiNE's importer takes: basename as name, vault-relative path as webkitRelativePath. */
export function toVaultFiles(notes: CollectedNote[], vaultName: string): File[] {
  return notes.map(n => {
    const file = new File([n.content], n.path.split('/').pop() ?? n.path, {
      type: 'text/markdown',
    });
    Object.defineProperty(file, 'webkitRelativePath', {
      value: `${vaultName}/${n.path}`,
    });
    return file;
  });
}

/** The folder's own name, for the importer's vault root. */
export function vaultNameOf(root: string): string {
  const parts = root.split('/').filter(Boolean);
  return parts[parts.length - 1] ?? 'vault';
}
