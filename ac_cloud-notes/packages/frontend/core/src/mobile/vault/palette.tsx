// Command palette (Ctrl+P) and quick switcher (Ctrl+O): one overlay, two sources.
import { useEffect, useMemo, useRef, useState } from 'react';

import type { CommandRow } from './decl-types';
import { searchPaths } from './vault-index';

export interface PaletteProps {
  mode: 'commands' | 'files';
  commands: CommandRow[];
  paths: string[];
  onRun: (commandId: string) => void;
  onOpen: (path: string) => void;
  onClose: () => void;
}

export const Palette = ({ mode, commands, paths, onRun, onOpen, onClose }: PaletteProps) => {
  const [q, setQ] = useState('');
  const [at, setAt] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => input.current?.focus(), []);

  const items = useMemo(() => {
    if (mode === 'files') return searchPaths(paths, q, 40).map(p => ({ key: p, label: p, hint: '', run: () => onOpen(p) }));
    const needle = q.trim().toLowerCase();
    return commands
      .filter(c => !needle || c.label.toLowerCase().includes(needle) || c.id.includes(needle))
      .map(c => ({ key: c.id, label: c.label, hint: c.hotkey ?? '', run: () => onRun(c.id) }));
  }, [mode, q, commands, paths, onOpen, onRun]);

  const go = (i: number) => {
    const it = items[i];
    if (it) {
      onClose();
      it.run();
    }
  };

  return (
    <div className="cnv-palette" onClick={onClose} data-testid={`palette-${mode}`}>
      <div className="cnv-palette-box" onClick={e => e.stopPropagation()}>
        <input
          ref={input}
          className="cnv-input"
          style={{ border: 0, borderBottom: '1px solid rgba(128,128,128,.3)', borderRadius: 0 }}
          placeholder={mode === 'files' ? 'Find or create a note' : 'Type a command'}
          value={q}
          onChange={e => { setQ(e.target.value); setAt(0); }}
          onKeyDown={e => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setAt(a => Math.min(items.length - 1, a + 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setAt(a => Math.max(0, a - 1)); }
            else if (e.key === 'Enter') go(at);
            else if (e.key === 'Escape') onClose();
          }}
        />
        <div className="cnv-scroll">
          {items.map((it, i) => (
            <button key={it.key} type="button" className="cnv-row" aria-current={i === at} onClick={() => go(i)}>
              <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{it.label}</span>
              <span className="cnv-dim">{it.hint}</span>
            </button>
          ))}
          {items.length === 0 && <div className="cnv-dim" style={{ padding: 8 }}>No match.</div>}
        </div>
      </div>
    </div>
  );
};
