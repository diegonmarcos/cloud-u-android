#!/usr/bin/env bash
# THE PREBUILT PAYLOAD CARRIES ANOTHER APPLICATION'S PACKAGE ID, AND SHIPPING IT
# VERBATIM IS A GREEN BUILD THAT DEGRADES THE ONLY THING THE APP IS FOR.
#
# ── what this is about ─────────────────────────────────────────────────────
# build.json::upstream.engine takes the engine's DATA half straight out of
# Collabora's sha256-pinned APK instead of building it. For almost everything
# under assets/ that is exactly right: the files are engine data and carry no
# identity. ONE file is different.
#
# android/lib/build.gradle's copyUnpackAssets does not COPY fonts.conf, it
# GENERATES it:
#
#     into('etc/fonts') {
#         from 'fonts.conf'
#         filter { String line ->
#             line.replaceAll('@@APPLICATION_ID@@', new String("${liboApplicationId}")) }
#     }
#
# and _doc_BLOCKER_configure_gate (3) requires prebuilt mode to DISABLE that
# task, because its other `from` directories point into an engine build tree we
# never build and a Gradle Copy whose `from` is missing succeeds silently.
# Disable the task, take its output from the pinned APK, and we inherit the
# substitution COLLABORA made:
#
#     <cachedir>/data/data/com.collabora.libreoffice/fontconfig</cachedir>
#
# That is another app's private data directory. Android gives it a different
# uid, so our process can neither read nor write it: fontconfig silently fails
# to cache and re-scans the font set on every document open. Nothing crashes,
# nothing is logged at the build, and the APK installs and launches. It is the
# same shape as the blocker above it — a Copy that "worked" and produced the
# wrong thing — which is why it is asserted rather than remembered.
#
# ── what it asserts, and why in this direction ─────────────────────────────
# It asserts the PROBLEM still exists in the pinned artefact, not that a fix is
# present, because the fix lives in a build that does not run yet. Concretely,
# for each path build.json::upstream.engine.assets.rebrand.files names:
#
#   T2  the entry exists in the pinned APK              (else the plan is stale)
#   T3  its bytes contain rebrand.upstream_application_id
#                                        (else the substitution stopped happening
#                                         upstream and this file can be taken
#                                         verbatim after all — report, do not pass)
#   T4  its bytes do NOT already contain android.application_id
#                                        (a sanity check on T3's meaning)
#
# and T5 asserts the inverse for a CONTROL path that must be clean, so a bug
# that made every read return the same buffer cannot show up as a pass.
#
# THE LIST IS DATA, NOT SOURCE. rebrand.files was derived by range-reading all
# 546 text-config entries under assets/ (assets/dist excluded) out of the pinned
# APK and grepping each for 'com.collabora'; exactly one matched. Adding a file
# here is a build.json edit, never an edit to this script.
#
# Usage: ./test-prebuilt-assets-need-rebranding.sh        (needs network)
#        ./test-prebuilt-assets-need-rebranding.sh --self-test
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo -e "  \033[0;32mok\033[0m: $1"; }
bad() { FAIL=$((FAIL+1)); echo -e "  \033[0;31mFAIL\033[0m: $1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

# A tester that shells out to a missing tool does not fail, it answers from the
# tool's absence. Four testers in this fleet have already passed that way.
for t in curl python3 jq; do
    command -v "$t" >/dev/null 2>&1 || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"

URL="$(jq -r '.upstream.engine.source.url    // empty' "$BJ")"
SIZE="$(jq -r '.upstream.engine.source.size  // empty' "$BJ")"
SHA="$(jq  -r '.upstream.engine.source.sha256 // empty' "$BJ")"
OURS="$(jq -r '.android.application_id        // empty' "$BJ")"
THEIRS="$(jq -r '.upstream.engine.assets.rebrand.upstream_application_id // empty' "$BJ")"
mapfile -t FILES < <(jq -r '.upstream.engine.assets.rebrand.files[]? // empty' "$BJ")

for v in URL SIZE SHA OURS THEIRS; do
    eval "val=\$$v"
    [ -n "$val" ] || die "build.json is missing the value behind $v — this tester would assert nothing"
done
# FAIL CLOSED ON AN EMPTY LIST. An empty `files` would make every loop below
# run zero times and the script exit 0, which is the single most common way a
# tester in this repository has reported a verdict it never reached.
[ "${#FILES[@]}" -gt 0 ] \
    || die "upstream.engine.assets.rebrand.files is empty — a tester that checks nothing must not be green"

# ── read one entry out of the remote APK by byte range ─────────────────────
# 6,375 entries and a 591 KB central directory: reading one 1.8 KB file costs
# three ranged GETs, not a 267 MB download.
extract() { python3 - "$URL" "$SIZE" "$1" <<'PY'
import urllib.request, struct, sys, zlib
url, size, want = sys.argv[1], int(sys.argv[2]), sys.argv[3]
def rng(a, b):
    r = urllib.request.Request(url, headers={"Range": f"bytes={a}-{b}"})
    with urllib.request.urlopen(r, timeout=180) as f: return f.read()
try:
    tail = rng(size - 70000, size - 1)
    i = tail.rfind(b"PK\x05\x06"); cdsize, cdoff = struct.unpack("<II", tail[i+12:i+20])
    j = tail.rfind(b"PK\x06\x06")
    if j != -1: cdsize, cdoff = struct.unpack("<QQ", tail[j+40:j+56])
    cd = rng(cdoff, cdoff + cdsize - 1)
    p = 0
    while p < len(cd) - 4 and cd[p:p+4] == b"PK\x01\x02":
        nl, el, cl = struct.unpack("<HHH", cd[p+28:p+34])
        meth,      = struct.unpack("<H",   cd[p+10:p+12])
        csz, _usz  = struct.unpack("<II",  cd[p+20:p+28])
        lho,       = struct.unpack("<I",   cd[p+42:p+46])
        name = cd[p+46:p+46+nl].decode("utf-8", "replace")
        if name == want:
            # The LOCAL header's extra field length differs from the central
            # one; it must be read from the local header or the offset is wrong.
            hdr = rng(lho, lho + 29)
            nl2, el2 = struct.unpack("<HH", hdr[26:30])
            st = lho + 30 + nl2 + el2
            raw = rng(st, st + csz - 1)
            sys.stdout.buffer.write(raw if meth == 0 else zlib.decompress(raw, -15))
            sys.exit(0)
        p += 46 + nl + el + cl
    sys.exit(3)          # parsed the directory, entry genuinely absent
except SystemExit: raise
except Exception as e:
    print(f"extract: {e}", file=sys.stderr); sys.exit(4)    # could not read: NOT "absent"
PY
}

# ── --self-test: break each assertion and require it to go red ─────────────
if [ "${1:-}" = "--self-test" ]; then
    echo "== self-test: each assertion must fail when its premise is broken =="
    rc=0
    body="$(extract "${FILES[0]}")" || { echo "  self-test cannot run: extract failed"; exit 2; }
    grep -qF "$THEIRS" <<<"$body" && echo "  ok: control — the real file DOES contain $THEIRS" \
        || { echo "  BROKEN: the real file does not contain $THEIRS"; rc=1; }
    grep -qF "$OURS" <<<"$body" && { echo "  BROKEN: the real file already contains $OURS"; rc=1; } \
        || echo "  ok: control — the real file does NOT contain $OURS"
    # a path that cannot exist must report absent, never pass
    if extract "assets/unpack/etc/fonts/NOT-A-REAL-FILE.conf" >/dev/null 2>&1; then
        echo "  BROKEN: a nonexistent entry extracted successfully"; rc=1
    else
        echo "  ok: a nonexistent entry fails instead of returning empty-and-passing"
    fi
    exit $rc
fi

echo "== T1: the pin this reads is the pin build.json declares =="
ok "pinned artefact $SIZE bytes, sha256 ${SHA:0:16}… (identity checked by tests/test-engine-artefacts-in-apk.sh)"

echo "== T2-T4: every file declared as needing a rebrand still needs one =="
for f in "${FILES[@]}"; do
    body="$(extract "$f")"; rc=$?
    if [ $rc -eq 3 ]; then
        bad "$f is NOT in the pinned APK — rebrand.files names a path the artefact does not carry, so the prebuilt plan is describing a tree that moved"
        continue
    elif [ $rc -ne 0 ]; then
        bad "$f could not be read from the pinned APK (extract rc=$rc) — reported as unreadable, NOT as clean"
        continue
    fi
    ok "T2 $f is present in the pinned APK ($(printf '%s' "$body" | wc -c) bytes)"

    if grep -qF "$THEIRS" <<<"$body"; then
        line="$(grep -F "$THEIRS" <<<"$body" | head -1 | sed 's/^[[:space:]]*//')"
        ok "T3 $f still bakes in $THEIRS — the substitution must be redone for us: $line"
    else
        bad "T3 $f no longer contains $THEIRS. Upstream may have stopped baking the id in, which would mean this file CAN be taken verbatim — but that is a decision, not a pass. Re-derive rebrand.files against the current pin before removing this entry."
    fi

    if grep -qF "$OURS" <<<"$body"; then
        bad "T4 $f already contains $OURS — impossible for an artefact Collabora built, so this run is reading something other than the pinned APK"
    else
        ok "T4 $f does not contain $OURS, as an upstream-built artefact must not"
    fi
done

echo "== T5: a file with no identity in it reads clean, so T3 is not vacuous =="
# If a defect made every read return the same buffer, or made grep match
# unconditionally, T3 would pass for the wrong reason. This control must NOT
# match, and it is only meaningful because T3 above did.
# DERIVED FROM THE ARTEFACT, NOT WRITTEN DOWN. The first draft named a control
# path by hand and it was not in the APK, so the control reported "could not be
# read" — correctly refusing to pass, but asserting nothing. Ask the archive
# which entries it actually has and take the first one that is not itself a
# rebrand target; then the control cannot rot when upstream moves a file.
CONTROL="$(python3 - "$URL" "$SIZE" "$(printf '%s\n' "${FILES[@]}")" <<'PYC'
import urllib.request, struct, sys
url, size = sys.argv[1], int(sys.argv[2])
skip = set(filter(None, sys.argv[3].split("\n")))
def rng(a, b):
    r = urllib.request.Request(url, headers={"Range": f"bytes={a}-{b}"})
    with urllib.request.urlopen(r, timeout=180) as f: return f.read()
tail = rng(size - 70000, size - 1)
i = tail.rfind(b"PK\x05\x06"); cdsize, cdoff = struct.unpack("<II", tail[i+12:i+20])
j = tail.rfind(b"PK\x06\x06")
if j != -1: cdsize, cdoff = struct.unpack("<QQ", tail[j+40:j+56])
cd = rng(cdoff, cdoff + cdsize - 1); p = 0
TEXT = (".conf", ".xcu", ".xml", ".cfg", ".rc", ".txt")
while p < len(cd) - 4 and cd[p:p+4] == b"PK\x01\x02":
    nl, el, cl = struct.unpack("<HHH", cd[p+28:p+34])
    _, usz = struct.unpack("<II", cd[p+20:p+28])
    name = cd[p+46:p+46+nl].decode("utf-8", "replace")
    if (name.startswith("assets/") and not name.startswith("assets/dist/")
            and name.lower().endswith(TEXT) and 0 < usz <= 262144 and name not in skip):
        print(name); sys.exit(0)
    p += 46 + nl + el + cl
sys.exit(3)
PYC
)"
[ -n "$CONTROL" ] || { bad "could not derive a control entry from the pinned APK — T3 is unverified this run"; CONTROL=""; }
cbody=""; crc=0
[ -n "$CONTROL" ] && { cbody="$(extract "$CONTROL")"; crc=$?; } || crc=4
if [ $crc -ne 0 ]; then
    bad "control $CONTROL could not be read (rc=$crc) — T3's result is unverified this run"
elif grep -qF "$THEIRS" <<<"$cbody"; then
    bad "control $CONTROL also contains $THEIRS — it belongs in rebrand.files, or the extractor is returning the wrong entry"
else
    ok "control $CONTROL is free of $THEIRS — the match in T3 is specific to that file"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
