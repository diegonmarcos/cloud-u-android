#!/usr/bin/env bash
# SELECTION -> ENHANCE -> REINSERT, DRIVEN AS FAR AS A HEADLESS RUNNER CAN DRIVE IT.
#
# #236 asks for the canvas text-input path working end to end, and rep-S said
# plainly what was missing: "Text Enhance itself is unverified end to end. It
# compiles and packages; no test has selected text in a document and watched a
# rewrite come back." This is that test, to the limit of what exists here.
#
# WHAT THIS RUNS FOR REAL. The browser half is plain JavaScript, so it is
# EXECUTED, not grepped: the three hunks patches/0001 adds to browser/ are cut
# verbatim out of the POST-PATCH tree (Gerrit fetch + git am, not the patch's '+'
# lines) and run under node against stubs for the map, the socket and the
# WebView bridge. That covers legs 1 and 2 of the round trip:
#
#   leg 1  the menu row asks core for the selection, with the one-shot flag set
#   leg 2  core's answer is handed to the Android bridge and the REAL CLIPBOARD
#          IS NEVER TOUCHED — the property that makes an enhance safe to run
#          while the owner has something else copied
#
# WHAT THIS CANNOT RUN, AND SAYS SO. There is no JVM and no device in this
# container (`command -v java` is empty), so leg 3 — LOActivity.paste() putting
# the rewrite back through core — is checked STATICALLY: that the mime asked of
# core is the mime pasted back, that CLOUD_TEXT_ENHANCE is routed and never
# forwarded to postMobileMessageNative, and that every ITextTools symbol the Java
# calls exists with that exact shape in the Kotlin it is calling. That last one
# is not pedantry: 29e738af9 burnt a run because Java cannot read a Kotlin `val`
# as a field, and this app has now shipped THREE defects that were green in CI.
#
# STILL RUNTIME-ONLY AFTER THIS PASSES: the binder actually connecting to the
# Cloud Keyboard, a provider answering, and core accepting the paste. Those need
# two signed apps on one phone and nothing here can stand in for them.
#
# Usage: ./test-text-enhance-round-trip.sh              (needs network: Gerrit REST)
#        ./test-text-enhance-round-trip.sh --self-test  (also prove the failure path fails)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$APP/.." && pwd)"
BJ="$APP/build.json"
PATCH_DIR="${CLOUD_OFFICE_PATCH_DIR:-$APP/patches}"
SELFTEST=0
[ "${1:-}" = "--self-test" ] && SELFTEST=1

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  \033[0;32mok\033[0m: %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[0;31mFAIL\033[0m: %s\n' "$1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

for t in curl jq base64 sed grep git node python3; do
    command -v "$t" >/dev/null 2>&1 \
        || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"

URL="$(jq -r '.upstream.online.url      // empty' "$BJ")"
PIN="$(jq -r '.upstream.online.revision // empty' "$BJ")"
[ "${#PIN}" -eq 40 ] || die "upstream.online.revision must be a full 40-char sha, got '${PIN}'"
BASE="${URL%/*}"; PROJECT="${URL##*/}"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cool-enhance-trip.XXXXXX")" || die "cannot mktemp"
trap 'rm -rf "$TMP"' EXIT

fetch() {
    local enc; enc="$(printf '%s' "$1" | sed 's|/|%2F|g')"
    local code
    code="$(curl -sS --max-time 120 -o "$TMP/raw" -w '%{http_code}' \
            "$BASE/projects/$PROJECT/commits/$PIN/files/$enc/content" 2>/dev/null)" || return 4
    case "$code" in
        200) base64 -d <"$TMP/raw" >"$2" 2>/dev/null || return 4; [ -s "$2" ] || return 4; return 0 ;;
        404) return 3 ;;
        *)   return 4 ;;
    esac
}

echo "== T1: build the POST-PATCH tree by applying the series for real =="
WORK="$TMP/work"; mkdir -p "$WORK"; ( cd "$WORK" && git init -q . )
while read -r f; do
    mkdir -p "$WORK/$(dirname "$f")"
    case "$(fetch "$f" "$WORK/$f"; echo $?)" in
        0) ;;
        3) die "$f is ABSENT at $PIN — the series edits a file that is gone; re-pin" ;;
        *) die "cannot read $f from $BASE — UNKNOWN, not a verdict" ;;
    esac
done < <(grep -h '^--- a/' "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch | sed 's|^--- a/||' | sort -u)
( cd "$WORK" && git add -A && git -c user.email=a@b -c user.name=a commit -qm base ) >/dev/null
for p in "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch; do
    ( cd "$WORK" && git -c user.name=t -c user.email=t@t am -q --keep-non-patch "$p" ) >/dev/null 2>&1 || {
        ( cd "$WORK" && git am --abort >/dev/null 2>&1 )
        bad "$(basename "$p") does not apply to $PIN — nothing below was checked"
        echo; echo "== RESULT: $PASS passed, $FAIL failed =="; exit 1
    }
done
# patches/0001 also creates files that exist only in it (the Java half). git am
# wrote them into the work tree, so read them from there, not from Gerrit.
JAVA="$WORK/android/lib/src/main/java/org/libreoffice/androidlib/CloudTextEnhance.java"
LOACT="$WORK/android/lib/src/main/java/org/libreoffice/androidlib/LOActivity.java"
for f in "$JAVA" "$LOACT"; do
    [ -s "$f" ] || die "$(basename "$f") is missing from the post-patch tree — the series changed shape"
done
ok "applied the series; the post-patch tree has both browser and Android halves"

echo "== T2: RUN the browser half — leg 1 and leg 2, executed, not grepped =="
if node "$HERE/round-trip-harness.js" "$WORK"; then
    PASS=$((PASS+1))
else
    bad "the browser round-trip harness reported a failure (see above)"
fi

echo "== T3: leg 3 — the mime asked of core is the mime pasted back =="
ASKED="$(grep -o "gettextselection mimetype=[^']*" "$WORK/browser/src/control/Control.Menubar.ts" | head -1 | sed 's/^gettextselection mimetype=//')"
PASTED="$(grep -o 'MIME = "[^"]*"' "$JAVA" | head -1 | sed 's/^MIME = "//; s/"$//')"
[ -n "$ASKED" ]  || die "could not read the mimetype the menu asks core for — the call site changed shape"
[ -n "$PASTED" ] || die "could not read the mimetype CloudTextEnhance pastes — the constant changed shape"
if [ "$ASKED" = "$PASTED" ]; then
    ok "core is asked for '$ASKED' and the rewrite is pasted back as the same type"
else
    bad "core is asked for '$ASKED' but the rewrite is pasted as '$PASTED' — plain text in, something else out"
fi
grep -q 'activity\.paste(MIME' "$JAVA" \
    && ok "the rewrite goes back through LOActivity.paste(), i.e. core's own paste — ONE undo step" \
    || bad "CloudTextEnhance no longer calls activity.paste(MIME, …) — a rewrite that is not a core paste is not one undo"
grep -q 'public native void paste(String mimeType, byte\[\] data)' "$LOACT" \
    && ok "LOActivity still declares paste(String, byte[]) as a native method" \
    || bad "LOActivity.paste(String, byte[]) is gone or changed shape — the Java above would not compile"

echo "== T4: the bridge routes our message and never forwards it to core =="
MSG="$(grep -o 'MESSAGE = "[^"]*"' "$JAVA" | head -1 | sed 's/^MESSAGE = "//; s/"$//')"
[ -n "$MSG" ] || die "could not read CloudTextEnhance.MESSAGE — the constant changed shape"
grep -qF "'$MSG '" "$WORK/browser/src/layer/tile/CanvasTileLayer.js" \
    && ok "the browser posts '$MSG ' and the Java answers to the same name" \
    || bad "the browser does not post '$MSG ' — the two halves disagree on the message name"
# beforeMessageFromWebView must RETURN FALSE for our case: returning true forwards
# it to postMobileMessageNative, which has no such command and logs an error for
# every enhance.
if python3 - "$LOACT" "$MSG" <<'PY'
import re, sys
src, msg = open(sys.argv[1], encoding="utf-8").read(), sys.argv[2]
m = re.search(r"private boolean beforeMessageFromWebView\(.*?\n    \}", src, re.S)
if not m: sys.exit(2)
body = m.group(0)
c = re.search(r"case CloudTextEnhance\.MESSAGE:(.*?)(?=\n            case |\n        \})", body, re.S)
if not c: sys.exit(3)
sys.exit(0 if "return false;" in c.group(1) and "getCloudTextEnhance()" in c.group(1) else 1)
PY
then
    ok "beforeMessageFromWebView routes $MSG to CloudTextEnhance and returns false, so core never sees it"
else
    case $? in
      2) bad "beforeMessageFromWebView is not in LOActivity in the shape this rule reads — re-derive it" ;;
      3) bad "LOActivity has no 'case CloudTextEnhance.MESSAGE:' — the bridge does not route our message at all" ;;
      *) bad "the $MSG case does not both call getCloudTextEnhance() and return false — the selection is forwarded to core, which has no such command" ;;
    esac
fi

echo "== T5: every ITextTools symbol the Java calls exists in the Kotlin it calls =="
# READ OUT OF THE REAL MODULE, NOT FROM MEMORY. 29e738af9 exists because Java
# cannot read a Kotlin `val` as a field, and that cost a full run of this app.
TT_DIR="$REPO/$(jq -r '.build.modules."text-tools".dir' "$BJ" | sed 's|^\.\./||')"
[ -d "$TT_DIR" ] || die "the text-tools module is not at $TT_DIR — build.modules.\"text-tools\".dir is stale"
CLIENT="$TT_DIR/src/main/java/com/diegonmarcos/superapp/texttools/TextToolsClient.kt"
TOOLS="$TT_DIR/src/main/java/com/diegonmarcos/superapp/texttools/TextTools.kt"
for f in "$CLIENT" "$TOOLS"; do [ -f "$f" ] || die "$f missing — cannot verify the binder API"; done

# enhanceWith's arity is what the Java passes, counted in the Kotlin declaration.
KARITY="$(python3 - "$CLIENT" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"fun enhanceWith\((.*?)\)\s*:", src, re.S)
print(len([p for p in m.group(1).split(",") if p.strip()]) if m else -1)
PY
)"
JARITY="$(python3 - "$JAVA" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"client\.enhanceWith\((.*?)\);", src, re.S)
print(len([p for p in m.group(1).split(",") if p.strip()]) if m else -1)
PY
)"
if [ "$KARITY" -gt 0 ] && [ "$KARITY" = "$JARITY" ]; then
    ok "enhanceWith takes $KARITY arguments in the Kotlin and is called with $JARITY from the Java"
else
    bad "enhanceWith takes $KARITY arguments in the Kotlin but is called with $JARITY from the Java"
fi
# Kotlin `val ok` / `val text` / `val error` compile to getOk()/getText()/getError().
for pair in "ok:getOk" "text:getText" "error:getError"; do
    kval="${pair%%:*}"; jget="${pair#*:}"
    if grep -qE "val $kval\b" "$TOOLS"; then
        grep -q "result\.$jget()" "$JAVA" \
            && ok "Result.$kval is a Kotlin val and the Java reads it as $jget()" \
            || bad "Result.$kval is a Kotlin val but the Java does not call $jget() — a field read does not compile"
    else
        bad "TextTools.Result has no 'val $kval' — the Java's $jget() would not resolve"
    fi
done
for sym in "fun isServingAppInstalled" "const val NOT_INSTALLED"; do
    src="$CLIENT"; case "$sym" in "const val"*) src="$TOOLS";; esac
    grep -qF "$sym" "$src" \
        && ok "$(basename "$src") still declares: $sym" \
        || bad "$(basename "$src") no longer declares '$sym' — the Java calls it"
done

if [ "$SELFTEST" -eq 1 ]; then
    echo "== T6: SELF-TEST — break the clipboard guard and the harness must fail =="
    MUTTREE="$TMP/mutant"; cp -r "$WORK" "$MUTTREE"
    # Remove the early `return` that keeps an enhance off the real clipboard. The
    # code still runs; it just stops being safe. The harness must notice.
    python3 - "$MUTTREE/browser/src/layer/tile/CanvasTileLayer.js" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
s = s.replace("""\t\t\t\tif (window.COOLMessageHandler) {
\t\t\t\t\twindow.COOLMessageHandler.postMobileMessage(
\t\t\t\t\t\t'CLOUD_TEXT_ENHANCE ' + textMsgContent);
\t\t\t\t}
\t\t\t\treturn;""", """\t\t\t\tif (window.COOLMessageHandler) {
\t\t\t\t\twindow.COOLMessageHandler.postMobileMessage(
\t\t\t\t\t\t'CLOUD_TEXT_ENHANCE ' + textMsgContent);
\t\t\t\t}""", 1)
open(p, "w", encoding="utf-8").write(s)
PY
    if node "$HERE/round-trip-harness.js" "$MUTTREE" >/dev/null 2>&1; then
        bad "the mutant that lets an enhance fall through to the clipboard still PASSES — the harness proves nothing"
    else
        ok "the mutant without the early return fails the harness, as it must"
    fi
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
echo "NOT COVERED HERE, AND ONLY A DEVICE CAN COVER IT: the binder reaching the"
echo "Cloud Keyboard, a provider answering, and core accepting the paste."
[ "$FAIL" -eq 0 ]
