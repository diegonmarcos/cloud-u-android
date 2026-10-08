#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-mail HTML FIT-TO-WIDTH - the engine half and the page half, pinned ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. "Every message must fit the width" was fixed (16f7c90a3, 46895c1da) and then
# reported broken AGAIN. The page-side rules (FIT_CSS) were guarded by test-mail-html.sh H12 and
# ReaderFitDocumentTest, but the ENGINE half - the WebView settings that make wide HTML fit - was
# not guarded at all (H12 even says "any check for those passes AGAINST the bug"), and the viewport
# meta carried `initial-scale=1`, which pins the first zoom and switches loadWithOverviewMode off:
# the one safety net for a message the CSS cannot narrow. Both halves are pinned here, each with a
# mutation that must turn it red.
#
#   F1  applyFitSettings(): useWideViewPort + loadWithOverviewMode + setInitialScale(0)
#   F2  the body WebView factory CALLS it (a function nobody calls fits nothing)
#   F3  ONE viewport meta (FIT_VIEWPORT_META = width=device-width, no scale pin) in BOTH templates,
#       and no other viewport meta / initial-scale anywhere in main code
#   F4  FIT_CSS: every element, the roots, aspect ratio, nowrap cells, breakable tokens, <pre>
#       - all !important - and BOTH templates interpolate it
#   F5  the wide fixtures (600 / 800 px newsletters, 3000 px image, the 1200 px table) exist
#   F6  the executed unit tests exist and are named
#   M   mutation-proof: each defence removed in a COPY turns its check red; the real tree is green
#
# OWN-SOURCE ONLY. python3 and grep only; no gradle, no device.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCREEN="$APP/app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
MAIN="$APP/app/src/main/kotlin"
RES="$APP/app/src/test/resources/fit"
TESTS="$APP/app/src/test/kotlin/app/sterna/ui/message/ReaderFitDocumentTest.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
[ -f "$SCREEN" ] || { echo "ERROR missing $SCREEN - this tester is unrun, not passing"; exit 1; }

# code lines only: comments carry prose about the very strings we police
code() { python3 - "$1" <<'PY'
import sys
for l in open(sys.argv[1], encoding="utf-8"):
    s = l.strip()
    if s.startswith(("//", "*", "/*")): continue
    print(l, end="")
PY
}

f1() { # <MessageScreen.kt>
  python3 - "$1" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"internal fun WebView\.applyFitSettings\(\) \{(.*?)\n\}", t, re.S)
bad = []
if not m: bad.append("applyFitSettings() is gone")
else:
    b = m.group(1)
    for need in ("settings.useWideViewPort = true", "settings.loadWithOverviewMode = true", "setInitialScale(0)"):
        if need not in b: bad.append("applyFitSettings() lacks `%s`" % need)
for banned in ("loadWithOverviewMode = false", "useWideViewPort = false"):
    if banned in t: bad.append("`%s` undoes the fit" % banned)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
f2() { # the factory of the body WebView calls it
  python3 - "$1" <<'PY'
import re, sys
t = "".join(l for l in open(sys.argv[1], encoding="utf-8") if not l.strip().startswith(("//", "*", "/*")))
t = re.sub(r"fun WebView\.applyFitSettings\(\)", "", t)
calls = len(re.findall(r"\bapplyFitSettings\(\)", t))
ok = calls == 1 and re.search(r"BodyWebView\(ctx\)\.apply \{.*?applyFitSettings\(\)", t, re.S)
if not ok: print("    applyFitSettings() is called %d time(s) and not from the BodyWebView factory" % calls)
sys.exit(0 if ok else 1)
PY
}
f3() { # <MessageScreen.kt> <main dir>
  python3 - "$1" "$2" <<'PY'
import os, re, sys
screen = open(sys.argv[1], encoding="utf-8").read()
bad = []
m = re.search(r'internal const val FIT_VIEWPORT_META = """(.*?)"""', screen, re.S)
if not m or m.group(1) != '<meta name="viewport" content="width=device-width">':
    bad.append("FIT_VIEWPORT_META is not exactly width=device-width: %r" % (m.group(1) if m else None))
code = "".join(l for l in screen.splitlines(True) if not l.strip().startswith(("//", "*", "/*")))
if code.count("$FIT_VIEWPORT_META") != 2: bad.append("%d of the 2 templates use FIT_VIEWPORT_META" % code.count("$FIT_VIEWPORT_META"))
for d, _, fs in os.walk(sys.argv[2]):
    for f in fs:
        if not f.endswith(".kt"): continue
        p = os.path.join(d, f)
        for i, l in enumerate(open(p, encoding="utf-8"), 1):
            if l.strip().startswith(("//", "*", "/*")): continue
            if "initial-scale" in l or "user-scalable" in l or "maximum-scale" in l:
                bad.append("%s:%d pins the scale (%s) - that switches loadWithOverviewMode off or kills pinch" % (os.path.relpath(p, sys.argv[2]), i, l.strip()[:60]))
            if 'name="viewport"' in l and "FIT_VIEWPORT_META =" not in l:
                bad.append("%s:%d declares a viewport meta of its own" % (os.path.relpath(p, sys.argv[2]), i))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
f4() { # <MessageScreen.kt>
  python3 - "$1" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'internal const val FIT_CSS = """(.*?)"""', t, re.S)
bad = []
if not m: print("    FIT_CSS is gone"); sys.exit(1)
css = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)
for rule in ("body * { max-width: 100% !important; min-width: 0 !important; }",
             "html, body { max-width: 100% !important; min-width: 0 !important; }",
             "img, video, svg, canvas { height: auto !important; }",
             "td, th { white-space: normal !important; }",
             "word-break: break-word !important;",
             "pre, code { white-space: pre-wrap !important;"):
    if rule not in css: bad.append("FIT_CSS lacks `%s`" % rule)
for l in css.splitlines():
    if ("max-width:" in l or "min-width:" in l) and "!important" not in l: bad.append("not !important, so an inline style beats it: %s" % l.strip())
code = "".join(l for l in t.splitlines(True) if not l.strip().startswith(("//", "*", "/*")))
n = len(re.findall(r"^ *\$FIT_CSS$", code, re.M))
if n != 2: bad.append("%d of the 2 templates interpolate FIT_CSS" % n)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

echo "── F1 the WebView fit settings ──"
f1 "$SCREEN" && pass "useWideViewPort + loadWithOverviewMode + setInitialScale(0), none disabled" || fail "applyFitSettings() is incomplete"
echo "── F2 the body WebView is built through it ──"
f2 "$SCREEN" && pass "the BodyWebView factory calls applyFitSettings() exactly once" || fail "applyFitSettings() is not wired into the factory"
echo "── F3 the viewport meta ──"
f3 "$SCREEN" "$MAIN" && pass "one width=device-width meta in both templates; no scale pinned anywhere" || fail "the viewport meta pins the scale, differs, or is duplicated"
echo "── F4 the page-side fit rules ──"
f4 "$SCREEN" && pass "every rule present and !important, in both templates" || fail "a page-side fit rule is missing or weak"
echo "── F5 the wide fixtures ──"
ok5=1
for pair in "newsletter-600.html:width=\"600\"" "newsletter-600.html:min-width:600px" "newsletter-800.html:width:800px" \
            "newsletter-800.html:width=\"3000\"" "wide-email.html:width=\"1200\"" "wide-email.html:width=\"2000\""; do
  f="${pair%%:*}"; s="${pair#*:}"
  grep -qF -- "$s" "$RES/$f" 2>/dev/null || { fail "fixture $f no longer carries $s"; ok5=0; }
done
[ $ok5 -eq 1 ] && pass "600 / 800 px newsletters, a 3000 px image and a 1200 px table are all in the fixtures"
echo "── F6 the executed tests ──"
n=$(grep -c '@Test' "$TESTS" 2>/dev/null || echo 0)
grep -q 'both templates declare width=device-width and do not pin the initial scale' "$TESTS" \
  && grep -q 'the reader WebView is built with overview mode and the wide viewport' "$TESTS" \
  && grep -q 'wide newsletter fixtures reach the page' "$TESTS" \
  && pass "ReaderFitDocumentTest carries the engine-half tests ($n tests)" || fail "ReaderFitDocumentTest lost an engine-half test"

echo "── M mutation-proof ──"
T="$(mktemp -d)"; trap 'rm -rf "${T:?}"' EXIT
mut() { # <label> <python replace expr: old> <new> <check fn> [args after screen]
  local label="$1" old="$2" new="$3" check="$4"
  mkdir -p "$T/main"; rm -rf "$T/main"; cp -r "$MAIN" "$T/main"
  local f="$T/main/app/sterna/ui/message/MessageScreen.kt"
  python3 - "$f" "$old" "$new" <<'PY'
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if old not in s: print("MUTATION-NOT-APPLIED"); sys.exit(3)
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
  local rc=$?
  [ $rc -eq 0 ] || { fail "M: $label - the mutation did not apply (tester is stale)"; return; }
  if [ "$check" = f3 ]; then f3 "$f" "$T/main" >/dev/null && fail "M: $label passed $check" || pass "M: $label -> $check RED"
  else "$check" "$f" >/dev/null && fail "M: $label passed $check" || pass "M: $label -> $check RED"; fi
}
mut "overview mode dropped"          "settings.loadWithOverviewMode = true" "settings.loadWithOverviewMode = false" f1
mut "wide viewport dropped"          "    settings.useWideViewPort = true" "    // gone" f1
mut "initial scale pin handed back"  "    setInitialScale(0)" "    setInitialScale(100)" f1
mut "applyFitSettings never called"  "                applyFitSettings()" "                // not called" f2
mut "initial-scale=1 back in meta"   'content="width=device-width">"""' 'content="width=device-width, initial-scale=1">"""' f3
mut "html/body roots uncapped"       "              html, body { max-width: 100% !important; min-width: 0 !important; }" "" f4
mut "max-width loses !important"     "body * { max-width: 100% !important; min-width: 0 !important; }" "body * { max-width: 100%; min-width: 0 !important; }" f4
mut "nowrap cells allowed again"     "td, th { white-space: normal !important; }" "" f4
f1 "$SCREEN" >/dev/null && f2 "$SCREEN" >/dev/null && f3 "$SCREEN" "$MAIN" >/dev/null && f4 "$SCREEN" >/dev/null \
  && pass "M: the unmutated tree is still green" || fail "M: the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mail-fit: all checks passed"; else echo "test-mail-fit: $FAILURES check(s) FAILED"; fi
exit "$FAILURES"
