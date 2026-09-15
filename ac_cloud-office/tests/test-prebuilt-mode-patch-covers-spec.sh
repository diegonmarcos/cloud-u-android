#!/usr/bin/env bash
#
# Cloud Office — does patches/0002 answer every anchor the spec names?
#
# build.json::prebuilt_mode.neutralise is the specification for the prebuilt
# engine mode, and tests/test-prebuilt-mode-anchors-exist.sh proves each anchor
# is still REAL UPSTREAM. Nothing proved the other half: that the patch written
# from that spec actually addresses each one. The specific failure that half
# exists to catch is a patch that hits five of six anchors — the build then
# either asks for the NDK it was supposed to stop asking for, or ships the
# engine's data half missing after a wholly green run, because a Gradle Copy
# whose source does not exist succeeds and copies nothing.
#
# So every entry DECLARES what the patch must do about it:
#   action "neutralise" + neutralised_by  → that exact line must be ADDED by 0002
#   action "keep"                         → deliberately still on the build graph
#                                           (cool-bundle-release is the Node build
#                                           of browser/, which carries our own
#                                           Text Enhance menu)
#
# Reads only files in this repository: no network, no Gerrit, no clone. The
# upstream-side claims are the other testers' job.
#
#   ./tests/test-prebuilt-mode-patch-covers-spec.sh
#   ./tests/test-prebuilt-mode-patch-covers-spec.sh --self-test
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BUILD_JSON="${CLOUD_OFFICE_BUILD_JSON:-$APP/build.json}"
PATCH_DIR="${CLOUD_OFFICE_PATCH_DIR:-$APP/patches}"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  \033[0;32mok\033[0m: %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[0;31mFAIL\033[0m: %s\n' "$1"; }
die() { printf 'ERROR: %s\n' "$1" >&2; exit 2; }

command -v jq >/dev/null 2>&1 || die "jq is not on PATH — refusing to report a verdict this run never computed"
[ -f "$BUILD_JSON" ] || die "$BUILD_JSON missing"

PREBUILT_PATCH="$(ls "$PATCH_DIR"/0002-*.patch 2>/dev/null | head -1)"
[ -n "$PREBUILT_PATCH" ] && [ -f "$PREBUILT_PATCH" ] \
    || die "no patches/0002-*.patch — the prebuilt engine mode is the patch, not the spec"

# The ADDED lines only. A string that merely appears in context is upstream's,
# not ours, and would let a patch that changes nothing pass.
ADDED="$(grep '^+' "$PREBUILT_PATCH" | grep -v '^+++')"
[ -n "$ADDED" ] || die "patches/0002 adds no lines — a patch that parses to nothing cannot cover a spec"

COUNT="$(jq '.prebuilt_mode.neutralise | length' "$BUILD_JSON")"
[ "$COUNT" -ge 1 ] 2>/dev/null \
    || die "prebuilt_mode.neutralise is empty — a spec with no anchors must not report green"

echo "== T1: every anchor declares what the patch does about it =="
for i in $(seq 0 $((COUNT - 1))); do
    id="$(jq -r ".prebuilt_mode.neutralise[$i].id" "$BUILD_JSON")"
    action="$(jq -r ".prebuilt_mode.neutralise[$i].action // empty" "$BUILD_JSON")"
    case "$action" in
        neutralise|keep) ok "$id: action $action" ;;
        "") bad "$id: no action — the spec does not say whether patches/0002 must address it" ;;
        *)  bad "$id: unknown action '$action' (expected neutralise or keep)" ;;
    esac
done

echo "== T2: each 'neutralise' anchor is answered by a line the patch ADDS =="
neutralised=0
for i in $(seq 0 $((COUNT - 1))); do
    [ "$(jq -r ".prebuilt_mode.neutralise[$i].action // empty" "$BUILD_JSON")" = "neutralise" ] || continue
    id="$(jq -r ".prebuilt_mode.neutralise[$i].id" "$BUILD_JSON")"
    line="$(jq -r ".prebuilt_mode.neutralise[$i].neutralised_by // empty" "$BUILD_JSON")"
    if [ -z "$line" ]; then
        bad "$id: action is neutralise with no neutralised_by — nothing to check the patch against"
        continue
    fi
    neutralised=$((neutralised + 1))
    if printf '%s\n' "$ADDED" | grep -qF -- "$line"; then
        ok "$id: patches/0002 adds '$line'"
    else
        bad "$id: patches/0002 adds no line containing '$line' — the anchor is specified and unanswered, which is exactly the five-of-six patch this tester exists to catch"
    fi
done
[ "$neutralised" -ge 1 ] \
    && ok "$neutralised anchor(s) were actually checked" \
    || bad "no anchor carried action neutralise — this run asserted nothing about the patch"

echo "== T3: the configure mode is DECLARED, not worked around =="
if printf '%s\n' "$ADDED" | grep -q 'AC_ARG_WITH(\[prebuilt-engine\]'; then
    ok "configure.ac gains --with-prebuilt-engine as a declared option"
else
    bad "patches/0002 adds no AC_ARG_WITH([prebuilt-engine]) — a mode that is not declared is a hack around configure.ac:884"
fi

echo "== T4: the patch series still compiles nothing native =="
# The load-bearing negative. If a patch ever adds a C++ line, libandroidapp.so
# needs relinking, the eight static archives come back and #240 with them.
native="$(grep '^+++ b/' "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch \
          | sed 's|^.*+++ b/||' \
          | grep -E '\.(c|cc|cpp|cxx|h|hpp|hxx|inl|mk|s)$|(^|/)(CMakeLists\.txt(\.in)?|Makefile(\.am|\.in)?)$' || true)"
[ -z "$native" ] \
    && ok "no patch touches a native source or build file" \
    || bad "native files in the series: $native — the engine would have to be rebuilt"

echo "== T5: fonts.conf is still generated for OUR application id =="
# Taking the released fonts.conf verbatim ships Collabora's own cache path: a
# different application's private data directory, unreadable at our uid. Green
# build, signed APK, and every document open re-scans the font set.
if printf '%s\n' "$ADDED" | grep -q "@@APPLICATION_ID@@" \
   && printf '%s\n' "$ADDED" | grep -q "unpack/etc/fonts/fonts.conf"; then
    ok "fonts.conf is excluded from the copied payload and generated instead"
else
    bad "patches/0002 does not both exclude the released fonts.conf and generate one — see build.json::upstream.engine.assets.rebrand"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ] || exit 1

[ "${1:-}" = "--self-test" ] || exit 0

# ── self-test: prove each verdict can go red ─────────────────────────
echo
echo "── self-test 1: an anchor whose neutralised_by the patch does not add ──"
BROKEN="$(mktemp -d)"; trap 'rm -rf "$BROKEN"' EXIT
jq '(.prebuilt_mode.neutralise[] | select(.action=="neutralise") | .neutralised_by) |= "notActuallyInThePatch = true"' \
    "$BUILD_JSON" > "$BROKEN/build.json"
if CLOUD_OFFICE_BUILD_JSON="$BROKEN/build.json" "$0" >/dev/null 2>&1; then
    echo "FAIL: the checker PASSED a spec no line of the patch answers" >&2; exit 1
fi
echo "  ok: an unanswered anchor fails, as it must"

echo "── self-test 2: a series with a native file in it ──"
mkdir -p "$BROKEN/patches"
cp "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch "$BROKEN/patches/"
printf '+++ b/wsd/COOLWSD.cpp\n+// pretend we touched the engine\n' >> "$BROKEN/patches/0002-native.patch"
if CLOUD_OFFICE_PATCH_DIR="$BROKEN/patches" "$0" >/dev/null 2>&1; then
    echo "FAIL: the checker PASSED a series containing a C++ file" >&2; exit 1
fi
echo "  ok: a native file in the series fails, as it must"
