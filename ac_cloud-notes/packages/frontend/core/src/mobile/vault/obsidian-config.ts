// READ-ONLY reader of <vault>/.obsidian. Nothing here applies a setting (that is the next
// milestone): it parses what the folder carries and tags every plugin with the status the
// mapping in build.json::vault.plugins gives it, so Configs > Vault can show "supported
// natively?" per plugin.
import type { PluginRow, VaultDecl } from './decl-types';

export interface ConfigReader {
  /** null when the file does not exist */
  tryReadText(path: string): Promise<string | null>;
  /** entry names of one folder level; [] when it does not exist */
  listNames(dir: string): Promise<{ name: string; isDir: boolean; size: number }[]>;
}

export type PluginStatusOrUnmapped = PluginRow['status'] | 'unmapped';

export interface ConfigFileReport {
  id: string;
  file: string;
  label: string;
  present: boolean;
  /** set when the file exists but is not valid JSON */
  parseError?: string;
  data?: unknown;
}

export interface PluginReport {
  id: string;
  kind: 'core' | 'community';
  enabled: boolean;
  status: PluginStatusOrUnmapped;
  feature: string;
  /** community: the plugin folder (with a manifest) is in the vault */
  installed?: boolean;
  name?: string;
  version?: string;
}

export interface HotkeyReport {
  command: string;
  keys: string[];
}

export interface SnippetReport {
  name: string;
  enabled: boolean;
  size: number;
}

export interface VaultConfigReport {
  /** true when the folder has a .obsidian directory with at least one readable entry */
  found: boolean;
  files: ConfigFileReport[];
  corePlugins: PluginReport[];
  communityPlugins: PluginReport[];
  hotkeys: HotkeyReport[];
  snippets: SnippetReport[];
  /** a short summary of app.json / appearance.json worth showing in the list */
  summary: { label: string; value: string }[];
}

export function statusOf(
  decl: Pick<VaultDecl, 'plugins'>,
  id: string,
  kind: PluginReport['kind']
): Pick<PluginReport, 'status' | 'feature'> {
  const row = decl.plugins.find(p => p.id === id && p.kind === kind) ?? decl.plugins.find(p => p.id === id);
  return row ? { status: row.status, feature: row.feature } : { status: 'unmapped', feature: 'Not in the plugin mapping yet' };
}

const asRecord = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};

/** `core-plugins.json` is `{id: bool}` in current Obsidian and `[id, ...]` (enabled only) in old vaults. */
export function enabledMap(data: unknown): Record<string, boolean> {
  if (Array.isArray(data)) return Object.fromEntries(data.filter(x => typeof x === 'string').map(x => [x, true]));
  const out: Record<string, boolean> = {};
  for (const [k, v] of Object.entries(asRecord(data))) out[k] = v === true;
  return out;
}

export function parseHotkeys(data: unknown): HotkeyReport[] {
  return Object.entries(asRecord(data)).map(([command, defs]) => ({
    command,
    keys: (Array.isArray(defs) ? defs : [])
      .map(d => {
        const o = asRecord(d);
        const mods = Array.isArray(o.modifiers) ? o.modifiers.map(String) : [];
        return [...mods, String(o.key ?? '')].filter(Boolean).join('+');
      })
      .filter(Boolean),
  }));
}

export async function readObsidianConfig(
  reader: ConfigReader,
  decl: Pick<VaultDecl, 'config_dir' | 'config_files' | 'config_dirs' | 'plugins'>
): Promise<VaultConfigReport> {
  const dir = decl.config_dir;
  const top = await reader.listNames(dir);
  const files: ConfigFileReport[] = [];
  for (const cf of decl.config_files) {
    const text = await reader.tryReadText(`${dir}/${cf.file}`);
    if (text === null) {
      files.push({ ...cf, present: false });
      continue;
    }
    try {
      files.push({ ...cf, present: true, data: JSON.parse(text) });
    } catch (e) {
      files.push({ ...cf, present: true, parseError: e instanceof Error ? e.message : String(e) });
    }
  }
  const dataOf = (id: string) => files.find(f => f.id === id)?.data;

  const corePlugins = Object.entries(enabledMap(dataOf('core-plugins'))).map(([id, enabled]) => ({
    id,
    kind: 'core' as const,
    enabled,
    ...statusOf(decl, id, 'core'),
  }));

  const pluginsDir = decl.config_dirs.find(d => d.id === 'plugins');
  const installed = new Map<string, { name?: string; version?: string }>();
  if (pluginsDir) {
    for (const e of await reader.listNames(`${dir}/${pluginsDir.dir}`)) {
      if (!e.isDir) continue;
      const m = await reader.tryReadText(`${dir}/${pluginsDir.dir}/${e.name}/${pluginsDir.manifest ?? 'manifest.json'}`);
      let info: { name?: string; version?: string } = {};
      if (m) {
        try {
          const o = asRecord(JSON.parse(m));
          info = { name: typeof o.name === 'string' ? o.name : undefined, version: typeof o.version === 'string' ? o.version : undefined };
        } catch {
          /* a broken manifest still counts as an installed folder */
        }
      }
      installed.set(e.name, info);
    }
  }
  const communityIds = Array.isArray(dataOf('community-plugins'))
    ? (dataOf('community-plugins') as unknown[]).filter((x): x is string => typeof x === 'string')
    : [];
  const communityPlugins = communityIds.map(id => ({
    id,
    kind: 'community' as const,
    enabled: true,
    ...statusOf(decl, id, 'community'),
    installed: installed.has(id),
    ...installed.get(id),
  }));

  const snippetsDir = decl.config_dirs.find(d => d.id === 'snippets');
  const enabledSnippets = new Set(
    Array.isArray(asRecord(dataOf('appearance')).enabledCssSnippets)
      ? (asRecord(dataOf('appearance')).enabledCssSnippets as unknown[]).map(String)
      : []
  );
  const snippets = snippetsDir
    ? (await reader.listNames(`${dir}/${snippetsDir.dir}`))
        .filter(e => !e.isDir && e.name.endsWith(snippetsDir.ext ?? '.css'))
        .map(e => {
          const name = e.name.slice(0, e.name.length - (snippetsDir.ext ?? '.css').length);
          return { name, enabled: enabledSnippets.has(name), size: e.size };
        })
    : [];

  const app = asRecord(dataOf('app'));
  const appearance = asRecord(dataOf('appearance'));
  const summary: { label: string; value: string }[] = [];
  const pick = (label: string, v: unknown) => {
    if (v !== undefined && v !== '') summary.push({ label, value: String(v) });
  };
  pick('Default view mode', app.defaultViewMode);
  pick('Live preview', app.livePreview);
  pick('New file location', app.newFileLocation);
  pick('New file folder', app.newFileFolderPath);
  pick('Attachment folder', app.attachmentFolderPath);
  pick('Link format', app.newLinkFormat);
  pick('Wikilinks', app.useMarkdownLinks === undefined ? undefined : !app.useMarkdownLinks);
  pick('Theme', appearance.theme);
  pick('Base font size', appearance.baseFontSize);

  return {
    found: top.length > 0,
    files,
    corePlugins,
    communityPlugins,
    hotkeys: parseHotkeys(dataOf('hotkeys')),
    snippets,
    summary,
  };
}
