#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ every published <asset>.source names the git commit it was built ║
# ║ from — a full 40-hex sha that exists in this repository          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The release sidecar held one line: the publish gate's INPUT identity, a
# content digest. It answers "did the inputs move?" and nothing else, so no
# published APK could say which commit it came from. The gate now stamps two
# lines — identity, then the commit the APK's versionName baked (#826:
# ${GITHUB_SHA:-$(git rev-parse HEAD)}) — and refuses to upload a
# sidecar whose line 2 is not a 40-hex commit in this repository. `check`
# still compares line 1 only, so skipping unchanged builds is untouched.
#
#   V  verify-source, both polarities, on real fixture files
#   S  stamp writes identity + HEAD and runs verify-source before uploading;
#      check reads line 1 only
#   M  each property, broken on a copy of the gate, turns red — and the copy
#      is verified to differ before the result counts
#
# Needs git and sh only: no gh, no network.
set -uo pipefail
cd "$(dirname "$0")/../../../.."
ROOT="$PWD"
GATE=1_cicd/src/scripts/cloud-android-publish-gate.sh
[ -f "$GATE" ] || { echo "ERROR missing $GATE — unrun, not passing"; exit 1; }

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  ok    $*"; }
bad() { fail=$((fail+1)); echo "  FAIL  $*"; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
HEAD_SHA="$(git rev-parse HEAD)"
IDENT="$(printf 'x' | sha256sum | cut -d' ' -f1)"   # 64 hex, the shape of the real identity
mk() { local n="$1"; shift; printf '%s\n' "$@" > "$TMP/$n"; printf '%s' "$TMP/$n"; }

GOOD="$(mk good "$IDENT" "$HEAD_SHA")"
LEGACY="$(mk legacy "$IDENT")"
DIGEST="$(mk digest "$IDENT" "$IDENT")"
SHORT="$(mk short "$IDENT" "${HEAD_SHA:0:39}")"
GHOST="$(mk ghost "$IDENT" "0123456789abcdef0123456789abcdef01234567")"
UPPER="$(mk upper "$IDENT" "$(printf '%s' "$HEAD_SHA" | tr a-f A-F)")"

# verify <gate> <file> → exit code, never through a pipe
verify() { CLOUD_ANDROID_ROOT="$ROOT" sh "$1" verify-source "$2" >/dev/null 2>&1; }

# v_all <gate>: 0 when every polarity holds
v_all() {
    local g="$1" r=0
    verify "$g" "$GOOD"   || r=1
    verify "$g" "$LEGACY" && r=1
    verify "$g" "$DIGEST" && r=1
    verify "$g" "$SHORT"  && r=1
    verify "$g" "$GHOST"  && r=1
    verify "$g" "$UPPER"  && r=1
    return $r
}
# the script's CODE, comment lines stripped
_code() { grep -vE '^[[:space:]]*#' "$1"; }
s_stamp() {
    [ "$(_code "$1" | grep -cF 'COMMIT="${GITHUB_SHA:-$(git -C "$ROOT" rev-parse HEAD)}"')" -eq 1 ] &&
    [ "$(_code "$1" | grep -cF "printf '%s\n%s\n' \"\$IDENTITY\" \"\$COMMIT\" > \"\$tmp/\$ASSET.source\"")" -eq 1 ] &&
    [ "$(_code "$1" | grep -cF 'if ! _verify_source "$tmp/$ASSET.source"; then')" -eq 1 ]
}
s_check() {
    [ "$(_code "$1" | grep -cF 'prev="$(head -n 1 "$tmp/$ASSET.source" | tr -d')" -eq 1 ]
}

echo "── V verify-source ──"
verify "$GATE" "$GOOD"   && ok "identity + HEAD's 40-hex sha accepted"         || bad "a real commit was refused"
verify "$GATE" "$LEGACY" && bad "a one-line (digest-only) sidecar passed"        || ok "digest-only legacy sidecar refused"
verify "$GATE" "$DIGEST" && bad "a 64-hex content digest passed as a commit"     || ok "content digest on line 2 refused"
verify "$GATE" "$SHORT"  && bad "a 39-hex abbreviation passed"                   || ok "abbreviated sha refused"
verify "$GATE" "$GHOST"  && bad "a 40-hex sha that is no commit here passed"     || ok "40-hex non-commit refused"
verify "$GATE" "$UPPER"  && bad "an uppercase sha passed"                         || ok "non-canonical (uppercase) sha refused"

echo "── S stamp/check wiring ──"
s_stamp "$GATE" && ok "stamp writes identity + the baked commit (GITHUB_SHA, else HEAD) and verifies before upload" || bad "stamp can upload a sidecar naming no commit"
s_check "$GATE" && ok "check compares line 1 only" || bad "check compares the commit too — every push would republish"
cmp -s <(sed 1d 1_cicd/dist/scripts/cloud-android-publish-gate.sh) "$GATE" \
    && ok "dist mirror equals source" || bad "dist/scripts copy drifted from the source the CI runs"

echo "── M mutation proof ──"
# mut <label> <check-fn> <sed-expr>
mut() {
    local label="$1" fn="$2" expr="$3" copy="$TMP/gate.sh"
    sed -E "$expr" "$GATE" > "$copy"
    if cmp -s "$GATE" "$copy"; then bad "MUT $label: did not apply — proves nothing"; return; fi
    if "$fn" "$copy"; then bad "MUT $label: still green with the defect planted"; else ok "MUT $label: red"; fi
}
mut "existence check dropped"   v_all   's/git -C "\$ROOT" cat-file -e "\$sha\^\{commit\}" 2>\/dev\/null/true/'
mut "length check dropped"      v_all   's/\[ "\$\{#sha\}" -eq 40 \]/true/'
mut "hex check dropped"         v_all   's/\*\[!0-9a-f\]\*\|""\)/"")/'
mut "reads line 1 as the sha"   v_all   's/sha="\$\(sed -n 2p/sha="$(sed -n 1p/'
mut "stamp writes identity only" s_stamp "s/printf '%s\\\\n%s\\\\n' \"\\\$IDENTITY\" \"\\\$COMMIT\"/printf '%s\\\\n' \"\$IDENTITY\"/"
mut "stamp ignores GITHUB_SHA" s_stamp 's/COMMIT="\$\{GITHUB_SHA:-\$\(git -C "\$ROOT" rev-parse HEAD\)\}"/COMMIT="$(git -C "$ROOT" rev-parse HEAD)"/'
mut "stamp skips the verify"    s_stamp 's/if ! _verify_source "\$tmp\/\$ASSET\.source"; then/if false; then/'
mut "check reads whole file"    s_check 's/prev="\$\(head -n 1 "\$tmp\/\$ASSET\.source" \| tr -d/prev="$(cat "$tmp\/$ASSET.source" | tr -d/'

echo
echo "== RESULT: $pass passed, $fail failed =="
[ "$fail" -eq 0 ]
