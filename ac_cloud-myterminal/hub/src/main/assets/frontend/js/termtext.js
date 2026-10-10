// termtext.js — what the pane menu reads OUT of a terminal: the whole
// scrollback, the last command with its output, the URLs on screen, and
// whether a paste needs a confirmation before it runs. Pure functions over
// plain rows ({text, wrapped}) and marks ({kind, line, col}) — no xterm, no
// DOM — so test/test-terminal-menu.sh runs them under node against the same
// file the WebView loads.
//
// PROMPT BOUNDARIES. Both fleet terminals' login (ab_cloud-terminal-store/
// login-init.sh) makes fish and bash emit OSC 133 shell-integration marks:
//   A prompt start · B prompt end / command start · C output start · D;exit end
// When a session carries them, "Copy Last" is exact. When it does not (an
// older terminal build, a nested ssh, a shell nobody configured) it falls back
// to the prompt-line heuristic: the block from the previous line that starts
// like the current prompt down to the line before it.
const TermText = {
  // ── OSC 133 ────────────────────────────────────────────────────────────
  // The payload after "133;": "A", "B", "C", "D", "D;0", "A;click_events=1".
  parseOsc133(data) {
    const m = /^([ABCD])(?:;(.*))?$/.exec(String(data || ""));
    if (!m) return null;
    let exit = null;
    if (m[1] === "D" && m[2] !== undefined && /^-?\d+$/.test(m[2].split(";")[0])) exit = parseInt(m[2], 10);
    return { kind: m[1], exit };
  },

  // ── Text out of rows ───────────────────────────────────────────────────
  // rows[i] = { text, wrapped }: `wrapped` means row i continues row i-1 (the
  // terminal soft-wrapped a long line), so no newline goes between them.
  slice(rows, startLine, startCol, endLine, endCol) {
    const out = [];
    let cur = "";
    for (let i = Math.max(0, startLine); i <= Math.min(endLine, rows.length - 1); i++) {
      let t = rows[i].text || "";
      const from = i === startLine ? startCol || 0 : 0;
      const to = i === endLine && endCol !== undefined && endCol !== null ? endCol : t.length;
      t = t.slice(from, Math.max(from, to));
      if (i > startLine && !rows[i].wrapped) { out.push(cur); cur = ""; }
      cur += t;
    }
    out.push(cur);
    const lines = out.map((l) => l.replace(/\s+$/, ""));
    while (lines.length && lines[lines.length - 1] === "") lines.pop();
    while (lines.length && lines[0] === "") lines.shift();
    return lines.join("\n");
  },

  all(rows) { return this.slice(rows, 0, 0, rows.length - 1); },

  lineCount(text) { return text ? text.split("\n").length : 0; },

  // ── Copy Last ──────────────────────────────────────────────────────────
  // { text, source: "marks" | "prompt" } or null when nothing can be found.
  lastCommand(rows, marks) {
    const byMarks = this._lastByMarks(rows, marks || []);
    if (byMarks !== null) return { text: byMarks, source: "marks" };
    const byPrompt = this._lastByPrompt(rows);
    if (byPrompt !== null) return { text: byPrompt, source: "prompt" };
    return null;
  },

  _lastByMarks(rows, marks) {
    const ms = marks.filter((m) => m && m.line >= 0 && m.line < rows.length)
      .slice().sort((a, b) => a.line - b.line || a.col - b.col);
    // Newest command first: a C (output started) with the A before it. A
    // command that printed nothing and whose line is empty is skipped, so an
    // Enter on an empty prompt does not become "the last command".
    for (let ci = ms.length - 1; ci >= 0; ci--) {
      if (ms[ci].kind !== "C") continue;
      let ai = -1, bi = -1;
      for (let j = ci - 1; j >= 0; j--) {
        if (ms[j].kind === "B" && bi < 0) bi = j;
        if (ms[j].kind === "A") { ai = j; break; }
        if (ms[j].kind === "C" || ms[j].kind === "D") break;
      }
      if (ai < 0) continue;
      let end = null;
      for (let j = ci + 1; j < ms.length; j++) {
        if (ms[j].kind === "D" || ms[j].kind === "A") { end = ms[j]; break; }
      }
      const c = ms[ci];
      if (bi >= 0) {
        const cmd = this.slice(rows, ms[bi].line, ms[bi].col, c.line, c.col);
        const out = end ? this.slice(rows, c.line, c.col, end.line, end.col) : this.slice(rows, c.line, c.col, rows.length - 1);
        if (!cmd.trim() && !out.trim()) continue;
      }
      const a = ms[ai];
      const text = end ? this.slice(rows, a.line, a.col, end.line, end.col) : this.slice(rows, a.line, a.col, rows.length - 1);
      if (text.trim()) return text;
    }
    return null;
  },

  // The prompt's stable head: "user@host" when the prompt has one (it stays
  // the same when the directory changes), else everything up to the prompt
  // character ($ # % > ❯ »).
  promptKey(line) {
    const at = /^\s*(\[?[\w.-]+@[\w.-]+)/.exec(line);
    if (at) return at[1];
    const ch = /^\s*(\S{0,40}?[$#%>❯»])(\s|$)/u.exec(line);
    return ch ? ch[1] : null;
  },

  _lastByPrompt(rows) {
    const text = this.all(rows);
    if (!text) return null;
    const lines = text.split("\n");
    const last = lines.length - 1;
    const key = this.promptKey(lines[last]);
    if (!key) return null;
    for (let i = last - 1; i >= 0; i--) {
      if (lines[i].trimStart().startsWith(key)) {
        const block = lines.slice(i, last).join("\n").replace(/\s+$/, "");
        return block || null;
      }
    }
    return null;
  },

  // ── URLs ───────────────────────────────────────────────────────────────
  // http(s):// anything, plus bare domains with a common TLD ("github.com/x",
  // "www.example.org"). File names are not domains: "build.gradle" and
  // "run.sh" have no listed TLD and are left alone.
  _TLDS: "com|org|net|io|dev|app|ai|co|me|info|gov|edu|xyz|tech|cloud|site|page|uk|de|es|fr|it|nl|eu|us|ca|au|br|jp|ru|ch|se|no|pl|pt",
  _re() {
    const tld = this._TLDS;
    return new RegExp(
      "\\bhttps?:\\/\\/[^\\s<>\"'`]+" +
      "|\\b(?:www\\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:" + tld + ")(?::\\d{2,5})?(?:\\/[^\\s<>\"'`]*)?(?![a-z0-9-])",
      "giu");
  },

  // A URL does not end in sentence punctuation, and a ")" is kept only when
  // the URL opened one (Wikipedia-style paths).
  _trim(u) {
    let s = u;
    for (;;) {
      const last = s.slice(-1);
      if (/[.,;:!?'"»”’\]}>*]/.test(last)) { s = s.slice(0, -1); continue; }
      if (last === ")") {
        const open = (s.match(/\(/g) || []).length, close = (s.match(/\)/g) || []).length;
        if (close > open) { s = s.slice(0, -1); continue; }
      }
      return s;
    }
  },

  // [{ url, index, length }] in order of appearance.
  findUrls(text) {
    const found = [];
    const re = this._re();
    let m;
    while ((m = re.exec(String(text || "")))) {
      // An e-mail's domain is not a link of its own.
      if (m.index > 0 && text[m.index - 1] === "@") continue;
      const url = this._trim(m[0]);
      if (url.length < 4 || !/[a-z]/i.test(url)) continue;
      found.push({ url, index: m.index, length: url.length });
    }
    return found;
  },

  // Distinct URLs, most recent (lowest on screen) first.
  urlList(text) {
    const seen = new Set(), out = [];
    const all = this.findUrls(text);
    for (let i = all.length - 1; i >= 0; i--) {
      if (!seen.has(all[i].url)) { seen.add(all[i].url); out.push(all[i].url); }
    }
    return out;
  },

  // ── Paste ──────────────────────────────────────────────────────────────
  // What Paste / Paste & Run send: the clipboard without its trailing
  // newlines (Run adds its own Enter), and whether running it needs a
  // confirmation — any text that is still more than one line, because every
  // newline in it is an Enter the shell will act on.
  pastePlan(text, run) {
    const body = String(text || "").replace(/\r\n?/g, "\n").replace(/\n+$/, "");
    const lines = body ? body.split("\n").length : 0;
    return { body, lines, confirm: !!run && lines > 1, enter: !!run && lines > 0 };
  },
};

if (typeof window !== "undefined") window.TermText = TermText;
if (typeof module !== "undefined" && module.exports) module.exports = TermText;
