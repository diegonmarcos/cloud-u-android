#!/usr/bin/env bash
# Tester (#826): EVERY fleet app bakes the commit it was built from, and the
# release sidecar names the SAME commit.
#
# Store Details reads the installed build's commit out of the APK versionName
# "(sha-xxxxxxxx)" (libs/appstore BuiltFrom, #820) and the available build's out
# of line 2 of the release `<asset>.source` sidecar (publish gate `stamp`). #820
# found 13 apps that baked nothing — their Installed row said "unknown" forever —
# and found the two writers reading different values: versionName took
# GITHUB_SHA, the sidecar `git rev-parse HEAD`.
#
#   T1  every app in constellation-fleet.json's `apps` group (minus PENDING) has
#       a gradle module that bakes versionName "… (sha-<var>)" AND
#       BuildConfig.GIT_SHORT_SHA from that same variable, and derives it the
#       fleet way: GITHUB_SHA first, `git rev-parse` when unset
#   T2  PENDING is exact: every entry is a fleet app that STILL does not bake
#       (one that starts baking must leave the list), with a reason
#   T3  the sidecar stamps ${GITHUB_SHA:-$(git rev-parse HEAD)} — the value
#       versionName baked — and nothing else
#
# A built-in mutation suite breaks each property on a COPY and demands red.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Fleet apps not covered by #826, each with why. Not a tolerance: T2 fails the
# moment one of them starts baking, so an entry cannot outlive its reason.
PENDING="
c3-webserver|tauri shell (src-tauri/gen/android); outside #826's scope — follow-up
chat|react-native fork (android/app/build.gradle); outside #826's scope — follow-up
code|cordova; versionName is set in onVariants (build-extras.gradle); outside #826's scope — follow-up
notes|capacitor shell (packages/frontend/apps/android); outside #826's scope — follow-up
office|Collabora source build via configure flags, no gradle module of ours to edit; outside #826's scope — follow-up
"

# The gradle file that declares the app's versionName.
_module() {
    local d="$1/$2" f
    for f in "$d/app/build.gradle" "$d/app/build.gradle.kts" "$d/hub/build.gradle"; do
        [ -f "$f" ] && grep -qE '^[[:space:]]*versionName' "$f" && { echo "$f"; return 0; }
    done
    return 1
}

# Does one gradle file bake the sha the fleet way? Prints the reason on failure.
_bakes() {
    local f="$1" var
    # Groovy:  versionName "… (sha-${shortSha})"   /  versionName = "… (sha-${shortSha})"
    # Kotlin:  versionName = "… (sha-$gitShortSha)"
    var="$(grep -E '^[[:space:]]*versionName[[:space:]=].*\(sha-\$\{?[A-Za-z_]+' "$f" \
           | sed -nE 's/.*\(sha-\$\{?([A-Za-z_]+).*/\1/p' | head -n 1)"
    [ -n "$var" ] || { echo "no versionName \"… (sha-\$var)\""; return 1; }
    grep -qE "buildConfigField[ (]+\"String\", *\"GIT_SHORT_SHA\", *\"\\\\\"\\\$\\{?${var}\\}?\\\\\"\"" "$f" \
        || { echo "BuildConfig.GIT_SHORT_SHA not baked from \$$var"; return 1; }
    # GITHUB_SHA first, `?: providers.exec { … git rev-parse … }` when unset — directly,
    # or through one intermediate (drive: fullSha → shortSha = fullSha.take(8)).
    python3 - "$f" "$var" <<'PY' || return 1
import re, sys
src, var = open(sys.argv[1], encoding="utf-8").read(), sys.argv[2]
derive = r"\(System\.getenv\(['\"]GITHUB_SHA['\"]\)[^\n]*\n?\s*\?:\s*providers\.exec\s*\{\s*commandLine[ (]+['\"]git['\"],\s*['\"]rev-parse['\"]"
decl = lambda v: r"(?:def|val)\s+" + v + r"\b[^=\n]*=\s*"
if re.search(decl(var) + derive, src): sys.exit(0)
m = re.search(decl(var) + r"([A-Za-z_]+)\.take\(8\)", src)
if m and re.search(decl(m.group(1)) + derive, src): sys.exit(0)
print("$%s is not derived GITHUB_SHA-first with a git rev-parse fallback" % var); sys.exit(1)
PY
    return 0
}

# All checks over a tree rooted at $1. Exit 0 = green.
check() {
    local R="$1" fail=0 id dir f why pend
    local fleet="$R/aa_cloud-superapp/data/constellation-fleet.json"
    local gate="$R/1_cicd/src/scripts/cloud-android-publish-gate.sh"
    local n=0
    while IFS=$'\t' read -r id dir; do
        [ -n "$id" ] || continue
        n=$((n + 1))
        pend="$(printf '%s\n' "$PENDING" | awk -F'|' -v k="$id" '$1==k{print $2}')"
        if f="$(_module "$R" "$dir")" && why="$(_bakes "$f")"; then
            if [ -n "$pend" ]; then echo "  FAIL T2 $id now bakes its sha — delete it from PENDING"; fail=1
            else echo "  ok   T1 $id ($dir) bakes its sha"; fi
        else
            [ -n "${f:-}" ] || why="no gradle module declares versionName"
            if [ -n "$pend" ]; then echo "  pend T2 $id — $pend"
            else echo "  FAIL T1 $id ($dir): $why"; fail=1; fi
        fi
        f=""
    done < <(jq -r '.apps[] | select(.group=="apps") | "\(.id)\t\(.repo_url | sub(".*/tree/main/"; ""))"' "$fleet")
    [ "$n" -ge 20 ] || { echo "  FAIL T1 read only $n fleet apps — the roster did not parse"; fail=1; }
    while IFS='|' read -r id why; do
        [ -n "$id" ] || continue
        jq -e --arg k "$id" '.apps[] | select(.group=="apps" and .id==$k)' "$fleet" >/dev/null \
            || { echo "  FAIL T2 PENDING names '$id', not a fleet app"; fail=1; }
        [ -n "$why" ] || { echo "  FAIL T2 PENDING '$id' carries no reason"; fail=1; }
    done <<<"$PENDING"
    if grep -qF 'COMMIT="${GITHUB_SHA:-$(git -C "$ROOT" rev-parse HEAD)}"' "$gate" \
       && [ "$(grep -cE '^[[:space:]]*COMMIT=' "$gate")" -eq 1 ]; then
        echo "  ok   T3 sidecar stamps the commit versionName baked"
    else
        echo "  FAIL T3 the sidecar's commit is not \${GITHUB_SHA:-\$(git rev-parse HEAD)}"; fail=1
    fi
    return $fail
}

[ "${1:-}" = "--check" ] && { check "$2"; exit $?; }

echo "── the tree ──"
check "$ROOT"; FAIL=$?

# ── mutations: each on a copy, each must turn red ─────────────────────────────
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
_stage() {
    rm -rf "$W/t"; mkdir -p "$W/t/aa_cloud-superapp/data" "$W/t/1_cicd/src/scripts"
    cp "$ROOT/aa_cloud-superapp/data/constellation-fleet.json" "$W/t/aa_cloud-superapp/data/"
    cp "$ROOT/1_cicd/src/scripts/cloud-android-publish-gate.sh" "$W/t/1_cicd/src/scripts/"
    local f rel
    for f in "$ROOT"/a[ac]_*/app/build.gradle "$ROOT"/a[ac]_*/app/build.gradle.kts "$ROOT"/a[ac]_*/hub/build.gradle; do
        [ -f "$f" ] || continue
        rel="${f#"$ROOT"/}"; mkdir -p "$W/t/$(dirname "$rel")"; cp "$f" "$W/t/$rel"
    done
}
_sub() {  # file old new — exact, once; a mutation that does not apply is an error
    python3 - "$1" "$2" "$3" <<'PY'
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if old not in s:
    sys.stderr.write("MUTATION DID NOT APPLY: %r absent from %s\n" % (old, p)); sys.exit(2)
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
}
HOLLOW=0; M=0
_mut() {  # label file old new
    _stage
    check "$W/t" >/dev/null 2>&1 || { echo "  MUT-VOID   $1 — the unmutated copy is already red"; HOLLOW=$((HOLLOW+1)); return; }
    _sub "$W/t/$2" "$3" "$4" || { HOLLOW=$((HOLLOW+1)); return; }
    M=$((M+1))
    if check "$W/t" >/dev/null 2>&1; then echo "  MUT-HOLLOW $1 — still green"; HOLLOW=$((HOLLOW+1))
    else echo "  MUT-RED    $1"; fi
}
echo "── mutations ──"
_mut "termux drops the versionName suffix (groovy, post-semver)" ac_cloud-termux/app/build.gradle \
     'versionName = "${versionName} (sha-${shortSha})"' 'versionName = "${versionName}"'
_mut "morpheus GIT_SHORT_SHA gone" ac_c3-morpheus/app/build.gradle \
     'buildConfigField "String", "GIT_SHORT_SHA", "\"${shortSha}\""' ''
_mut "dialer GIT_SHORT_SHA back to the -P prop" ac_cloud-dialer/app/build.gradle.kts \
     'buildConfigField("String", "GIT_SHORT_SHA", "\"$gitShortSha\"")' 'buildConfigField("String", "GIT_SHORT_SHA", "\"${project.findProperty("GIT_SHORT_SHA") ?: "dev"}\"")'
_mut "vault derives from git only (GITHUB_SHA dropped)" ac_cloud-vault/app/build.gradle.kts \
     'System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }' 'null'
_mut "mail loses the git fallback" ac_cloud-mail/app/build.gradle.kts \
     'commandLine("git", "rev-parse", "--short=8", "HEAD")' 'commandLine("true")'
_mut "an existing app (nav) stops baking" ac_cloud-nav/app/build.gradle \
     '(sha-${shortSha})' ''
_mut "the sidecar back to HEAD alone" 1_cicd/src/scripts/cloud-android-publish-gate.sh \
     'COMMIT="${GITHUB_SHA:-$(git -C "$ROOT" rev-parse HEAD)}"' 'COMMIT="$(git -C "$ROOT" rev-parse HEAD)"'
_mut "a new fleet app nobody wired" aa_cloud-superapp/data/constellation-fleet.json \
     '"repo_url":"https://github.com/diegonmarcos/cloud-u-android/tree/main/ac_cloud-calc"' '"repo_url":"https://github.com/diegonmarcos/cloud-u-android/tree/main/ac_cloud-nonexistent"'
_mut "a PENDING app that now bakes stays listed (webserver wired)" aa_cloud-superapp/data/constellation-fleet.json \
     '"repo_url":"https://github.com/diegonmarcos/cloud-u-android/tree/main/ac_cloud-c3-webserver"' '"repo_url":"https://github.com/diegonmarcos/cloud-u-android/tree/main/ac_cloud-calc"'

echo
if [ "$FAIL" -eq 0 ] && [ "$HOLLOW" -eq 0 ]; then echo "ALL GREEN — $M mutations, every one red"; exit 0; fi
echo "RED — tree fail=$FAIL, hollow/void mutations=$HOLLOW"; exit 1
