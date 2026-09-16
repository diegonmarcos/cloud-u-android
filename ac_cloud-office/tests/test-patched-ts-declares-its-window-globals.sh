#!/usr/bin/env bash
# EVERY window.<X> OUR PATCHED TYPESCRIPT TOUCHES MUST BE DECLARED TO TYPESCRIPT.
#
# Run 35041370319 died in the COOL release bundle build with
#
#   src/control/Control.Menubar.ts(2535,15): error TS2339: Property
#   'COOLMessageHandler' does not exist on type 'Window & typeof globalThis'.
#
# The CALL was right: LOActivity registers the bridge with
# addJavascriptInterface(this, "COOLMessageHandler") and its
# postMobileMessage(String) is @JavascriptInterface, so the object is real at
# runtime. The TYPE was missing. browser/ had only ever reached that object from
# plain JavaScript, which upstream's build does not type-check (tsc runs
# --checkJs false), so patches/0001 was its first TypeScript caller and nothing
# in the tree declared it.
#
# NOTHING IN THIS REPOSITORY COULD HAVE CAUGHT THAT. The patched TypeScript
# exists only inside a patch file; it is compiled only after a ~2 GB Gerrit
# fetch; and both upstream linters pass on it — measured, not assumed: prettier
# never even looks at Control.Menubar.ts, which is listed in
# browser/.beforeprettier, and eslint exits 0 on all three patched browser files.
# This is the same shape as test-patched-java-calls-kotlin-correctly.sh one
# layer up, and it is the SECOND patch we wrote that reached CI uncompiled.
#
# THE RULE, read from build.json::patched_typescript. For every `window.<X>`
# our series ADDS to a .ts/.tsx file, one of these must hold:
#   (a) the POST-PATCH file declares <X> inside an `interface Window` block, or
#   (b) UPSTREAM's own copy of that file already reads `window.<X>`, in which
#       case upstream types it somewhere and tsc already accepts it.
# Anything else is a property reaching TypeScript for the first time through us,
# undeclared, and it stops the release bundle hours into a run.
#
# The post-patch file is built by APPLYING the series to the file fetched from
# Gerrit, not by grepping the patch: a rule that reasoned about `+` lines alone
# could not tell a declaration our patch adds from one it merely moves.
#
# COMMENT LINES ARE STRIPPED BEFORE MATCHING, on both sides. A `//` comment that
# mentions window.COOLMessageHandler must neither create an obligation nor
# satisfy one — patches/0001's own comment names it.
#
# Usage: ./test-patched-ts-declares-its-window-globals.sh   (needs network: Gerrit REST)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"
PATCH_DIR="${CLOUD_OFFICE_PATCH_DIR:-$APP/patches}"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo -e "  \033[0;32mok\033[0m: $1"; }
bad() { FAIL=$((FAIL+1)); echo -e "  \033[0;31mFAIL\033[0m: $1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

for t in curl jq base64 sed grep git python3; do
    command -v "$t" >/dev/null 2>&1 || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"

OBJ="$(jq -r '.patched_typescript.globals_object     // empty' "$BJ")"
DECL="$(jq -r '.patched_typescript.declaration_keyword // empty' "$BJ")"
[ -n "$OBJ" ] && [ -n "$DECL" ] \
    || die "build.json::patched_typescript is incomplete — an empty rule must not report green"

URL="$(jq -r '.upstream.online.url      // empty' "$BJ")"
PIN="$(jq -r '.upstream.online.revision // empty' "$BJ")"
[ "${#PIN}" -eq 40 ] || die "upstream.online.revision must be a full 40-char sha, got '${PIN}'"
BASE="${URL%/*}"; PROJECT="${URL##*/}"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cool-ts-globals.XXXXXX")" || die "cannot mktemp"
trap 'rm -rf "$TMP"' EXIT

fetch() {  # fetch <path> <dest>; 3 = absent, 4 = unreadable. Never a verdict.
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

# Which pre-existing .ts/.tsx files does the series edit?
mapfile -t TSFILES < <(grep -h '^--- a/' "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch \
                       | sed 's|^--- a/||' | grep -E '\.tsx?$' | sort -u)
# FAIL CLOSED. A series that edits no TypeScript would run the loop zero times
# and exit 0 — a green tick for a rule that examined nothing.
[ "${#TSFILES[@]}" -ge 1 ] \
    || die "the patch series edits no .ts/.tsx file — this rule is about patched TypeScript and must not pass on none"

echo "== T1: the pinned tree is reachable and this run read it =="
for f in "${TSFILES[@]}"; do
    case "$(fetch "$f" "$TMP/$(echo "$f" | tr / _).orig"; echo $?)" in
        0) ok "read $f out of $PROJECT at $PIN" ;;
        3) die "$f is ABSENT at $PIN — the series edits a file that is gone; re-pin" ;;
        *) die "cannot read $f from $BASE — UNKNOWN, not a verdict" ;;
    esac
done

echo "== T2: build the POST-PATCH tree by applying the series for real =="
WORK="$TMP/work"; mkdir -p "$WORK"; ( cd "$WORK" && git init -q . )
while read -r f; do
    mkdir -p "$WORK/$(dirname "$f")"
    fetch "$f" "$WORK/$f" || die "cannot read $f from $BASE — UNKNOWN, not a verdict"
done < <(grep -h '^--- a/' "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch | sed 's|^--- a/||' | sort -u)
( cd "$WORK" && git add -A && git -c user.email=a@b -c user.name=a commit -qm base ) >/dev/null
applied=0
for p in "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch; do
    if ( cd "$WORK" && git -c user.name=t -c user.email=t@t am -q --keep-non-patch "$p" ) >/dev/null 2>&1; then
        applied=$((applied+1))
    else
        ( cd "$WORK" && git am --abort >/dev/null 2>&1 )
        bad "$(basename "$p") does not apply to $PIN — no post-patch tree, so nothing below was checked"
        echo; echo "passed $PASS, failed $FAIL"; exit 1
    fi
done
ok "applied $applied patch(es) to the pinned tree"

echo "== T3: every window.<X> our series adds is declared =="
python3 - "$WORK" "$OBJ" "$DECL" "$PATCH_DIR" "${TSFILES[@]}" <<'PY'
import glob, os, re, sys
work, obj, decl, patch_dir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
tsfiles = sys.argv[5:]

GREEN, RED, OFF = "\033[0;32m", "\033[0;31m", "\033[0m"
passed = failed = 0
def ok(m):
    global passed; passed += 1; print(f"  {GREEN}ok{OFF}: {m}")
def bad(m):
    global failed; failed += 1; print(f"  {RED}FAIL{OFF}: {m}")

# `//`-only stripping. Not a general comment parser: a trailing comment on a
# real statement still leaves the statement, which is what we want.
strip = lambda t: "\n".join(re.sub(r"\s*//.*$", "", ln) for ln in t.splitlines())

# window.<X> our series ADDS to a .ts/.tsx file, comments excluded.
added = []
for p in sorted(glob.glob(os.path.join(patch_dir, "[0-9][0-9][0-9][0-9]-*.patch"))):
    cur = None
    for ln in open(p, encoding="utf-8", errors="replace"):
        if ln.startswith("+++ b/"):
            cur = ln[6:].strip()
        elif ln.startswith("+") and not ln.startswith("+++") and cur and cur.endswith((".ts", ".tsx")):
            added.append((cur, ln[1:]))
refs = {}
for f, ln in added:
    for m in re.finditer(rf"\b{re.escape(obj)}\.([A-Za-z_$][A-Za-z0-9_$]*)", strip(ln)):
        refs.setdefault(m.group(1), set()).add(f)

if not refs:
    sys.exit(f"ERROR: the series adds no {obj}.<property> reference in any .ts/.tsx — "
             "refusing to report green for a rule that found nothing to check")

# Members of every `interface Window { ... }` block in the POST-PATCH files.
declared = set()
for f in tsfiles:
    text = strip(open(os.path.join(work, f), encoding="utf-8", errors="replace").read())
    for m in re.finditer(re.escape(decl) + r"\s*\{", text):
        i, depth = m.end() - 1, 0
        for j in range(i, len(text)):
            if text[j] == "{": depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    for d in re.finditer(r"^\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*[?:(]", text[i:j], re.M):
                        declared.add(d.group(1))
                    break

for prop in sorted(refs):
    if prop in declared:
        ok(f"{obj}.{prop}: declared in an `{decl}` block of the post-patch tree")
        continue
    # Upstream already reads it from this same TypeScript file, so it is typed
    # somewhere upstream and tsc already accepts it.
    upstream_uses = False
    for f in refs[prop]:
        orig = os.path.join(os.path.dirname(work), f.replace("/", "_") + ".orig")
        if os.path.exists(orig):
            if re.search(rf"\b{re.escape(obj)}\.{re.escape(prop)}\b",
                         strip(open(orig, encoding="utf-8", errors="replace").read())):
                upstream_uses = True
                ok(f"{obj}.{prop}: upstream's own {f} already reads it, so it is typed upstream")
                break
    if not upstream_uses:
        bad(f"{obj}.{prop}: reaches TypeScript for the first time through "
            f"{sorted(refs[prop])} and NOTHING declares it — tsc will stop the "
            f"COOL release bundle with TS2339, hours into a run")

print(f"\n  {len(refs)} {obj}.<property> reference(s) added by the series")
sys.exit(1 if failed else 0)
PY
rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -eq 0 ] || FAIL=$((FAIL+1))

# ── self-test: delete the declaration and require a red ───────────────
if [ -z "${CLOUD_OFFICE_TSGLOBALS_SELFTEST:-}" ]; then
    echo "== T4: a series that uses an undeclared window global must FAIL this tester =="
    BROKEN="$TMP/broken"; mkdir -p "$BROKEN/patches"
    cp "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch "$BROKEN/patches/"
    # Strip the declaration block from whichever patch adds it, keeping the
    # hunk headers honest by regenerating nothing — git am is not used on the
    # broken copy for anything but this tester's own T2.
    python3 - "$BROKEN/patches" "$DECL" <<'PY'
import glob, os, re, sys
d, decl = sys.argv[1], sys.argv[2]
for p in glob.glob(os.path.join(d, "[0-9][0-9][0-9][0-9]-*.patch")):
    lines = open(p, encoding="utf-8", errors="replace").read().split("\n")
    out, drop = [], False
    for ln in lines:
        if ln.startswith("+") and decl in ln and "{" in ln:
            drop = True
            continue
        if drop:
            if ln.startswith("+") and ln.strip() in ("+}", "+};"):
                drop = False
            continue
        out.append(ln)
    open(p, "w", encoding="utf-8").write("\n".join(out))
PY
    if CLOUD_OFFICE_TSGLOBALS_SELFTEST=1 CLOUD_OFFICE_PATCH_DIR="$BROKEN/patches" \
       "$0" >/dev/null 2>&1; then
        bad "self-test: a series with the \`$DECL\` declaration deleted still PASSED — this tester asserts nothing"
    else
        ok "self-test: deleting the \`$DECL\` declaration turns this tester red"
    fi
fi

echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
