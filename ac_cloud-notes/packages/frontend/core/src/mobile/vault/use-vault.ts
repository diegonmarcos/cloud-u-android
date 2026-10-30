// All state of the open vault: the file system adapter, the tree, tabs and their buffers,
// the index (built off the UI thread), the recent list and the read-only .obsidian report.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import type { VaultDecl } from './decl-types';
import { baseName, handlerFor, isTextHandler, stem } from './file-types';
import { runIndex } from './index-runner';
import { vaultNative } from './native-host';
import { readObsidianConfig, type VaultConfigReport } from './obsidian-config';
import {
  forgetRecent,
  type KeyValueStore,
  readRecent,
  type RecentVault,
  touchRecent,
} from './recent-vaults';
import { INDEXED_EXT, type VaultIndex } from './vault-index';
import {
  nativeVaultFS,
  parentOf,
  type VaultEntry,
  type VaultFS,
  walkVault,
} from './vault-fs';

export interface Tab {
  path: string;
  /** one of the handler's views */
  view: string;
  text: string | null;
  dirty: boolean;
  error: string | null;
  /** HTML pages: the CDN toggle, per tab, off by default */
  cdn: boolean;
}

export interface OpenVault {
  root: string;
  name: string;
  fs: VaultFS;
}

const errText = (e: unknown) =>
  e instanceof Error ? e.message : typeof e === 'object' && e !== null && 'message' in e ? String((e as { message: unknown }).message) : String(e);

export function useVault(decl: VaultDecl, storage: KeyValueStore) {
  const native = vaultNative();
  const [vault, setVault] = useState<OpenVault | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recent, setRecent] = useState<RecentVault[]>(() => readRecent(storage, decl.open.storage_key));
  const [persisted, setPersisted] = useState<{ root: string; name: string }[]>([]);
  const [showHidden, setShowHidden] = useState(false);
  const [dirs, setDirs] = useState<Record<string, VaultEntry[]>>({});
  const [files, setFiles] = useState<VaultEntry[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [index, setIndex] = useState<VaultIndex | null>(null);
  const [indexing, setIndexing] = useState<{ parsed: number; total: number } | null>(null);
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [config, setConfig] = useState<VaultConfigReport | null>(null);
  const texts = useRef(new Map<string, string>());
  const tabsRef = useRef<Tab[]>([]);
  tabsRef.current = tabs;
  const gen = useRef(0);

  useEffect(() => {
    if (!native) return;
    native.persisted().then(r => setPersisted(r.vaults)).catch(() => setPersisted([]));
  }, [native]);

  const loadDir = useCallback(
    async (fs: VaultFS, dir: string) => {
      const list = await fs.list(dir);
      setDirs(d => ({ ...d, [dir]: list }));
      return list;
    },
    []
  );

  const reindex = useCallback(
    async (fs: VaultFS, hidden: boolean, myGen: number) => {
      const walked = await walkVault(fs, decl.limits, { hidden });
      if (gen.current !== myGen) return;
      setFiles(walked.files);
      setTruncated(walked.truncated);
      const notes = walked.files.filter(f => INDEXED_EXT.includes(f.name.slice(f.name.lastIndexOf('.') + 1).toLowerCase()));
      const chunk = decl.limits.index_chunk;
      const map = new Map<string, string>();
      setIndexing({ parsed: 0, total: notes.length });
      for (let i = 0; i < notes.length; i += chunk) {
        const batch = notes.slice(i, i + chunk);
        const got = await Promise.all(
          batch.map(async n => {
            try {
              return [n.path, n.size > decl.limits.max_text_bytes ? '' : await fs.readText(n.path)] as const;
            } catch {
              return [n.path, ''] as const;
            }
          })
        );
        if (gen.current !== myGen) return;
        got.forEach(([p, t]) => map.set(p, t));
        setIndexing({ parsed: map.size, total: notes.length });
      }
      texts.current = map;
      const idx = await runIndex(map, walked.files.map(f => f.path), {
        chunk,
        onProgress: (parsed, total) => gen.current === myGen && setIndexing({ parsed, total }),
      });
      if (gen.current !== myGen) return;
      setIndex(idx);
      setIndexing(null);
    },
    [decl.limits]
  );

  const openRoot = useCallback(
    async (root: string, name: string) => {
      if (!native) {
        setError('This build has no folder access (the Vault plugin is missing).');
        return;
      }
      setBusy(true);
      setError(null);
      const myGen = ++gen.current;
      try {
        const fs = nativeVaultFS(native, root);
        await loadDir(fs, '');
        setVault({ root, name, fs });
        setRecent(touchRecent(storage, decl.open.storage_key, decl.open.recent_max, { root, name }));
        setTabs([]);
        setActive(null);
        setIndex(null);
        setDirs(d => ({ '': d[''] ?? [] }));
        readObsidianConfig(
          {
            tryReadText: p => fs.tryReadText(p),
            listNames: async dir => {
              try {
                const parts = dir.split('/');
                // list level by level so every id is known
                let cur = '';
                for (const part of parts) {
                  const hit = (await fs.list(cur)).find(e => e.name === part && e.isDir);
                  if (!hit) return [];
                  cur = hit.path;
                }
                return (await fs.list(cur)).map(e => ({ name: e.name, isDir: e.isDir, size: e.size }));
              } catch {
                return [];
              }
            },
          },
          decl
        ).then(c => gen.current === myGen && setConfig(c));
        void reindex(fs, false, myGen).catch(e => setError('Indexing failed: ' + errText(e)));
      } catch (e) {
        setError(errText(e));
        setVault(null);
      } finally {
        setBusy(false);
      }
    },
    [native, loadDir, reindex, storage, decl]
  );

  const pickFolder = useCallback(async () => {
    if (!native) return setError('This build has no folder access (the Vault plugin is missing).');
    try {
      const v = await native.pickVault();
      await openRoot(v.root, v.name);
    } catch (e) {
      const msg = errText(e);
      if (!/No folder was chosen/i.test(msg)) setError(msg);
    }
  }, [native, openRoot]);

  const openTyped = useCallback(
    async (path: string) => {
      if (!native) return setError('This build has no folder access (the Vault plugin is missing).');
      try {
        const v = await native.openPath({ path });
        await openRoot(v.root, v.name);
      } catch (e) {
        setError(errText(e));
      }
    },
    [native, openRoot]
  );

  const closeVault = useCallback(() => {
    gen.current++;
    setVault(null);
    setTabs([]);
    setActive(null);
    setIndex(null);
    setConfig(null);
    setFiles([]);
    setDirs({});
    texts.current = new Map();
  }, []);

  const forget = useCallback(
    (root: string) => {
      setRecent(forgetRecent(storage, decl.open.storage_key, root));
      if (root.startsWith('content://')) void native?.releaseVault({ root }).catch(() => undefined);
      setPersisted(p => p.filter(v => v.root !== root));
    },
    [storage, decl.open.storage_key, native]
  );

  const toggleHidden = useCallback(() => {
    if (!vault) return;
    const next = !showHidden;
    setShowHidden(next);
    const myGen = ++gen.current;
    void reindex(vault.fs, next, myGen).catch(() => undefined);
  }, [vault, showHidden, reindex]);

  const rebuildIndex = useCallback(() => {
    if (!vault) return;
    const myGen = ++gen.current;
    void reindex(vault.fs, showHidden, myGen).catch(e => setError('Indexing failed: ' + errText(e)));
  }, [vault, showHidden, reindex]);

  // ---- tabs ----

  const openFile = useCallback(
    async (path: string) => {
      if (!vault) return;
      const existing = tabsRef.current.find(t => t.path === path);
      if (existing) return setActive(path);
      const h = handlerFor(decl, path);
      const tab: Tab = { path, view: h.default_view, text: null, dirty: false, error: null, cdn: decl.html.cdn.default };
      try {
        if (isTextHandler(h)) {
          const size = files.find(f => f.path === path)?.size ?? 0;
          if (size > decl.limits.max_text_bytes) throw new Error('This file is larger than the text limit.');
          tab.text = await vault.fs.readText(path);
        } else if (h.handler === 'unsupported') {
          tab.error = 'This file type is not supported yet.';
        }
      } catch (e) {
        tab.error = errText(e);
      }
      setTabs(t => [...t.filter(x => x.path !== path), tab].slice(-decl.layout.tab_max));
      setActive(path);
    },
    [vault, decl, files]
  );

  const closeTab = useCallback((path: string) => {
    setTabs(t => {
      const i = t.findIndex(x => x.path === path);
      const rest = t.filter(x => x.path !== path);
      setActive(a => (a === path ? (rest[Math.min(i, rest.length - 1)]?.path ?? null) : a));
      return rest;
    });
  }, []);

  const patchTab = useCallback((path: string, patch: Partial<Tab>) => {
    setTabs(t => t.map(x => (x.path === path ? { ...x, ...patch } : x)));
  }, []);

  const setText = useCallback((path: string, text: string) => patchTab(path, { text, dirty: true }), [patchTab]);

  const save = useCallback(
    async (path: string) => {
      const tab = tabsRef.current.find(t => t.path === path);
      if (!vault || !tab || tab.text === null || !tab.dirty) return;
      try {
        await vault.fs.writeText(path, tab.text);
        patchTab(path, { dirty: false, error: null });
        if (INDEXED_EXT.includes(path.slice(path.lastIndexOf('.') + 1).toLowerCase())) {
          texts.current.set(path, tab.text);
          const myGen = gen.current;
          void runIndex(texts.current, files.map(f => f.path), { chunk: decl.limits.index_chunk })
            .then(idx => gen.current === myGen && setIndex(idx))
            .catch(() => undefined);
        }
      } catch (e) {
        patchTab(path, { error: 'Save failed: ' + errText(e) });
      }
    },
    [vault, patchTab, files, decl.limits.index_chunk]
  );

  // autosave: a dirty tab is written after a quiet period
  useEffect(() => {
    const dirty = tabs.filter(t => t.dirty);
    if (dirty.length === 0) return;
    const id = setTimeout(() => dirty.forEach(t => void save(t.path)), decl.limits.autosave_ms);
    return () => clearTimeout(id);
  }, [tabs, save, decl.limits.autosave_ms]);

  const createFile = useCallback(
    async (dir: string, name: string, text = '') => {
      if (!vault) return null;
      try {
        const path = await vault.fs.createFile(dir, name, text);
        texts.current.set(path, text);
        await loadDir(vault.fs, dir);
        const entry: VaultEntry = { path, name, isDir: false, size: text.length, mtime: Date.now() };
        setFiles(f => [...f.filter(x => x.path !== path), entry]);
        await openFile(path);
        return path;
      } catch (e) {
        setError(errText(e));
        return null;
      }
    },
    [vault, loadDir, openFile]
  );

  const newNote = useCallback(
    async (dir = '') => {
      const names = new Set((dirs[dir] ?? []).map(e => e.name));
      let n = 'Untitled';
      for (let i = 1; names.has(n + '.md'); i++) n = `Untitled ${i}`;
      return createFile(dir, n + '.md');
    },
    [dirs, createFile]
  );

  const newCanvas = useCallback(
    async (dir = '') => {
      const names = new Set((dirs[dir] ?? []).map(e => e.name));
      let n = 'Untitled';
      for (let i = 1; names.has(n + '.canvas'); i++) n = `Untitled ${i}`;
      return createFile(dir, n + '.canvas', '{\n\t"nodes": [],\n\t"edges": []\n}');
    },
    [dirs, createFile]
  );

  /** Follow a wikilink: open the note it resolves to, or create it next to the current note. */
  const followLink = useCallback(
    async (target: string, from: string) => {
      if (!index) return;
      const { resolveLink } = await import('./vault-index');
      const hit = resolveLink(target, from, files.map(f => f.path));
      if (hit) return openFile(hit);
      const name = baseName(target).includes('.') ? baseName(target) : baseName(target) + '.md';
      await createFile(parentOf(from), name);
    },
    [index, files, openFile, createFile]
  );

  const readDataUrl = useCallback(
    async (path: string): Promise<string | null> => {
      if (!vault) return null;
      try {
        const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
        const mime = ext === 'svg' ? 'image/svg+xml' : ext === 'jpg' ? 'image/jpeg' : `image/${ext}`;
        return `data:${mime};base64,${await vault.fs.readBinary(path)}`;
      } catch {
        return null;
      }
    },
    [vault]
  );

  const allPaths = useMemo(() => files.map(f => f.path), [files]);
  const noteTexts = texts;

  return {
    hasNative: native !== null,
    vault, busy, error, setError, recent, persisted, showHidden, dirs, files, allPaths, truncated,
    index, indexing, tabs, active, setActive, config, noteTexts,
    pickFolder, openRoot, openTyped, closeVault, forget, toggleHidden, rebuildIndex,
    loadDir: (dir: string) => (vault ? loadDir(vault.fs, dir) : Promise.resolve([])),
    openFile, closeTab, patchTab, setText, save, newNote, newCanvas, followLink, readDataUrl,
    titleOf: stem,
  };
}

export type VaultApi = ReturnType<typeof useVault>;
