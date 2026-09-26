// store.js — the terminal environment's unix package manager: browse
// installed + available packages (libraries, CLIs, GUI apps) and
// install/remove them. Runs the real package manager for the terminal
// backend (Termux `pkg`, or Nix `nix profile` when the env has nix) over a
// dedicated hidden PTY — same Transport.ptyStart/ptyWrite/onPty primitives
// term.js uses for a visible shell pane, just with no xterm attached. Output
// is delimited with a sentinel so each command's result can be parsed out of
// the raw stream (ponytail: no engine protocol change needed for this).
const Store = {
  _ptyId: null, _starting: null, _buf: "", _queue: [],

  async _ensurePty() {
    if (this._starting) return this._starting;
    this._ptyId = "store-pty";
    this._starting = (async () => {
      Transport.onPty(this._ptyId, (data) => { this._buf += data; this._drain(); });
      Transport.onPtyExit(this._ptyId, () => { this._ptyId = null; this._starting = null; });
      await Transport.ptyStart(this._ptyId, 200, 50, null);
    })();
    return this._starting;
  },

  // Resolve the oldest queued run() once its sentinel shows up in the buffer.
  _drain() {
    const marker = "\x01STOREDONE\x01";
    for (;;) {
      const idx = this._buf.indexOf(marker);
      if (idx === -1 || !this._queue.length) return;
      const out = this._buf.slice(0, idx);
      this._buf = this._buf.slice(idx + marker.length);
      this._queue.shift().resolve(out);
    }
  },

  // Run one command to completion in the hidden PTY, return its raw stdout+stderr.
  async run(cmd) {
    await this._ensurePty();
    return new Promise((resolve) => {
      this._queue.push({ resolve });
      Transport.ptyWrite(this._ptyId, `${cmd}; printf '\\x01STOREDONE\\x01'\n`);
    });
  },

  // Strip the echoed input line + prompt noise a real PTY leaves behind —
  // keep only lines that look like output, not the command we just sent.
  _clean(raw, cmd) {
    return raw.split(/\r?\n/).filter((l) => l.trim() && l.trim() !== cmd.trim()).join("\n");
  },

  async detectBackend() {
    if (this._backend) return this._backend;
    const out = await this.run("command -v nix >/dev/null 2>&1 && echo nix || echo termux");
    this._backend = this._clean(out, "").trim().split("\n").pop() === "nix" ? "nix" : "termux";
    return this._backend;
  },

  // ── Backend-specific package manager commands ──
  _cmds(backend) {
    return backend === "nix" ? {
      listInstalled: "nix profile list",
      search: (q) => `nix search nixpkgs ${JSON.stringify(q)} --json 2>/dev/null`,
      install: (name) => `nix profile install nixpkgs#${name}`,
      remove: (name) => `nix profile remove ${name}`,
    } : {
      listInstalled: "pkg list-installed 2>/dev/null",
      search: (q) => `pkg search ${JSON.stringify(q)} 2>/dev/null`,
      install: (name) => `pkg install -y ${name}`,
      remove: (name) => `pkg uninstall -y ${name}`,
    };
  },

  // Parse the loose text pkg/nix emit into {name, desc} rows — both tools
  // print one package per line/paragraph, "name/version desc" or similar;
  // this is intentionally forgiving rather than a strict grammar.
  _parseList(text, backend) {
    const lines = text.split("\n").map((l) => l.trim()).filter(Boolean);
    if (backend === "nix") {
      // `nix search --json` → {"nixpkgs#name": {pname, description}}
      try {
        const obj = JSON.parse(text);
        return Object.entries(obj).map(([k, v]) => ({ name: v.pname || k.split("#").pop(), desc: v.description || "" }));
      } catch { /* fall through to line parsing (e.g. `nix profile list`) */ }
      return lines
        .map((l) => { const m = l.match(/flake:nixpkgs#(\S+)/) || l.match(/^\d+\s+(\S+)/); return m ? { name: m[1], desc: l } : null; })
        .filter(Boolean);
    }
    // termux `pkg`: "name/repo version arch\n  description" pairs, or plain "name" for list-installed.
    return lines
      .filter((l) => !l.startsWith(" ") && !/^(all installed packages|installing|reading)/i.test(l))
      .map((l) => { const [head, ...rest] = l.split(" "); return { name: head.split("/")[0], desc: rest.join(" ") }; });
  },

  mount(container) {
    container.innerHTML = `
      <div class="store-wrap">
        <div class="store-toolbar">
          <span class="store-backend">detecting package manager…</span>
          <input class="store-search-input" type="text" spellcheck="false" placeholder="Search available packages…" />
          <button class="store-search-btn">Search</button>
          <button class="store-refresh-btn">Refresh installed</button>
        </div>
        <div class="store-panes">
          <div class="store-col">
            <div class="store-col-title">Installed</div>
            <div class="store-list store-installed"></div>
          </div>
          <div class="store-col">
            <div class="store-col-title">Available</div>
            <div class="store-list store-available"></div>
          </div>
        </div>
        <pre class="store-log"></pre>
      </div>`;

    const backendEl = container.querySelector(".store-backend");
    const searchInput = container.querySelector(".store-search-input");
    const installedEl = container.querySelector(".store-installed");
    const availableEl = container.querySelector(".store-available");
    const logEl = container.querySelector(".store-log");

    const log = (msg) => { logEl.textContent = msg + "\n" + logEl.textContent; };

    const renderList = (host, rows, action) => {
      host.innerHTML = "";
      if (!rows.length) { host.innerHTML = `<div class="store-empty">No packages.</div>`; return; }
      for (const row of rows) {
        const item = document.createElement("div");
        item.className = "store-item";
        item.innerHTML = `<span class="store-item-name">${row.name}</span><span class="store-item-desc">${row.desc || ""}</span>`;
        const btn = document.createElement("button");
        btn.className = "store-item-btn";
        btn.textContent = action.label;
        btn.addEventListener("click", () => action.run(row.name, btn));
        item.appendChild(btn);
        host.appendChild(item);
      }
    };

    const refreshInstalled = async () => {
      const backend = await Store.detectBackend();
      const cmds = Store._cmds(backend);
      installedEl.innerHTML = `<div class="store-empty">Loading…</div>`;
      const out = await Store.run(cmds.listInstalled);
      const rows = Store._parseList(Store._clean(out, cmds.listInstalled), backend);
      renderList(installedEl, rows, {
        label: "Remove",
        run: async (name, btn) => {
          btn.disabled = true;
          log(`Removing ${name}…`);
          const res = await Store.run(cmds.remove(name));
          log(Store._clean(res, cmds.remove(name)) || `${name} removed.`);
          refreshInstalled();
        },
      });
    };

    const runSearch = async () => {
      const q = searchInput.value.trim();
      if (!q) return;
      const backend = await Store.detectBackend();
      const cmds = Store._cmds(backend);
      availableEl.innerHTML = `<div class="store-empty">Searching…</div>`;
      const out = await Store.run(cmds.search(q));
      const rows = Store._parseList(Store._clean(out, cmds.search(q)), backend);
      renderList(availableEl, rows, {
        label: "Install",
        run: async (name, btn) => {
          btn.disabled = true;
          log(`Installing ${name}…`);
          const res = await Store.run(cmds.install(name));
          log(Store._clean(res, cmds.install(name)) || `${name} installed.`);
          refreshInstalled();
        },
      });
    };

    container.querySelector(".store-search-btn").addEventListener("click", runSearch);
    searchInput.addEventListener("keydown", (e) => { if (e.key === "Enter") runSearch(); });
    container.querySelector(".store-refresh-btn").addEventListener("click", refreshInstalled);

    Store.detectBackend().then((b) => { backendEl.textContent = `package manager: ${b}`; });
    refreshInstalled();
  },
};
