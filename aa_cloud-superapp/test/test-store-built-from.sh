#!/usr/bin/env bash
# Tester (#820): the Store shows the commit each build came from, read by ONE
# reader (libs/appstore BuiltFrom) from the two places CI writes it:
#   T1  the writer (publish gate `stamp`) puts the 40-hex commit on line 2
#   T2  BuiltFrom reads line 2 (index 1), demands 40 hex, else "unknown"
#   T3  BuiltFrom reads the installed sha from the "(sha-<hex>)" versionName
#   T4  the superapp bakes that versionName + GIT_SHORT_SHA from GITHUB_SHA (its About)
#   T5  Store Details renders both Installed and Available built-from via BuiltFrom
#   T6  nobody else fetches the `.source` sidecar (one reader)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
L="$ROOT/ab_cloud-libs-shared/libs"
BF="$L/appstore/src/main/java/com/diegonmarcos/superapp/appstore/BuiltFrom.kt"
DS="$L/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ApkDetailSheet.kt"
GATE="$ROOT/1_cicd/src/scripts/cloud-android-publish-gate.sh"
SG="$ROOT/aa_cloud-superapp/app/build.gradle"
fail=0; ok(){ echo "  ok   $*"; }; bad(){ echo "  FAIL $*"; fail=1; }

grep -q "printf '%s\\\\n%s\\\\n' \"\$IDENTITY\" \"\$COMMIT\"" "$GATE" && ok "T1 writer stamps identity + commit" || bad "T1 writer no longer writes line 2 = commit"
grep -q 'lines()?.getOrNull(1)' "$BF" && ok "T2 reads line 2" || bad "T2 BuiltFrom does not read line 2"
grep -q 'Regex("^\[0-9a-f\]{40}\$")' "$BF" && ok "T2 demands 40 hex" || bad "T2 40-hex check missing"
grep -q 'const val UNKNOWN = "unknown"' "$BF" && ok "T2 legacy -> unknown" || bad "T2 unknown fallback missing"
grep -q 'sha-(\[0-9a-f\]' "$BF" && ok "T3 versionName sha" || bad "T3 versionName sha pattern missing"
grep -q 'versionName "${buildJson.android.version_name} (sha-${shortSha})"' "$SG" && grep -q 'GITHUB_SHA' "$SG" \
  && grep -q 'GIT_SHORT_SHA' "$SG" && ok "T4 superapp bakes its sha" || bad "T4 superapp sha bake changed"
grep -q 'BuiltFrom.shaFromVersionName(d.versionName)' "$DS" && ok "T5 installed built-from" || bad "T5 installed built-from missing"
grep -q 'BuiltFrom.fetchReleaseCommit(app)' "$DS" && grep -q '"Available built from"' "$DS" && ok "T5 available built-from" || bad "T5 available built-from missing"
others="$(grep -rl '\.source"' "$L" --include=*.kt | grep -v '/BuiltFrom.kt$' || true)"
[ -z "$others" ] && ok "T6 one reader" || bad "T6 second .source reader: $others"
exit $fail
