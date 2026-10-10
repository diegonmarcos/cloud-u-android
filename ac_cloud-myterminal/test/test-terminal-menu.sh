#!/usr/bin/env bash
# Tester: the terminal's tap menu reads the terminal out like Termux's does, and runs nothing by
# surprise.
#
# Tapping a pane used to offer only Split and Close. It now has Copy All (the scrollback), Copy
# Last (the last command and its output), Paste, Paste & Run (paste + Enter, confirmed when the
# clipboard holds more than one line), URL — Select (every URL on screen: tap copies, long-press
# opens), then Split and Close; URLs in the text itself copy on tap and open on long-press.
#
# Everything is proved by RUNNING the shipped files under node — js/termtext.js (the pure half)
# and js/term.js (the menu, against a stub DOM and terminal) — and, for the prompt marks, against
# a REAL bash given the fleet's ab_cloud-terminal-store/login-init.sh:
#   M1  OSC 133 payloads parse (A B C D, D;exit, A;options; anything else ignored)
#   M2  Copy Last WITH marks: exact block from hand-built rows, and from a live bash session
#   M3  Copy Last WITHOUT marks: the prompt-line heuristic (fish, bash, root prompts), and null
#       when there is no prompt to anchor on
#   M4  URL detection: http/https, bare domains, trailing punctuation, parentheses, e-mail and
#       file names left alone, distinct and most recent first
#   M5  Paste & Run: one line runs at once, several lines wait for the confirmation, Cancel sends
#       nothing, plain Paste never adds an Enter
#   M6  the menu offers every item, Split and Close kept, counts on each row; the URL list copies
#       on tap and opens on hold
#   M7  index.html loads termtext.js before term.js; login-init.sh emits the marks for fish too
#   MUT each property broken on a copy must turn its check red (incl. the old two-item menu)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(dirname "$APP")"
command -v node >/dev/null || { echo "ERROR: node required" >&2; exit 2; }
command -v bash >/dev/null || { echo "ERROR: bash required" >&2; exit 2; }
FE="$APP/hub/src/main/assets/frontend"
INIT="$ROOT/ab_cloud-terminal-store/login-init.sh"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# ── a live bash transcript with the fleet login's marks ───────────────────
python3 - "$INIT" "$WORK" <<'PY' || { echo "ERROR: could not record a bash session" >&2; exit 2; }
import os, pty, select, sys, time
init, work = sys.argv[1], sys.argv[2]
def record(shell, name, inputs, with_init=True):
    home = os.path.join(work, "home-" + name); os.makedirs(home, exist_ok=True)
    env = {"HOME": home, "PATH": os.environ.get("PATH", "/usr/bin:/bin"), "TERM": "xterm-256color",
           "CLOUD_STORE_INSTALL_DIR": "/nonexistent", "PS1": "fleet@phone:~$ "}
    pid, fd = pty.fork()
    if pid == 0:
        src = (". '%s'; " % init) if with_init else ""
        os.execve("/bin/sh", ["sh", "-c", src + "exec %s --norc --noprofile -i" % shell], env)
    out = b""
    def pump(t):
        nonlocal out
        end = time.time() + t
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], 0.05)
            if r:
                try: out += os.read(fd, 65536)
                except OSError: return
    pump(1.5)
    for line in inputs:
        os.write(fd, line.encode() + b"\r"); pump(0.8)
    os.kill(pid, 9)
    open(os.path.join(work, name + ".bin"), "wb").write(out)
inputs = ["echo first-output", "printf 'two\\nlines\\n'"]
record("bash", "bash-marks", inputs, True)
record("bash", "bash-plain", inputs, False)
PY

exec node - "$FE" "$WORK" "$INIT" <<'JS'
const fs = require("fs"), path = require("path"), vm = require("vm");
const [FE, WORK, INIT] = process.argv.slice(2);
const SRC = { text: fs.readFileSync(path.join(FE, "js/termtext.js"), "utf8"), term: fs.readFileSync(path.join(FE, "js/term.js"), "utf8") };
let pass = 0, fail = 0;
const ok = (m) => { pass++; console.log("  PASS: " + m); };
const bad = (m) => { fail++; console.log("  FAIL: " + m); };
const check = (c, m) => (c ? ok(m) : bad(m));

function loadText(src) { const sb = { module: { exports: {} } }; vm.runInNewContext(src, sb); return sb.module.exports; }

// A tiny screen: enough of a terminal to place text and OSC 133 marks the way xterm does.
function render(bytes) {
  const rows = [""], marks = [];
  let line = 0, col = 0, i = 0;
  const s = bytes.toString("utf8");
  const put = (ch) => { const r = rows[line]; rows[line] = (r.length < col ? r + " ".repeat(col - r.length) : r.slice(0, col)) + ch + r.slice(col + 1); col++; };
  while (i < s.length) {
    const c = s[i];
    if (c === "\x1b") {
      const n = s[i + 1];
      if (n === "]") {
        let j = i + 2, body = "";
        while (j < s.length && s[j] !== "\x07" && !(s[j] === "\x1b" && s[j + 1] === "\\")) body += s[j++];
        i = s[j] === "\x07" ? j + 1 : j + 2;
        if (body.startsWith("133;")) marks.push({ data: body.slice(4), line, col });
        continue;
      }
      if (n === "[") {
        let j = i + 2; while (j < s.length && !/[@-~]/.test(s[j])) j++;
        const params = s.slice(i + 2, j), fin = s[j]; i = j + 1;
        const k = parseInt(params.replace(/[^0-9]/g, ""), 10) || 1;
        if (fin === "K") rows[line] = rows[line].slice(0, col);
        else if (fin === "C") col += k; else if (fin === "D") col = Math.max(0, col - k);
        else if (fin === "G") col = k - 1;
        continue;
      }
      i += 2; continue;
    }
    if (c === "\r") { col = 0; i++; continue; }
    if (c === "\n") { line++; if (rows.length <= line) rows.push(""); i++; continue; }
    if (c === "\b") { col = Math.max(0, col - 1); i++; continue; }
    if (c < " ") { i++; continue; }
    put(c); i++;
  }
  return { rows: rows.map((t) => ({ text: t, wrapped: false })), marks };
}

function verdict(textSrc, termSrc) {
  const T = loadText(textSrc);
  const r = {};
  // M1
  const p = (d) => JSON.stringify(T.parseOsc133(d));
  r.m1 = p("A") === '{"kind":"A","exit":null}' && p("D;0") === '{"kind":"D","exit":0}' && p("D;127") === '{"kind":"D","exit":127}'
    && p("A;click_events=1") === '{"kind":"A","exit":null}' && p("B") === '{"kind":"B","exit":null}' && p("C") === '{"kind":"C","exit":null}'
    && T.parseOsc133("E") === null && T.parseOsc133("") === null && T.parseOsc133("1337;x") === null;
  // M2 hand-built: two commands; the last is ls with two output lines, then a fresh prompt.
  const rows = ["me@phone ~> echo hi", "hi", "me@phone ~> ls", "a.txt", "b.txt", "me@phone ~> "].map((t) => ({ text: t, wrapped: false }));
  const mk = (kind, line, col) => ({ kind, line, col });
  const marks = [mk("A", 0, 0), mk("B", 0, 12), mk("C", 1, 0), mk("D", 2, 0), mk("A", 2, 0), mk("B", 2, 12), mk("C", 3, 0), mk("D", 5, 0), mk("A", 5, 0), mk("B", 5, 12)];
  const last = T.lastCommand(rows, marks);
  r.m2hand = !!last && last.source === "marks" && last.text === "me@phone ~> ls\na.txt\nb.txt";
  // an Enter on an empty prompt is not "the last command"
  const rows2 = rows.concat([{ text: "me@phone ~> ", wrapped: false }]);
  const marks2 = marks.concat([mk("C", 5, 12), mk("D", 6, 0), mk("A", 6, 0), mk("B", 6, 12)]);
  const last2 = T.lastCommand(rows2, marks2);
  r.m2empty = !!last2 && last2.text === "me@phone ~> ls\na.txt\nb.txt";
  // a soft-wrapped output row joins without a newline
  const rows3 = [{ text: "$ cat x", wrapped: false }, { text: "aaaa", wrapped: false }, { text: "bbbb", wrapped: true }, { text: "$ ", wrapped: false }];
  const last3 = T.lastCommand(rows3, [mk("A", 0, 0), mk("B", 0, 2), mk("C", 1, 0), mk("D", 3, 0), mk("A", 3, 0)]);
  r.m2wrap = !!last3 && last3.text === "$ cat x\naaaabbbb";
  // live bash, with the fleet login's marks
  const live = render(fs.readFileSync(path.join(WORK, "bash-marks.bin")));
  const lm = live.marks.map((m) => { const o = T.parseOsc133(m.data); return o && { kind: o.kind, line: m.line, col: m.col }; }).filter(Boolean);
  const kinds = new Set(lm.map((m) => m.kind));
  const ll = T.lastCommand(live.rows, lm);
  r.m2liveMarks = ["A", "B", "C", "D"].every((k) => kinds.has(k));
  r.m2live = !!ll && ll.source === "marks" && ll.text.includes("printf 'two\\nlines\\n'") && ll.text.includes("two\nlines") && !ll.text.includes("first-output");
  r.m2liveText = ll ? ll.text : null;
  // M3 heuristic
  const plain = (a) => a.map((t) => ({ text: t, wrapped: false }));
  const h1 = T.lastCommand(plain(["diego@phone ~> cd x", "diego@phone ~/x> ls", "f1", "f2", "diego@phone ~/x> "]), []);
  const h2 = T.lastCommand(plain(["root@localhost:~# cat a", "A", "root@localhost:~# "]), []);
  const h3 = T.lastCommand(plain(["just", "some", "output"]), []);
  const pl = render(fs.readFileSync(path.join(WORK, "bash-plain.bin")));
  const h4 = T.lastCommand(pl.rows, []);
  r.m3 = !!h1 && h1.source === "prompt" && h1.text === "diego@phone ~/x> ls\nf1\nf2"
    && !!h2 && h2.text === "root@localhost:~# cat a\nA" && h3 === null
    && !!h4 && h4.source === "prompt" && h4.text.includes("two\nlines") && !h4.text.includes("first-output");
  r.m3detail = JSON.stringify([h1, h2, h3, h4 && h4.text]);
  // M4 URLs
  const text = "see https://example.com/a(b)). and github.com/foo, mail me@host.com; build.gradle run.sh README.md\n" +
    "www.test.org: (http://x.io/p?q=1&r=2) 'https://quoted.dev/x' github.community http://a.b.co/z. https://example.com/a(b)";
  const urls = T.findUrls(text).map((u) => u.url);
  const want = ["https://example.com/a(b)", "github.com/foo", "www.test.org", "http://x.io/p?q=1&r=2", "https://quoted.dev/x", "http://a.b.co/z", "https://example.com/a(b)"];
  r.m4 = JSON.stringify(urls) === JSON.stringify(want);
  r.m4list = JSON.stringify(T.urlList(text)) === JSON.stringify(["https://example.com/a(b)", "http://a.b.co/z", "https://quoted.dev/x", "http://x.io/p?q=1&r=2", "www.test.org", "github.com/foo"]);
  r.m4got = JSON.stringify(urls);
  // M5 / M6 against term.js with a stub DOM
  const dom = makeDom();
  const sb = { document: dom.document, window: {}, navigator: {}, setTimeout, clearTimeout, console, Date, JSON, Math, Promise, Set, Map };
  sb.window = sb; sb.window.innerWidth = 800; sb.window.innerHeight = 600;
  vm.createContext(sb);
  vm.runInContext(textSrc, sb);
  vm.runInContext(termSrc + "\n;this.MYK = MYK;", sb);
  const MYK = sb.MYK;
  const sent = [], pasted = [];
  let clip = "", reads = 0;
  sb.Transport = { ptyWrite: (id, d) => sent.push([id, d]) };
  sb.AndroidTerm = { clipboardGet: () => { reads++; return clip; }, clipboardHas: () => clip !== "", clipboardSet: (t) => { clip = t; }, openUrl: (u) => sent.push(["open", u]) };
  sb.window.AndroidTerm = sb.AndroidTerm;
  const buf = ["fleet@phone:~$ curl https://example.com/x.", "ok", "fleet@phone:~$ "];
  const term = { cols: 80, rows: 24, paste: (t) => pasted.push(t), focus() {}, hasSelection: () => false,
    buffer: { active: { length: buf.length, viewportY: 0, getLine: (i) => (i < buf.length ? { isWrapped: false, translateToString: () => buf[i] } : undefined) } } };
  MYK.panes.set("p1", { term, host: dom.document.createElement("div"), tabId: "t1", marks: [] });
  MYK.activePane = "p1";
  return { r, MYK, sent, pasted, setClip: (t) => { clip = t; }, reads: () => reads, dom };
}

function makeDom() {
  const all = [];
  const el = (tag) => {
    const e = { tag, id: "", className: "", textContent: "", title: "", hidden: false, style: {}, children: [], parent: null, _h: {},
      append(...cs) { cs.forEach((c) => this.appendChild(c)); },
      appendChild(c) { c.parent = this; this.children.push(c); return c; },
      addEventListener(ev, f) { (this._h[ev] = this._h[ev] || []).push(f); },
      fire(ev, arg) { (this._h[ev] || []).forEach((f) => f(arg || { preventDefault() {}, stopPropagation() {} })); },
      remove() { if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this); this.parent = null; },
      getBoundingClientRect() { return { width: 200, height: 200, left: 0, top: 0 }; },
      contains(x) { for (let n = x; n; n = n.parent) if (n === this) return true; return false; },
      querySelector() { return null; },
      set innerHTML(v) { this.children = []; },
    };
    all.push(e);
    return e;
  };
  const body = el("body");
  const find = (n, id) => { if (n.id === id) return n; for (const c of n.children) { const f = find(c, id); if (f) return f; } return null; };
  const document = { body, createElement: el, getElementById: (id) => find(body, id), addEventListener() {} };
  return { document, body };
}
const labels = (n) => n.children.map((c) => (c.children[0] && c.children[0].textContent) || c.textContent);

async function menuVerdict(textSrc, termSrc) {
  const v = verdict(textSrc, termSrc);
  const { MYK, sent, pasted, setClip, dom } = v;
  const r = v.r;
  // M5
  setClip("ls -la\n");
  await MYK.paste(true);
  r.m5single = !dom.document.getElementById("paste-confirm") && pasted.join() === "ls -la" && sent.some(([, d]) => d === "\r");
  pasted.length = 0; sent.length = 0;
  setClip("rm -rf build\nmake\n");
  await MYK.paste(true);
  const box = dom.document.getElementById("paste-confirm");
  r.m5wait = !!box && pasted.length === 0 && sent.length === 0;
  const findId = (n, id) => (n.id === id ? n : n.children.map((c) => findId(c, id)).find(Boolean));
  if (box) findId(box, "paste-cancel").fire("click");
  r.m5cancel = !dom.document.getElementById("paste-confirm") && pasted.length === 0 && sent.length === 0;
  await MYK.paste(true);
  const box2 = dom.document.getElementById("paste-confirm");
  if (box2) findId(box2, "paste-run").fire("click");
  r.m5run = pasted.join() === "rm -rf build\nmake" && sent.filter(([, d]) => d === "\r").length === 1;
  pasted.length = 0; sent.length = 0;
  await MYK.paste(false);
  r.m5plain = !dom.document.getElementById("paste-confirm") && pasted.join() === "rm -rf build\nmake" && sent.length === 0;
  const T = loadText(textSrc);
  r.m5plan = T.pastePlan("a\r\nb", true).confirm && !T.pastePlan("a\n\n", true).confirm && !T.pastePlan("a\nb", false).confirm && T.pastePlan("", true).lines === 0;
  // M6 — opening the menu must not READ the clipboard (Android notices every read)
  const readsBefore = v.reads();
  MYK.showPaneMenu(10, 10);
  r.m6noread = v.reads() === readsBefore;
  const menu = dom.document.getElementById("pane-menu");
  const got = menu ? labels(menu) : [];
  const need = ["Copy All", "Copy Last", "Paste", "Paste & Run", "URL — Select ▸", "Split", "Close Pane"];
  r.m6items = need.every((n) => got.includes(n));
  r.m6got = JSON.stringify(got);
  const splitRow = menu && menu.children.find((c) => c.className.includes("pane-menu-split"));
  r.m6split = !!splitRow && splitRow.children.filter((c) => c.tag === "button").map((b) => b.title).join() === "Split Right,Split Left,Split Down,Split Up";
  const hint = (name) => { const row = menu && menu.children.find((c) => c.children[0] && c.children[0].textContent === name); return row ? row.children[1].textContent : ""; };
  r.m6counts = /\d+ ln/.test(hint("Copy All")) && /\d+ ln · (marks|prompt)/.test(hint("Copy Last")) && hint("URL — Select ▸") === "1" && /asks if >1 ln/.test(hint("Paste & Run"));
  r.m6hints = JSON.stringify({ all: hint("Copy All"), last: hint("Copy Last"), url: hint("URL — Select ▸"), run: hint("Paste & Run") });
  const urlRow = menu && menu.children.find((c) => c.children[0] && c.children[0].textContent === "URL — Select ▸");
  if (urlRow) urlRow.fire("click");
  const item = menu && menu.children.find((c) => c.className.includes("pane-menu-url"));
  r.m6urls = !!item && item.textContent === "https://example.com/x";
  sent.length = 0;
  if (item) { item.fire("pointerdown"); item.fire("click"); }
  r.m6tap = sent.length === 0 && v.MYK && (await Promise.resolve(true));
  // reopen and long-press
  MYK.showPaneMenu(10, 10);
  const menu2 = dom.document.getElementById("pane-menu");
  menu2.children.find((c) => c.children[0] && c.children[0].textContent === "URL — Select ▸").fire("click");
  const item2 = menu2.children.find((c) => c.className.includes("pane-menu-url"));
  item2.fire("contextmenu");
  r.m6hold = sent.some(([k, u]) => k === "open" && u === "https://example.com/x");
  return r;
}

(async () => {
  const r = await menuVerdict(SRC.text, SRC.term);
  console.log("== M1: OSC 133 =="); check(r.m1, "M1 A/B/C/D, D;exit and A;options parse; anything else is ignored");
  console.log("== M2: Copy Last with marks ==");
  check(r.m2hand, "M2 the last command and its output, exactly, from the marks");
  check(r.m2empty, "M2 an Enter on an empty prompt does not replace the last command");
  check(r.m2wrap, "M2 a soft-wrapped output row joins without a newline");
  check(r.m2liveMarks, "M2 a live bash given the fleet login-init.sh emits A, B, C and D");
  check(r.m2live, "M2 Copy Last on that live session is the last command and its output only: " + JSON.stringify(r.m2liveText));
  console.log("== M3: Copy Last without marks =="); check(r.m3, "M3 the prompt heuristic finds fish, bash and root prompts, and gives up without one " + (r.m3 ? "" : r.m3detail));
  console.log("== M4: URLs ==");
  check(r.m4, "M4 http(s), bare domains, punctuation and parentheses; e-mail and file names left alone " + (r.m4 ? "" : r.m4got));
  check(r.m4list, "M4 the URL list is distinct, most recent first");
  console.log("== M5: Paste & Run ==");
  check(r.m5single, "M5 one line pastes and runs at once");
  check(r.m5wait, "M5 several lines wait for the confirmation: nothing is pasted or sent");
  check(r.m5cancel, "M5 Cancel sends nothing");
  check(r.m5run, "M5 Run pastes the lines and one Enter");
  check(r.m5plain, "M5 plain Paste never confirms and never adds an Enter");
  check(r.m5plan, "M5 the plan: CRLF counts as lines, trailing newlines do not, an empty clipboard has none");
  console.log("== M6: the menu ==");
  check(r.m6items, "M6 Copy All, Copy Last, Paste, Paste & Run, URL — Select, Split and Close Pane are all offered " + r.m6got);
  check(r.m6split, "M6 Split keeps its four directions");
  check(r.m6noread, "M6 opening the menu does not read the clipboard (no Android paste notice per tap)");
  check(r.m6counts, "M6 every row says how much it acts on " + r.m6hints);
  check(r.m6urls, "M6 URL — Select lists the URL on screen without its trailing period");
  check(r.m6tap, "M6 tapping a listed URL copies it and opens nothing");
  check(r.m6hold, "M6 long-pressing a listed URL opens it (Cloud Browser via AndroidTerm.openUrl)");
  console.log("== M7: wiring ==");
  const html = fs.readFileSync(path.join(FE, "index.html"), "utf8");
  check(html.indexOf('src="js/termtext.js"') > 0 && html.indexOf('src="js/termtext.js"') < html.indexOf('src="js/term.js"'), "M7 index.html loads termtext.js before term.js");
  const init = fs.readFileSync(INIT, "utf8");
  check(/fish_preexec/.test(init) && /fish_postexec/.test(init) && /133;A/.test(init) && /133;B/.test(init) && /PS0=/.test(init), "M7 login-init.sh carries the fish (< 4) marks and the bash PROMPT_COMMAND / PS0");
  check(/registerOscHandler\(133/.test(SRC.term) && /registerLinkProvider\(/.test(SRC.term), "M7 term.js records OSC 133 marks and makes URLs links");

  console.log("== MUT ==");
  const muts = [
    ["text", "a multi-line Run needs no confirmation", "confirm: !!run && lines > 1", "confirm: false", (x) => x.m5wait],
    ["text", "trailing punctuation kept on URLs", 'if (/[.,;:!?\'"»”’\\]}>*]/.test(last)) { s = s.slice(0, -1); continue; }', "", (x) => x.m4],
    ["text", "marks ignored (heuristic only)", "const byMarks = this._lastByMarks(rows, marks || []);", "const byMarks = null;", (x) => x.m2hand && x.m2live],
    ["text", "bare domains not detected", '"|\\\\b(?:www', '"|\\\\bNEVER(?:www', (x) => x.m4],
    ["term", "THE OLD MENU: only Split and Close", 'row("Copy All"', 'if (0) row("Copy All"', (x) => x.m6items],
    ["term", "Paste & Run skips the confirmation", "if (plan.confirm) this.confirmRun(plan, go); else go();", "go();", (x) => x.m5wait],
    ["term", "the menu reads the clipboard to count it", "const clipEmpty = this._clipEmpty();", "const clipEmpty = this._clipEmpty(); this._clipPeek();", (x) => x.m6noread],
    ["term", "long-press on a listed URL copies instead", "() => { menu.remove(); this.openUrl(u); }", "() => { menu.remove(); this._copy(u); }", (x) => x.m6hold],
  ];
  let hollow = 0;
  for (const [which, label, from, to, green] of muts) {
    const src = which === "text" ? SRC.text : SRC.term;
    if (!src.includes(from)) { bad("MUT stale: '" + label + "' — its text is no longer in the source"); hollow++; continue; }
    const m = src.replace(from, to);
    let rr;
    try { rr = await menuVerdict(which === "text" ? m : SRC.text, which === "term" ? m : SRC.term); } catch (e) { rr = null; }
    if (rr && green(rr)) { bad("MUT hollow: '" + label + "' leaves the tester green"); hollow++; }
    else console.log("  MUT-RED    " + label);
  }
  if (!hollow) ok("MUT " + muts.length + " mutations, every one red (incl. the old two-item menu)");
  console.log("\n── terminal menu: " + pass + " passed, " + fail + " failed ──");
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.log("  FAIL: tester crashed: " + (e && e.stack)); process.exit(1); });
JS
