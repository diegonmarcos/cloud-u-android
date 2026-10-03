#!/usr/bin/env bash
# licence-inventory-guard.test.sh — #805: proves cloud-android-licence-inventory.py
# `check` fails on every kind of unrecorded subject and stays green on changes
# that must not need a new entry. Runs on a throw-away fixture repository, never
# on the real tree, and touches no network (refresh --offline).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
inv="$here/cloud-android-licence-inventory.py"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
fail=0

fixture() {
  rm -rf "$work/r"; mkdir -p "$work/r"; cd "$work/r"
  git init -q . && git config user.email t@t && git config user.name t
  mkdir -p licenses app/src fork/sub libs/x
  cat > licenses/curated.json <<'J'
{"scan_skip_prefixes":["z_archive/"],"asset_extensions":[".so",".jar",".ttf"],"maven_repos":[],
 "maven_group_licences":{"androidx":{"licence":"Apache-2.0"}},
 "directories":{"app":{"licence":"LicenseRef-NoLicenseGranted"},"fork":{"licence":"MIT"},
                "libs":{"licence":"LicenseRef-NoLicenseGranted"},"licenses":{"licence":"LicenseRef-NoLicenseGranted"}},"assets":{}}
J
  echo '{"modules":[{"id":"fork","path":"fork","upstream":"F","licence":"MIT","repo":"x","ref":""}]}' > licenses/upstreams.json
  printf 'dependencies {\n    implementation "androidx.core:core:1.0.0"\n}\n' > app/build.gradle
  echo '{"name":"app","dependencies":{"left-pad":"1.0.0","fork-internal":"workspace:*"}}' > app/package.json
  echo '{"name":"fork-internal"}' > fork/package.json
  echo MIT > fork/LICENSE; echo MIT > fork/sub/LICENSE.md
  echo bin > fork/lib.so; echo x > app/src/a.kt; echo x > libs/x/a.kt
  git add -A && git commit -qm base
  python3 "$inv" refresh . --offline 2>/dev/null
  git add -A && git commit -qm inv
}

expect() {  # expect <0|1> <label>
  local rc=0; python3 "$inv" check . >/dev/null 2>&1 || rc=$?
  if [ "$rc" = "$1" ]; then echo "ok   $2"; else echo "FAIL $2 (exit $rc, wanted $1)"; fail=1; fi
}

fixture; expect 0 "baseline fixture is clean"

# ── mutations the guard must catch ──
fixture; printf 'dependencies {\n    implementation("com.example:new-lib:2.0")\n}\n' > libs/x/build.gradle; git add -A
expect 1 "new Maven coordinate in a build.gradle"

fixture; mkdir -p app/gradle; printf '[versions]\nv = "1"\n[libraries]\nfoo = { module = "org.foo:foo", version.ref = "v" }\n' > app/gradle/libs.versions.toml; git add -A
expect 1 "new library in a version catalog"

fixture; echo '{"name":"app","dependencies":{"left-pad":"1.0.0","is-odd":"3.0.0","fork-internal":"workspace:*"}}' > app/package.json; git add -A
expect 1 "new npm dependency"

fixture; mkdir -p libs/vendored; echo GPL > libs/vendored/COPYING; git add -A
expect 1 "new vendored directory carrying a licence file"

fixture; echo bin > libs/x/libfoo.so; git add -A
expect 1 "new native library binary"

fixture; mkdir -p newtop; echo x > newtop/a.txt; git add -A
expect 1 "new top-level directory"

fixture; python3 - <<'P'
import json; p="licenses/inventory.json"; d=json.load(open(p)); d["entries"].pop("npm:left-pad"); json.dump(d,open(p,"w"))
P
expect 1 "inventory entry deleted"

fixture; python3 - <<'P'
import json; p="licenses/curated.json"; d=json.load(open(p)); d["directories"].pop("libs"); json.dump(d,open(p,"w"))
P
python3 "$inv" refresh . --offline 2>/dev/null
expect 1 "top-level directory with no curated licence (refreshed anyway)"

# ── changes that must NOT need an entry ──
fixture; sed -i 's/core:1.0.0/core:1.1.0/' app/build.gradle; git add -A
expect 0 "version bump of a recorded coordinate"

fixture; mkdir -p fork/deeper; echo MIT > fork/deeper/LICENSE; git add -A
expect 0 "licence file inside a declared upstream module"

fixture; echo x > app/licence-guard.yml; git add -A
expect 0 "a file merely named like a licence is not a vendored directory"

exit $fail
