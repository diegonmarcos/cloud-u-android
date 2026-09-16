#!/usr/bin/env bash
# A MENU ENTRY THIS APP ADDS MUST BE IN THE MENU A PHONE ACTUALLY OPENS.
#
# Control.Menubar.ts carries TWO unrelated sets of menu definitions on one
# options object:
#
#   options.text / .presentation / .drawing / .spreadsheet
#       what _onRefresh() hands to _initializeMenu() for the DESKTOP menubar.
#   options.mobiletext / .mobilepresentation / .mobiledrawing / .mobilespreadsheet
#       what generateFullMenuStructure() reads — literally
#       this.options['mobile' + this._map.getDocType()] — to build the wizard
#       the TOP-RIGHT HAMBURGER opens, which on a phone is the only menu there is.
#
# patches/0001 added 'Text Enhance' and 'Settings' to options.text alone. That
# compiled, passed eslint, minified, packaged, signed and PUBLISHED — and could
# not be reached on any phone. #236 called Text Enhance "unverified end to end";
# it could not have been verified, because there was nothing to tap.
#
# MEASURED ON THE SHIPPED ARTEFACT, NOT ARGUED. Cloud-Office.apk from run
# 35043058309 (266,888,676 bytes) was range-read, assets/dist/bundle.js pulled
# out, and `cloudtextenhance` occurs in it EXACTLY ONCE — in the menu block that
# also holds '.uno:PasteSpecial', i.e. options.text. options.mobiletext's Edit
# menu has no PasteSpecial.
#
# NOTHING ELSE IN THIS REPOSITORY COULD CATCH THIS. Every other tester on the
# series asks whether the patch APPLIES or COMPILES. Both were true the whole
# time. This is the third defect in a row on this app that shipped green:
# Java-calling-Kotlin, then an undeclared window global, now a menu nobody reads.
#
# THE RULE, read from build.json::document_menu:
#   for every entry, the POST-PATCH Control.Menubar.ts must place its id inside
#   EVERY top-level options array the entry names, and at least one of those
#   must be a mobile one. Entries marked must_be_view_mode_action must also
#   appear in the allowedViewModeActions array, or a read-only document greys
#   them out.
#
# The post-patch file is BUILT, not grepped: the file is fetched from Gerrit at
# the pin and the series is applied with `git am`, because a rule that reasoned
# about '+' lines could not tell which array a hunk landed in — which is the
# exact distinction this tester exists to make.
#
# Usage: ./test-menu-entries-reach-the-mobile-app.sh              (needs network: Gerrit REST)
#        ./test-menu-entries-reach-the-mobile-app.sh --self-test  (also prove the failure path fails)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"
PATCH_DIR="${CLOUD_OFFICE_PATCH_DIR:-$APP/patches}"
SELFTEST=0
[ "${1:-}" = "--self-test" ] && SELFTEST=1

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  \033[0;32mok\033[0m: %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[0;31mFAIL\033[0m: %s\n' "$1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

for t in curl jq base64 sed grep git python3; do
    command -v "$t" >/dev/null 2>&1 \
        || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"

MENU_FILE="$(jq -r '.document_menu.file                 // empty' "$BJ")"
SELECTOR="$( jq -r '.document_menu.mobile_menu_selector // empty' "$BJ")"
BUILDER="$(  jq -r '.document_menu.mobile_builder       // empty' "$BJ")"
PREFIX="$(   jq -r '.document_menu.mobile_array_prefix  // empty' "$BJ")"
VMARRAY="$(  jq -r '.document_menu.view_mode_actions_array // empty' "$BJ")"
NENTRIES="$( jq -r '.document_menu.entries | length'     "$BJ" 2>/dev/null || echo 0)"

# FAIL CLOSED. An empty or half-written spec must never report green: this whole
# class of defect is "the check passed because it checked nothing".
[ -n "$MENU_FILE" ] && [ -n "$SELECTOR" ] && [ -n "$BUILDER" ] && [ -n "$PREFIX" ] && [ -n "$VMARRAY" ] \
    || die "build.json::document_menu is incomplete — an empty rule must not report green"
[ "$NENTRIES" -ge 1 ] \
    || die "build.json::document_menu.entries is empty — this rule is about the entries we add and must not pass on none"

URL="$(jq -r '.upstream.online.url      // empty' "$BJ")"
PIN="$(jq -r '.upstream.online.revision // empty' "$BJ")"
[ "${#PIN}" -eq 40 ] || die "upstream.online.revision must be a full 40-char sha, got '${PIN}'"
BASE="${URL%/*}"; PROJECT="${URL##*/}"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cool-menu-reach.XXXXXX")" || die "cannot mktemp"
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
[ -f "$WORK/$MENU_FILE" ] \
    || die "the series does not edit $MENU_FILE — document_menu describes a file no patch touches"
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

# THE PREMISE OF THE WHOLE RULE. If upstream ever stops selecting the mobile menu
# by name, "is it in a mobile* array" stops meaning "can a phone see it", and this
# tester would keep passing while saying nothing. Check it against upstream's OWN
# copy of the file, not the patched one, so our patch cannot satisfy it for them.
echo "== T2: the mobile menu is still chosen by the '$PREFIX' + docType rule =="
UP="$TMP/upstream-menubar"
fetch "$MENU_FILE" "$UP" || die "cannot re-read $MENU_FILE from $BASE — UNKNOWN, not a verdict"
if grep -qF "$SELECTOR" "$UP"; then
    ok "upstream $MENU_FILE still selects the mobile menu as $SELECTOR"
else
    bad "upstream no longer contains '$SELECTOR' — the mobile menu is chosen some other way now and every verdict below is meaningless"
    echo; echo "passed $PASS, failed $FAIL"; exit 1
fi
grep -qF "$BUILDER" "$UP" \
    && ok "upstream still defines $BUILDER, the function that reads it" \
    || bad "upstream no longer defines $BUILDER — re-derive this rule before trusting it"

echo "== T3: every entry we add is in every menu it must be in =="
python3 - "$WORK/$MENU_FILE" "$BJ" "$PREFIX" "$VMARRAY" <<'PY'
import json, re, sys

menubar, bj, prefix, vmarray = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
GREEN, RED, OFF = "\033[0;32m", "\033[0;31m", "\033[0m"
passed = failed = 0
def ok(m):
    global passed; passed += 1; print(f"  {GREEN}ok{OFF}: {m}")
def bad(m):
    global failed; failed += 1; print(f"  {RED}FAIL{OFF}: {m}")

lines = open(menubar, encoding="utf-8").read().splitlines()

# COMMENTS ARE STRIPPED BEFORE MATCHING. Our own patch comments name every id
# they add, several times, and a comment must neither create an obligation nor
# satisfy one. Not a general parser: a trailing comment on a real statement
# still leaves the statement, which is what we want.
code = [re.sub(r"\s*//.*$", "", ln) for ln in lines]

# Top-level `\t\t<name>: [` rows are the option arrays. Their extent runs to the
# next one; the last runs to end of file.
starts = [(i, m.group(1)) for i, ln in enumerate(code)
          for m in [re.match(r"^\t\t(\w+):\s*\[", ln)] if m]
if not starts:
    bad("found no top-level option arrays in the post-patch file — the file's shape changed")
    print(f"\npassed {passed}, failed {failed}")
    sys.exit(1)
ok(f"post-patch file declares {len(starts)} top-level option arrays "
   f"({sum(1 for _, n in starts if n.startswith(prefix))} of them '{prefix}*')")

def owner(idx):
    cur = None
    for s, n in starts:
        if s <= idx: cur = n
        else: break
    return cur

spec = json.load(open(bj))["document_menu"]
for entry in spec["entries"]:
    eid, label = entry["id"], entry.get("label", entry["id"])
    want = list(entry["must_appear_in"])

    # The DEFINITION rows, not the handler. A menu row names its id inside an
    # object literal that also carries `type:` — `id === 'x'` in _executeAction
    # is the handler and must not count as placement.
    found = sorted({owner(i) for i, ln in enumerate(code)
                    if re.search(r"id:\s*'%s'" % re.escape(eid), ln)
                    and "type:" in ln and owner(i) is not None})

    missing = [a for a in want if a not in found]
    extra_ok = [a for a in found if a not in want]
    if missing:
        bad(f"'{label}' (id {eid}) is declared for {want} but the post-patch file has it only in "
            f"{found or 'NO array at all'} — missing {missing}")
    else:
        ok(f"'{label}' (id {eid}) is in every array it must be in: {want}")
    if extra_ok:
        bad(f"'{label}' (id {eid}) also appears in {extra_ok}, which build.json does not declare — "
            f"a menu row nobody wrote down is a menu row nobody reviewed")

    # THE POINT OF THE TESTER. A phone opens only a mobile* array.
    if any(a.startswith(prefix) for a in want):
        ok(f"'{label}' is declared for at least one '{prefix}*' menu, so a phone can reach it")
    else:
        bad(f"'{label}' is declared only for desktop menus {want} — it would ship unreachable on "
            f"every phone in the fleet, which is the defect this rule exists to stop")

    if entry.get("must_be_view_mode_action"):
        # allowedViewModeActions is a flat list of id strings; find its extent and
        # look for the bare quoted id inside it.
        idx = [i for i, n in starts if n == vmarray]
        if not idx:
            bad(f"{vmarray} is not a top-level array in the post-patch file — cannot check "
                f"'{eid}' against it")
        else:
            s = idx[0]
            e = min([i for i, _ in starts if i > s], default=len(code))
            body = "\n".join(code[s:e])
            if re.search(r"'%s'" % re.escape(eid), body):
                ok(f"'{label}' is on {vmarray}, so a read-only document does not grey it out")
            else:
                bad(f"'{label}' is NOT on {vmarray} — _beforeShow disables every type:'action' "
                    f"not named there once isReadOnlyMode() is true")

print(f"\npassed {passed}, failed {failed}")
sys.exit(0 if failed == 0 else 1)
PY
rc=$?
if [ "$rc" -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi

# SELF-TEST. Delete every mobile* placement from the post-patch file and require
# the rule above to go RED. A tester that cannot fail is not a tester, and this
# one's whole job is to notice an absence.
if [ "$SELFTEST" -eq 1 ]; then
    echo "== T4: SELF-TEST — with the mobile rows removed, T3 must fail =="
    MUT="$TMP/mutant.ts"
    python3 - "$WORK/$MENU_FILE" "$MUT" "$BJ" "$PREFIX" <<'PY'
import json, re, sys
src, dst, bj, prefix = sys.argv[1:5]
code = open(src, encoding="utf-8").read().splitlines()
starts = [(i, m.group(1)) for i, ln in enumerate(code)
          for m in [re.match(r"^\t\t(\w+):\s*\[", ln)] if m]
def owner(idx):
    cur = None
    for s, n in starts:
        if s <= idx: cur = n
        else: break
    return cur
ids = [e["id"] for e in json.load(open(bj))["document_menu"]["entries"]]
out = []
for i, ln in enumerate(code):
    o = owner(i)
    if o and o.startswith(prefix) and any(re.search(r"id:\s*'%s'" % re.escape(x), ln) for x in ids) and "type:" in ln:
        continue   # drop the row a phone would have seen
    out.append(ln)
open(dst, "w", encoding="utf-8").write("\n".join(out) + "\n")
PY
    if python3 - "$MUT" "$BJ" "$PREFIX" "$VMARRAY" <<'PY' >/dev/null 2>&1
import json, re, sys
menubar, bj, prefix, vmarray = sys.argv[1:5]
code = [re.sub(r"\s*//.*$", "", ln) for ln in open(menubar, encoding="utf-8").read().splitlines()]
starts = [(i, m.group(1)) for i, ln in enumerate(code)
          for m in [re.match(r"^\t\t(\w+):\s*\[", ln)] if m]
def owner(idx):
    cur = None
    for s, n in starts:
        if s <= idx: cur = n
        else: break
    return cur
failed = 0
for entry in json.load(open(bj))["document_menu"]["entries"]:
    found = {owner(i) for i, ln in enumerate(code)
             if re.search(r"id:\s*'%s'" % re.escape(entry["id"]), ln) and "type:" in ln and owner(i)}
    if [a for a in entry["must_appear_in"] if a not in found]:
        failed += 1
sys.exit(0 if failed == 0 else 1)
PY
    then
        bad "the mutant (mobile rows deleted) still PASSES — this tester cannot detect the defect it exists for"
    else
        ok "the mutant with every mobile row deleted fails, as it must"
    fi
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
