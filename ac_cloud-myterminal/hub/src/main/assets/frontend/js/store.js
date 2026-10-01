// store.js — MyTerminal's Store tab: the terminal's package manager, as a UI
// over cloud-store (ab_cloud-terminal-store, #644). cloud-store is the ONE
// engine on the phone that owns both layers:
//   links     verify / repair / generations / rollback / switch — the $HOME
//             link tree no package manager manages (#638/#640/#641 lived there)
//   packages  installed / search / install / remove / update, which it
//             delegates to the DECLARED nix profile, or refuses by name where
//             the terminal declares no package manager (the termux rootfs is
//             baked at build time and replaced on update)
// #659: this file used to run `nix profile` / `pkg` itself — a second thing
// driving the package layer beside cloud-store. It now runs NOTHING but
// cloud-store; test/test-store-tab-drives-cloud-store.sh holds that line.
//
// Transport: a dedicated hidden PTY (same Transport.ptyStart/ptyWrite/onPty
// term.js uses, no xterm attached). Each call is wrapped in `sh -c` so the
// login shell (fish in the nix terminal) does not matter, and its output is
// framed by \x01-delimited BEGIN/END markers carrying the exit code. The PTY's
// echo of the command line contains the ESCAPED marker text, never the byte,
// so the echo can never be mistaken for output.
const Store = {
  // The shipped, read-only engine — repairs from here even when the $HOME copy
  // is what broke. MUST equal "/" + store.json::store.install_dir + "/" +
  // store.json::store.engine (the tester asserts it, as render-store.py does
  // for login-init.sh's copy of the same literal). PATH is the fallback.
  ENGINE_PATH: "/usr/lib/cloud-store/cloud-store",
  ENGINE_NAME: "cloud-store",

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

  // Resolve the oldest queued call once its END marker is in the buffer.
  _drain() {
    const re = /\x01B\x01([\s\S]*?)\x01(\d+)\x01E\x01/;
    for (;;) {
      const m = this._buf.match(re);
      if (!m || !this._queue.length) return;
      this._buf = this._buf.slice(m.index + m[0].length);
      this._queue.shift().resolve({ rc: Number(m[2]), out: m[1].replace(/\x1b\[[0-9;]*[A-Za-z]/g, "").replace(/\r/g, "") });
    }
  },

  // The ONE shell line this tab ever sends. Args are refused unless they are
  // plain tokens: they land inside a quoted `sh -c`, and a package name or
  // query never needs a quote, a space or a `$`.
  _line(args) {
    for (const a of args) {
      if (!/^[A-Za-z0-9._+@-]+$/.test(a)) throw new Error(`refusing argument ${JSON.stringify(a)}: only letters, digits and . _ + @ - are passed to ${this.ENGINE_NAME}`);
    }
    const missing = `${this.ENGINE_NAME}: not found in this terminal (looked at ${this.ENGINE_PATH} and PATH). The selected terminal has no fleet store - pick a Cloud Terminal in Configs, or update it.`;
    const script =
      `printf "\\001B\\001"; E="${this.ENGINE_PATH}"; [ -x "$E" ] || E=$(command -v ${this.ENGINE_NAME}) || E=; ` +
      `if [ -z "$E" ]; then echo "${missing}"; rc=127; else "$E" ${args.join(" ")} 2>&1; rc=$?; fi; ` +
      `printf "\\001%s\\001E\\001" "$rc"`;
    return `sh -c '${script.replace(/'/g, `'\\''`)}'\n`;
  },

  // Run one cloud-store command to completion → {rc, out}.
  async engine(...args) {
    const line = this._line(args);
    await this._ensurePty();
    return new Promise((resolve) => {
      this._queue.push({ resolve });
      Transport.ptyWrite(this._ptyId, line);
    });
  },

  // ── parsers over cloud-store's own output ──

  // verify names every broken link ("FAIL — <tool>: …" / "FAIL — fixed link
  // <path> …"); keep the name — a count nobody can act on is the defect #644
  // was filed against, one level up.
  parseVerify(res) {
    const broken = [];
    for (const l of res.out.split("\n")) {
      if (!/FAIL [—-]+ /.test(l)) continue;
      const detail = l.replace(/^.*?FAIL [—-]+ /, "");
      const m = detail.match(/^fixed link (\S+)/) || detail.match(/^([^:\s]+): /);
      broken.push({ name: m ? m[1] : "store", detail });
    }
    return { ok: res.rc === 0 && !broken.length, broken, out: res.out.trim() };
  },

  parseGenerations(res) {
    return res.out.split("\n").map((l) => l.trim().match(/^(\d+)( \(live\))?$/)).filter(Boolean)
      .map((m) => ({ n: m[1], live: !!m[2] }));
  },

  // `nix profile list`: new nix prints "Name: <n>" blocks, old nix prints
  // "<idx> flake:nixpkgs#…<attr> …" lines. Both are accepted.
  parseInstalled(res) {
    const rows = [];
    for (const l of res.out.split("\n")) {
      let m = l.match(/^Name:\s+(\S+)/);
      if (m) { rows.push({ name: m[1], desc: "" }); continue; }
      m = l.match(/^\d+\s+\S*#(\S+)/);
      if (m) rows.push({ name: m[1].split(".").pop(), desc: l.trim() });
    }
    return rows;
  },

  // `nix search --json`: {"legacyPackages.<system>.<attr>": {pname, description}}.
  parseSearch(res) {
    try {
      const obj = JSON.parse(res.out.slice(res.out.indexOf("{")));
      return Object.entries(obj).map(([k, v]) => ({ name: k.split(".").slice(2).join(".") || v.pname, desc: v.description || "" }));
    } catch { return []; }
  },

  _esc(s) { return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]); },

  // Pure: verify result → HTML. Every broken link is a row WITH its name.
  renderLinks(v) {
    if (v.ok) return `<div class="store-ok">✓ every declared link resolves</div>`;
    if (!v.broken.length) return `<div class="store-bad">✗ verify failed</div><pre class="store-out">${this._esc(v.out)}</pre>`;
    return `<div class="store-bad">✗ ${v.broken.length} broken link(s) — Repair rebuilds them from the declaration</div>` +
      v.broken.map((b) => `<div class="store-item store-broken"><span class="store-item-name">${this._esc(b.name)}</span><span class="store-item-desc">${this._esc(b.detail)}</span></div>`).join("");
  },

  mount(container) {
    container.innerHTML = `
      <div class="store-wrap">
        <div class="store-toolbar">
          <span class="store-col-title">Links</span>
          <button class="store-verify-btn">Verify</button>
          <button class="store-repair-btn">Repair</button>
          <button class="store-rollback-btn">Rollback</button>
        </div>
        <div class="store-panes">
          <div class="store-col"><div class="store-col-title">Link status</div><div class="store-list store-links"></div></div>
          <div class="store-col"><div class="store-col-title">Generations</div><div class="store-list store-gens"></div></div>
        </div>
        <div class="store-toolbar">
          <span class="store-col-title">Packages</span>
          <input class="store-search-input" type="text" spellcheck="false" placeholder="Search available packages…" />
          <button class="store-search-btn">Search</button>
          <button class="store-refresh-btn">Refresh installed</button>
          <button class="store-update-btn">Update all</button>
        </div>
        <div class="store-panes">
          <div class="store-col"><div class="store-col-title">Installed</div><div class="store-list store-installed"></div></div>
          <div class="store-col"><div class="store-col-title">Available</div><div class="store-list store-available"></div></div>
        </div>
        <pre class="store-log"></pre>
      </div>`;

    const $ = (sel) => container.querySelector(sel);
    const linksEl = $(".store-links"), gensEl = $(".store-gens");
    const installedEl = $(".store-installed"), availableEl = $(".store-available"), logEl = $(".store-log");
    const log = (msg) => { if (msg) logEl.textContent = msg.trim() + "\n" + logEl.textContent; };
    const msgBox = (host, text) => { host.innerHTML = `<div class="store-empty">${Store._esc(text)}</div>`; };

    const renderList = (host, rows, action) => {
      host.innerHTML = "";
      if (!rows.length) return msgBox(host, "No packages.");
      for (const row of rows) {
        const item = document.createElement("div");
        item.className = "store-item";
        item.innerHTML = `<span class="store-item-name">${Store._esc(row.name)}</span><span class="store-item-desc">${Store._esc(row.desc || "")}</span>`;
        const btn = document.createElement("button");
        btn.className = "store-item-btn";
        btn.textContent = action.label;
        btn.addEventListener("click", () => action.run(row.name, btn));
        item.appendChild(btn);
        host.appendChild(item);
      }
    };

    // Runs a link-changing command, logs its output, then re-reads the truth.
    const act = async (...args) => {
      try { log((await Store.engine(...args)).out); } catch (e) { log(String(e.message || e)); }
      refreshLinks();
    };

    const refreshLinks = async () => {
      linksEl.innerHTML = `<div class="store-empty">Verifying…</div>`;
      linksEl.innerHTML = Store.renderLinks(Store.parseVerify(await Store.engine("verify")));
      const gens = Store.parseGenerations(await Store.engine("generations"));
      renderList(gensEl, gens.map((g) => ({ name: g.n, desc: g.live ? "live" : "" })), {
        label: "Switch", run: (n, btn) => { btn.disabled = true; act("switch", n); },
      });
    };

    const refreshInstalled = async () => {
      msgBox(installedEl, "Loading…");
      const res = await Store.engine("installed");
      if (res.rc !== 0) return msgBox(installedEl, res.out.trim());
      renderList(installedEl, Store.parseInstalled(res), {
        label: "Remove",
        run: async (name, btn) => {
          btn.disabled = true;
          log(`Removing ${name}…`);
          await act("remove", name);
          refreshInstalled();
        },
      });
    };

    const runSearch = async () => {
      const q = $(".store-search-input").value.trim();
      if (!q) return;
      msgBox(availableEl, "Searching…");
      let res;
      try { res = await Store.engine("search", q); } catch (e) { return msgBox(availableEl, e.message); }
      if (res.rc !== 0) return msgBox(availableEl, res.out.trim());
      renderList(availableEl, Store.parseSearch(res), {
        label: "Install",
        run: async (name, btn) => {
          btn.disabled = true;
          log(`Installing ${name}…`);
          await act("install", name);
          refreshInstalled();
        },
      });
    };

    $(".store-verify-btn").addEventListener("click", refreshLinks);
    $(".store-repair-btn").addEventListener("click", () => act("repair"));
    $(".store-rollback-btn").addEventListener("click", () => act("rollback"));
    $(".store-search-btn").addEventListener("click", runSearch);
    $(".store-search-input").addEventListener("keydown", (e) => { if (e.key === "Enter") runSearch(); });
    $(".store-refresh-btn").addEventListener("click", refreshInstalled);
    $(".store-update-btn").addEventListener("click", async () => { await act("update"); refreshInstalled(); });

    refreshLinks();
    refreshInstalled();
  },
};

// ── node test import (test/test-store-tab-drives-cloud-store.sh) — no-op in the browser ──
if (typeof module !== "undefined" && module.exports) module.exports = { Store };
