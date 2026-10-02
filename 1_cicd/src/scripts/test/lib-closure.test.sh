#!/usr/bin/env bash
# ╔═══════════════════════════════════════════════════════════════════════╗
# ║ lib-closure.test — the one answer to "which shared libs are inputs of  ║
# ║ this app" separates real inputs from mentions                          ║
# ╚═══════════════════════════════════════════════════════════════════════╝
#
# #763. cloud_android_lib_closure.inputs decides an app's ship triggers (the
# generator) and the mesh guard's G2. Too wide and every lib edit rebuilds and
# republishes apps whose bytes did not move (vault watched three libs it never
# compiled); too narrow and a lib edit silently fails to reach an app that ships
# it. Each case below was a real shape in the tree on 2026-10-02.
#
# python3 + coreutils; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
MOD="$ROOT/1_cicd/src/scripts/cloud_android_lib_closure.py"
[ -f "$MOD" ] || { echo "ERROR missing source: $MOD — this test is unrun, not passing"; exit 1; }
export PYTHONDONTWRITEBYTECODE=1

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
T="$WORK/t"; L="$T/ab_cloud-libs-shared/libs"

lib() { mkdir -p "$L/$1"; printf '%s\n' "${2:-}" > "$L/$1/build.gradle"; }
lib core     "dependencies { api project(':libs:devtools') }"
lib devtools ""
lib shizuku  ""
lib sysdns   ""
lib fin      ""
lib updater  "dependencies {
    if (findProject(':libs:shizuku') != null) {
        implementation project(':libs:shizuku')
    }
    implementation project(':libs:core')
}"

app() {  # app <dir> <module-map json> <app/build.gradle body>
    mkdir -p "$T/$1/app"
    printf '{"modules": %s}\n' "$2" > "$T/$1/build.json"
    printf '%s\n' "$3" > "$T/$1/app/build.gradle"
}
UPD="dependencies { implementation project(':libs:updater') }"
app a_plain '{"app": {}, "libs:core": {"dir": "../ab_cloud-libs-shared/libs/core"}, "libs:devtools": {"dir": "../ab_cloud-libs-shared/libs/devtools"}, "libs:updater": {"dir": "../ab_cloud-libs-shared/libs/updater"}}' "$UPD"
app a_shiz  '{"app": {}, "libs:core": {"dir": "../ab_cloud-libs-shared/libs/core"}, "libs:devtools": {"dir": "../ab_cloud-libs-shared/libs/devtools"}, "libs:updater": {"dir": "../ab_cloud-libs-shared/libs/updater"}, "libs:shizuku": {"dir": "../ab_cloud-libs-shared/libs/shizuku"}}' "$UPD"
app a_fork  '{}' "dependencies {
    implementation project(':libs:core')
    // implementation project(':libs:fin')   left with the dashboard
}"
printf "include ':app', ':libs:core', ':libs:devtools'\n" > "$T/a_fork/settings.gradle"
mkdir -p "$T/a_fork/rootfs"
cat > "$T/a_fork/rootfs/rootfs.json" <<'EOF'
{
  "dns_bridge": {
    "_doc": "../ab_cloud-libs-shared/libs/fin is only mentioned here",
    "source": "ab_cloud-libs-shared/libs/sysdns/data/sysdns.json"
  },
  "repo_url": "https://github.com/x/y/tree/main/ab_cloud-libs-shared/libs/fin",
  "note": "ab_cloud-libs-shared/libs/fin/.../Thing.kt, reached by launch intent."
}
EOF

inputs() { python3 "$MOD" "$T" "$1" | sed 's/^[^ ]* *//'; }
check() {  # check <label> <app> <want>
    local got; got="$(inputs "$2")"
    [ "$got" = "$3" ] && ok "$1" || fail "$1 — got '$got', want '$3'"
}

check "a conditional lib edge does not reach a module the app never includes" \
      a_plain "core(c) devtools(c) updater(c)"
check "the same edge does reach it once the app includes it" \
      a_shiz  "core(c) devtools(c) shizuku(c) updater(c)"
check "a commented-out dependency, a _doc, a repo URL and prose are mentions; a path value is a source" \
      a_fork  "core(c) devtools(c) sysdns(s)"

echo
if [ "$FAILURES" -eq 0 ]; then echo "all passed"; exit 0; fi
echo "FAIL — $FAILURES assertion(s)"; exit 1
