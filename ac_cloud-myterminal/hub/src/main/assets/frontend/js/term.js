// term.js — terminals, panes, and splits. Each tab holds a binary split TREE:
// leaves are `.pane` (one xterm.js + one Rust PTY each), internal nodes are
// `.split.row` (left/right) or `.split.col` (top/bottom) flex containers.
// Konsole-style. All keybindings are data-driven (config.json → MYK.config).
const MYK = {
  panes: new Map(),   // paneId -> { term, fit, search, host, tabId }
  activePane: null,
  seq: 0,
  config: {},         // theme/font/terminal/keybindings — set by boot.js

  // ── Create a leaf pane (xterm + PTY) inside `container` (not yet split) ──
  async makePane(tabId, container) {
    const id = "p" + ++this.seq;
    const host = document.createElement("div");
    host.className = "pane";
    host.dataset.id = id;
    host.tabIndex = -1;
    container.appendChild(host);

    const c = this.config || {}, font = c.font || {}, t = c.terminal || {};
    const term = new Terminal({
      fontFamily: font.family || "monospace",
      fontSize: font.size || 11,
      scrollback: t.scrollback ?? 5000,
      cursorBlink: t.cursorBlink ?? true,
      theme: c.theme || {},
      allowProposedApi: true,
    });
    const fit = new FitAddon.FitAddon();
    const search = new SearchAddon.SearchAddon();
    term.loadAddon(fit); term.loadAddon(search);
    term.open(host);
    this._bindKeys(term, id);
    // OSC 133 prompt marks (A prompt, B command, C output, D;exit end), which the fleet
    // terminals' login-init.sh makes fish and bash emit: where each command starts and
    // ends, for Copy Last. Kept as xterm markers so they follow the scrollback.
    const marks = [];
    term.parser.registerOscHandler(133, (data) => {
      const m = TermText.parseOsc133(data);
      if (!m) return false;
      const marker = term.registerMarker(0);
      if (marker) {
        marks.push({ kind: m.kind, exit: m.exit, col: term.buffer.active.cursorX, marker });
        if (marks.length > 4000) marks.splice(0, marks.length - 4000).forEach((x) => x.marker.dispose());
      }
      return true;
    });
    // URLs in the text are links: a tap copies, a long-press opens (Cloud Browser,
    // else the default browser). The provider only finds them; what a tap does is
    // _linkTap, and the long-press is the contextmenu below.
    term.registerLinkProvider({
      provideLinks: (y, cb) => cb(this._linksOnRow(term, y - 1)),
    });
    host.addEventListener("mousedown", () => this.focusPane(id));
    host.addEventListener("click", (e) => {
      // Touch taps do not always reach xterm's own link activation; this does.
      if (Date.now() - this._lastLinkAct < 400 || term.hasSelection()) return;
      const url = this._urlAt(id, e.clientX, e.clientY);
      if (url) this._linkTap(url);
    });
    host.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      this.focusPane(id);
      const url = this._urlAt(id, e.clientX, e.clientY);
      if (url) return this.openUrl(url);
      this.showPaneMenu(e.clientX, e.clientY);
    });

    term.onData((d) => Transport.ptyWrite(id, d));
    term.onResize(({ cols, rows }) => Transport.ptyResize(id, cols, rows));
    term.onTitleChange((title) => Tabs.setTitle(tabId, title));
    const unlisten = await Transport.onPty(id, (data) => term.write(data));
    await Transport.onPtyExit(id, () => this.closeView(id));

    this.panes.set(id, { term, fit, search, host, tabId, unlisten, marks });
    fit.fit();
    await Transport.ptyStart(id, term.cols, term.rows, null);
    return id;
  },

  focusPane(id) {
    const p = this.panes.get(id);
    if (!p) return;
    this.activePane = id;
    for (const [pid, pane] of this.panes) pane.host.classList.toggle("focused", pid === id);
    p.fit.fit(); p.term.focus();
  },

  // ── Split the active pane. dir: "row" = left/right, "col" = top/bottom.
  // before: new pane goes before the current one (left/up) instead of after (right/down).
  async split(dir, before = false) {
    const p = this.panes.get(this.activePane);
    if (!p) return;
    const host = p.host, parent = host.parentNode;
    const wrap = document.createElement("div");
    wrap.className = "split " + dir;
    parent.replaceChild(wrap, host);
    wrap.appendChild(host);
    const nid = await this.makePane(p.tabId, wrap);
    if (before) wrap.insertBefore(this.panes.get(nid).host, host);
    this._fitTab(p.tabId);
    this.focusPane(nid);
  },

  // ── Pane menu (tap / long-press / right-click): read the terminal out, paste
  // into it, its URLs, and the splits. Data-dense: every row says how much it acts on.
  showPaneMenu(x, y) {
    document.getElementById("pane-menu")?.remove();
    const p = this.panes.get(this.activePane);
    if (!p) return;
    const rows = this._rows(p.term);
    const all = TermText.all(rows);
    const last = TermText.lastCommand(rows, this._marks(p));
    const urls = TermText.urlList(all);
    // Whether there is anything to paste, WITHOUT reading it: reading the clipboard to count
    // its lines would raise Android's "pasted from your clipboard" notice on every menu open.
    const clipEmpty = this._clipEmpty();
    const n = (k) => k.toLocaleString();
    const menu = document.createElement("div");
    menu.id = "pane-menu";
    const row = (label, hint, fn, opts = {}) => {
      const it = document.createElement("div");
      it.className = "pane-menu-item" + (opts.disabled ? " disabled" : "");
      const l = document.createElement("span"); l.textContent = label;
      const h = document.createElement("span"); h.className = "pane-menu-hint"; h.textContent = hint || "";
      it.append(l, h);
      if (!opts.disabled) it.addEventListener("click", () => { if (!opts.keep) menu.remove(); fn(); });
      menu.appendChild(it);
      return it;
    };
    row("Copy All", `${n(TermText.lineCount(all))} ln`, () => this._copy(all), { disabled: !all });
    row("Copy Last", last ? `${n(TermText.lineCount(last.text))} ln · ${last.source === "marks" ? "marks" : "prompt"}` : "none",
      () => this._copy(last.text), { disabled: !last });
    row("Paste", clipEmpty ? "empty" : "", () => this.paste(false), { disabled: clipEmpty });
    row("Paste & Run", clipEmpty ? "empty" : "+ Enter · asks if >1 ln", () => this.paste(true), { disabled: clipEmpty });
    row("URL — Select ▸", `${urls.length}`, () => this._urlMenu(menu, urls), { disabled: !urls.length, keep: true });
    const sep = document.createElement("div"); sep.className = "pane-menu-sep"; menu.appendChild(sep);
    const splits = document.createElement("div");
    splits.className = "pane-menu-item pane-menu-split";
    const sl = document.createElement("span"); sl.textContent = "Split";
    splits.appendChild(sl);
    for (const [glyph, title, dir, before] of [["→", "Split Right", "row", false], ["←", "Split Left", "row", true],
                                               ["↓", "Split Down", "col", false], ["↑", "Split Up", "col", true]]) {
      const b = document.createElement("button");
      b.textContent = glyph; b.title = title;
      b.addEventListener("click", (e) => { e.stopPropagation(); menu.remove(); this.split(dir, before); });
      splits.appendChild(b);
    }
    menu.appendChild(splits);
    row("Close Pane", "", () => this.closeView());
    this._place(menu, x, y);
  },

  // The URL list replaces the menu's rows: tap copies, long-press opens.
  _urlMenu(menu, urls) {
    menu.innerHTML = "";
    const head = document.createElement("div");
    head.className = "pane-menu-head";
    head.textContent = `URLs · ${urls.length} · tap copy · hold open`;
    menu.appendChild(head);
    for (const u of urls) {
      const it = document.createElement("div");
      it.className = "pane-menu-item pane-menu-url";
      it.textContent = u; it.title = u;
      this._tapOrHold(it, () => { menu.remove(); this._copy(u, "URL copied"); }, () => { menu.remove(); this.openUrl(u); });
      menu.appendChild(it);
    }
  },

  // Tap → onTap, long-press (≥ 500 ms, or the platform's contextmenu) → onHold. Never both.
  _tapOrHold(el, onTap, onHold) {
    let down = 0, held = false;
    el.addEventListener("pointerdown", () => { down = Date.now(); held = false; });
    el.addEventListener("contextmenu", (e) => { e.preventDefault(); if (!held) { held = true; onHold(); } });
    el.addEventListener("click", () => {
      if (held) return;
      if (down && Date.now() - down >= 500) { held = true; onHold(); } else onTap();
    });
  },

  _place(menu, x, y) {
    document.body.appendChild(menu);
    const r = menu.getBoundingClientRect();
    menu.style.left = Math.max(4, Math.min(x, window.innerWidth - r.width - 4)) + "px";
    menu.style.top = Math.max(4, Math.min(y, window.innerHeight - r.height - 4)) + "px";
    const closeOnce = (e) => { if (!menu.contains(e.target)) menu.remove(); else document.addEventListener("mousedown", closeOnce, { once: true }); };
    setTimeout(() => document.addEventListener("mousedown", closeOnce, { once: true }), 0);
  },

  // ── Reading the buffer ───────────────────────────────────────────────────
  // Every row of the scrollback as {text, wrapped}; a row that continues onto the
  // next keeps its trailing spaces (they are part of the line), others are trimmed.
  _rows(term) {
    const b = term.buffer.active, rows = [];
    for (let i = 0; i < b.length; i++) {
      const l = b.getLine(i), next = b.getLine(i + 1);
      rows.push({ text: l ? l.translateToString(!(next && next.isWrapped)) : "", wrapped: !!(l && l.isWrapped) });
    }
    return rows;
  },
  _marks(p) {
    return (p.marks || []).filter((m) => !m.marker.isDisposed && m.marker.line >= 0)
      .map((m) => ({ kind: m.kind, line: m.marker.line, col: m.col }));
  },

  // The logical line (soft-wrapped rows joined) around buffer row [line].
  _logical(term, line) {
    const b = term.buffer.active;
    let start = line, end = line;
    while (start > 0 && b.getLine(start)?.isWrapped) start--;
    while (b.getLine(end + 1)?.isWrapped) end++;
    const parts = [];
    for (let i = start; i <= end; i++) parts.push(b.getLine(i)?.translateToString(i === end) ?? "");
    return { start, parts, text: parts.join("") };
  },
  _pos(parts, start, offset) {
    for (let i = 0; i < parts.length; i++) {
      if (offset < parts[i].length || i === parts.length - 1) return { y: start + i, x: offset };
      offset -= parts[i].length;
    }
    return { y: start, x: 0 };
  },
  _linksOnRow(term, line) {
    if (line < 0) return undefined;
    const { start, parts, text } = this._logical(term, line);
    const links = [];
    for (const u of TermText.findUrls(text)) {
      const a = this._pos(parts, start, u.index), z = this._pos(parts, start, u.index + u.length - 1);
      if (a.y > line || z.y < line) continue;
      links.push({
        range: { start: { x: a.x + 1, y: a.y + 1 }, end: { x: z.x + 1, y: z.y + 1 } },
        text: u.url,
        decorations: { underline: true, pointerCursor: true },
        activate: (_e, t) => { this._lastLinkAct = Date.now(); this._linkTap(t); },
      });
    }
    return links.length ? links : undefined;
  },
  // The URL under a screen point, or null.
  _urlAt(id, x, y) {
    const p = this.panes.get(id);
    const scr = p?.host.querySelector(".xterm-screen");
    if (!scr) return null;
    const r = scr.getBoundingClientRect();
    const col = Math.floor((x - r.left) / (r.width / p.term.cols));
    const row = Math.floor((y - r.top) / (r.height / p.term.rows));
    if (col < 0 || row < 0 || col >= p.term.cols || row >= p.term.rows) return null;
    const line = p.term.buffer.active.viewportY + row;
    const { start, parts, text } = this._logical(p.term, line);
    let off = col;
    for (let i = start; i < line; i++) off += parts[i - start].length;
    const hit = TermText.findUrls(text).find((u) => off >= u.index && off < u.index + u.length);
    return hit ? hit.url : null;
  },

  // ── Clipboard, links, paste ──────────────────────────────────────────────
  _lastLinkAct: 0,
  _linkTap(url) { this._copy(url, "URL copied · hold to open"); },
  openUrl(url) {
    if (window.AndroidTerm && window.AndroidTerm.openUrl) window.AndroidTerm.openUrl(url);
    else if (window.AndroidTerm && window.AndroidTerm.browserOpen) window.AndroidTerm.browserOpen(url);
    else window.open(/^[a-z][a-z0-9+.-]*:\/\//i.test(url) ? url : "https://" + url, "_blank");
    this.toast("Opening " + url);
  },
  _copy(text, msg) {
    if (!text) return;
    if (window.AndroidTerm && window.AndroidTerm.clipboardSet) window.AndroidTerm.clipboardSet(text);
    else navigator.clipboard?.writeText(text);
    this.toast(msg || `Copied ${TermText.lineCount(text).toLocaleString()} ln`);
  },
  // True only when the app bridge KNOWS the clipboard holds nothing (it asks without reading).
  _clipEmpty() {
    try { return !!(window.AndroidTerm && window.AndroidTerm.clipboardHas && !window.AndroidTerm.clipboardHas()); }
    catch (e) { return false; }
  },
  // Synchronous clipboard read when the app bridge has one; null if not. Only on a paste.
  _clipPeek() {
    try { return window.AndroidTerm && window.AndroidTerm.clipboardGet ? window.AndroidTerm.clipboardGet() : null; }
    catch (e) { return null; }
  },
  async _clipRead() {
    const peek = this._clipPeek();
    if (peek !== null) return peek;
    try { return await navigator.clipboard.readText(); } catch (e) { return ""; }
  },
  // Paste, or Paste & Run (paste + Enter). A multi-line Run asks first: every newline
  // in it is an Enter the shell acts on.
  async paste(run) {
    const p = this.panes.get(this.activePane);
    if (!p) return;
    const plan = TermText.pastePlan(await this._clipRead(), run);
    if (!plan.lines) return this.toast("Clipboard is empty");
    const go = () => {
      p.term.paste(plan.body);
      if (plan.enter) Transport.ptyWrite(this.activePane, "\r");
      p.term.focus();
    };
    if (plan.confirm) this.confirmRun(plan, go); else go();
  },
  confirmRun(plan, go) {
    document.getElementById("paste-confirm")?.remove();
    const box = document.createElement("div");
    box.id = "paste-confirm";
    const panel = document.createElement("div");
    panel.className = "paste-confirm-panel";
    const head = document.createElement("div");
    head.className = "pane-menu-head";
    head.textContent = `Run ${plan.lines} lines? Each line is an Enter.`;
    const pre = document.createElement("pre");
    const shown = plan.body.split("\n");
    pre.textContent = shown.slice(0, 12).join("\n") + (shown.length > 12 ? `\n… ${shown.length - 12} more` : "");
    const bar = document.createElement("div");
    bar.className = "paste-confirm-bar";
    const cancel = document.createElement("button"); cancel.textContent = "Cancel"; cancel.id = "paste-cancel";
    const runBtn = document.createElement("button"); runBtn.textContent = `Run ${plan.lines}`; runBtn.id = "paste-run";
    cancel.addEventListener("click", () => box.remove());
    runBtn.addEventListener("click", () => { box.remove(); go(); });
    bar.append(cancel, runBtn);
    panel.append(head, pre, bar);
    box.appendChild(panel);
    document.body.appendChild(box);
    return box;
  },
  toast(msg) {
    let t = document.getElementById("term-toast");
    if (!t) { t = document.createElement("div"); t.id = "term-toast"; document.body.appendChild(t); }
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(this._toastT);
    this._toastT = setTimeout(() => { t.hidden = true; }, 1600);
  },

  // ── Close the active view (pane). Unwraps the split; closes tab if last. ──
  closeView(id) {
    id = id || this.activePane;
    const p = this.panes.get(id);
    if (!p) return;
    const tabId = p.tabId;
    this._disposePane(id);
    const host = p.host, wrap = host.parentNode;
    if (wrap && wrap.classList.contains("split")) {
      const sibling = [...wrap.children].find((c) => c !== host);
      wrap.parentNode.replaceChild(sibling, wrap);
      const firstPane = sibling.classList.contains("pane") ? sibling : sibling.querySelector(".pane");
      this._fitTab(tabId);
      if (firstPane) this.focusPane(firstPane.dataset.id);
    } else {
      Tabs.close(tabId); // was the tab's only pane
    }
  },

  _disposePane(id) {
    const p = this.panes.get(id);
    if (!p) return;
    Transport.ptyKill(id);
    if (p.unlisten) p.unlisten();
    p.term.dispose();
    p.host.remove();
    this.panes.delete(id);
  },

  paneIdsOf(tabId) {
    return [...this.panes.entries()].filter(([, p]) => p.tabId === tabId).map(([id]) => id);
  },
  disposeTab(tabId) { for (const id of this.paneIdsOf(tabId)) this._disposePane(id); },
  _fitTab(tabId) { for (const id of this.paneIdsOf(tabId)) this.panes.get(id)?.fit.fit(); },
  fitActive() { const p = this.panes.get(this.activePane); if (p) p.fit.fit(); },

  focusSibling(delta) {
    const p = this.panes.get(this.activePane); if (!p) return;
    const ids = this.paneIdsOf(p.tabId);
    const i = ids.indexOf(this.activePane);
    if (i < 0) return;
    this.focusPane(ids[(i + delta + ids.length) % ids.length]);
  },

  // ── Data-driven keybinding dispatch ──────────────────────────────────────
  _action(name, term, id) {
    switch (name) {
      case "new-tab":          Tabs.newTab(); return false;
      case "close-tab":        Tabs.close(this.panes.get(this.activePane)?.tabId); return false;
      case "next-tab":         Tabs.next(); return false;
      case "prev-tab":         Tabs.prev(); return false;
      case "move-tab-left":    Tabs.move(-1); return false;
      case "move-tab-right":   Tabs.move(+1); return false;
      case "copy": {
        const sel = term.getSelection();
        if (sel) { this._copy(sel); return false; }
        return true; // no selection → let it fall through
      }
      case "paste":            this.paste(false); return false;
      case "find":             Find.open(); return false;
      case "split-left-right": this.split("row"); return false;
      case "split-top-bottom": this.split("col"); return false;
      case "close-view":       this.closeView(this.activePane); return false;
      case "focus-next-view":  this.focusSibling(+1); return false;
      case "focus-prev-view":  this.focusSibling(-1); return false;
      case "clear-scrollback": term.clear(); return false;
      default: return true;
    }
  },

  _bindKeys(term, id) {
    term.attachCustomKeyEventHandler((e) => {
      if (e.type !== "keydown") return true;
      for (const b of (this.config.keybindings || [])) {
        if (!!e.ctrlKey === !!b.ctrl && !!e.shiftKey === !!b.shift &&
            !!e.altKey === !!b.alt && e.code === b.key) {
          return this._action(b.action, term, id) === true;
        }
      }
      return true;
    });
  },
};

// Find bar (find action)
const Find = {
  open() { const b = document.getElementById("findbar"); b.hidden = false; document.getElementById("find-input").focus(); },
  close() { document.getElementById("findbar").hidden = true; MYK.panes.get(MYK.activePane)?.term.focus(); },
  _s() { return MYK.panes.get(MYK.activePane)?.search; },
  next() { const q = document.getElementById("find-input").value; if (q) this._s()?.findNext(q); },
  prev() { const q = document.getElementById("find-input").value; if (q) this._s()?.findPrevious(q); },
};
