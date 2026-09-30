// configs.js — frontend Configs overlay (same app UI). Per-app settings live in
// localStorage and are read when opening the matching tab. The Terminal/SSH
// settings open the native screen via the AndroidTerm bridge.
const Configs = {
  KEYS: {
    browserUrl: "myk-cfg-browser-url",
    fbPath: "myk-cfg-fb-path",
    fePath: "myk-cfg-fe-path",
  },
  get(k, def) { try { return localStorage.getItem(k) || def; } catch (e) { return def; } },
  set(k, v) { try { if (v) localStorage.setItem(k, v); else localStorage.removeItem(k); } catch (e) {} },

  open() {
    const byId = (id) => document.getElementById(id);
    byId("cfg-browser-url").value = this.get(this.KEYS.browserUrl, "https://duckduckgo.com");
    byId("cfg-fb-path").value = this.get(this.KEYS.fbPath, "~");
    byId("cfg-fe-path").value = this.get(this.KEYS.fePath, "");
    this.loadTerminals();
    byId("configs-overlay").hidden = false;
  },

  // The Terminal field: options come ONLY from AndroidTerm.terminals() (the
  // declared terminal-targets.json list), never from a list in this file.
  // Every open and every change probes the selected terminal, so a terminal
  // that is not installed or has no sshd shows up here in red instead of as a
  // blank tab later.
  loadTerminals() {
    const sel = document.getElementById("cfg-terminal");
    if (!(window.AndroidTerm && window.AndroidTerm.terminals)) {
      sel.disabled = true;
      return this.termStatus("Terminal selection is only available in the app build.", true);
    }
    const t = JSON.parse(window.AndroidTerm.terminals());
    sel.innerHTML = "";
    for (const b of t.backends) {
      const o = document.createElement("option");
      o.value = b.key;
      o.textContent = `${b.label} — ${b.host}:${b.port}`;
      sel.appendChild(o);
    }
    sel.value = t.selected;
    this.termStatus("checking…", false);
    window.AndroidTerm.probeTerminal();
  },
  termStatus(msg, bad) {
    const el = document.getElementById("cfg-terminal-status");
    el.textContent = msg;
    el.style.color = bad ? "#ed1515" : "";
  },
  close() { document.getElementById("configs-overlay").hidden = true; },

  init() {
    const byId = (id) => document.getElementById(id);
    byId("cfg-close").addEventListener("click", () => this.close());
    byId("configs-overlay").addEventListener("click", (e) => { if (e.target.id === "configs-overlay") this.close(); });
    byId("cfg-browser-url").addEventListener("change", (e) => this.set(this.KEYS.browserUrl, e.target.value.trim()));
    byId("cfg-fb-path").addEventListener("change", (e) => this.set(this.KEYS.fbPath, e.target.value.trim()));
    byId("cfg-fe-path").addEventListener("change", (e) => this.set(this.KEYS.fePath, e.target.value.trim()));
    byId("cfg-terminal").addEventListener("change", (e) => {
      this.termStatus("checking…", false);
      window.AndroidTerm.selectTerminal(e.target.value);
    });
    byId("cfg-ssh-open").addEventListener("click", () => {
      if (window.AndroidTerm && window.AndroidTerm.openConfigs) window.AndroidTerm.openConfigs();
      else alert("Terminal/SSH settings are available in the app build.");
    });
    const dev = byId("cfg-dev-open");
    if (dev) dev.addEventListener("click", () => {
      if (window.AndroidTerm && window.AndroidTerm.openDevControl) window.AndroidTerm.openDevControl();
      else alert("Dev Control is available in the app build.");
    });
  },
};
// Probe verdict from TerminalBridge.probeTerminal / selectTerminal.
window.__termProbe = (key, err) => Configs.termStatus(
  err ? `✗ ${err} — is it installed, is its sshd running, is the SSH key authorised? Open SSH settings for the setup steps.` : "✓ reachable",
  !!err);
window.Configs = Configs;
Configs.init();
