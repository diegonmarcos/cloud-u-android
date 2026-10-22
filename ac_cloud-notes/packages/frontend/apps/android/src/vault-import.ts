// Phase 1 (cloud-notes-hybrid): import a whole Obsidian vault / Markor folder.
//
// ExternalFilePlugin lists one folder level and reads one text file at a time;
// Cloud Notes's Obsidian importer resolves [[wikilinks]] only across the files of ONE
// call (it builds a title/path lookup over them). So the folder is walked here,
// every note is read, and the notes go to the importer together, each File
// carrying the vault-relative `webkitRelativePath` the importer keys on. The
// walk takes its file system as an argument: the spec drives it with a fake,
// the app with the Capacitor plugin.
import type {
  ExternalBinaryReadResult,
  ExternalDirListing,
  ExternalFileReadResult,
} from './plugins/external-file';

export interface VaultFS {
  listDir(options: { path: string }): Promise<ExternalDirListing>;
  readFile(options: { path: string }): Promise<ExternalFileReadResult>;
  /** with these two the walk also brings attachments (images, pdf, ...) */
  listFiles?(options: { path: string }): Promise<ExternalDirListing>;
  readBinary?(options: { path: string }): Promise<ExternalBinaryReadResult>;
}

export interface VaultLimits {
  maxNotes: number;
  maxDepth: number;
  maxAttachments: number;
  /** total attachment bytes read, so a photo-heavy vault cannot exhaust the WebView */
  maxAttachmentBytes: number;
}

export const VAULT_LIMITS: VaultLimits = {
  maxNotes: 5000,
  maxDepth: 12,
  maxAttachments: 2000,
  maxAttachmentBytes: 150 * 1024 * 1024,
};

export interface CollectedAttachment {
  /** vault-relative, '/'-separated, includes the file name */
  path: string;
  bytes: Uint8Array;
}

export interface CollectedNote {
  /** vault-relative, '/'-separated, includes the file name */
  path: string;
  content: string;
}

export interface VaultCollection {
  notes: CollectedNote[];
  attachments: CollectedAttachment[];
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
  const out: VaultCollection = { notes: [], attachments: [], skipped: [], truncated: false };
  let attachmentBytes = 0;
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
        // non-notes the plain listing offers (json, txt, csv...) are picked up below with the
        // attachments when the file system can read bytes; otherwise they are reported
        if (!fs.listFiles || !fs.readBinary) {
          out.skipped.push({ path: rel, reason: 'not a note (this file system reads text only)' });
        }
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
    if (fs.listFiles && fs.readBinary) {
      await collectAttachments(fs, dir, base, limits, out, bytes => {
        attachmentBytes += bytes;
        return attachmentBytes;
      });
    }
  }
  out.notes.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return out;
}

async function collectAttachments(
  fs: VaultFS,
  dir: string,
  base: string,
  limits: VaultLimits,
  out: VaultCollection,
  addBytes: (n: number) => number
) {
  let listing: ExternalDirListing;
  try {
    listing = await fs.listFiles!({ path: dir });
  } catch (err) {
    out.skipped.push({ path: dir.slice(base.length), reason: err instanceof Error ? err.message : 'folder could not be listed' });
    return;
  }
  for (const entry of listing.entries) {
    if (entry.isDir || NOTE.test(entry.name) || isHidden(entry.name)) continue;
    const rel = dir.slice(base.length) + entry.name;
    if (out.attachments.length >= limits.maxAttachments) {
      out.skipped.push({ path: rel, reason: 'over the attachment limit' });
      out.truncated = true;
      continue;
    }
    if (entry.size > 10 * 1024 * 1024) {
      out.skipped.push({ path: rel, reason: 'larger than the 10 MiB per-file limit' });
      continue;
    }
    if (addBytes(0) + entry.size > limits.maxAttachmentBytes) {
      out.skipped.push({ path: rel, reason: 'over the total attachment size limit' });
      out.truncated = true;
      continue;
    }
    try {
      const f = await fs.readBinary!({ path: `${dir}${entry.name}` });
      addBytes(f.size);
      out.attachments.push({ path: rel, bytes: base64ToBytes(f.data) });
    } catch (err) {
      out.skipped.push({ path: rel, reason: err instanceof Error ? err.message : 'could not be read' });
    }
  }
}

function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** The Files Cloud Notes's importer takes: basename as name, vault-relative path as webkitRelativePath. */
export function toVaultFiles(
  notes: CollectedNote[],
  vaultName: string,
  attachments: CollectedAttachment[] = []
): File[] {
  const withPath = (file: File, path: string) => {
    Object.defineProperty(file, 'webkitRelativePath', {
      value: `${vaultName}/${path}`,
    });
    return file;
  };
  return [
    ...notes.map(n =>
      withPath(
        new File([n.content], n.path.split('/').pop() ?? n.path, { type: 'text/markdown' }),
        n.path
      )
    ),
    ...attachments.map(a =>
      withPath(new File([a.bytes as BlobPart], a.path.split('/').pop() ?? a.path), a.path)
    ),
  ];
}

/** The folder's own name, for the importer's vault root. */
export function vaultNameOf(root: string): string {
  const parts = root.split('/').filter(Boolean);
  return parts[parts.length - 1] ?? 'vault';
}
