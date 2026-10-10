#!/usr/bin/env bash
# An import (Store ▸ Phone "Install all missing", /api/store/import?run=1) downloads every app first,
# then installs. It ran on a thread inside the Store, so a Store self-update replaced the process
# mid-batch and the import was gone: 5 hours of downloads, nothing installed. The inventory is now
# remembered until the run FINISHES and the Store resumes it on start; the cache keeps what was
# already downloaded.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
API="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreDebugApi.kt"
APP="$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
check() { # $1 api $2 app
  python3 - "$1" "$2" <<'PY'
import re, sys
api, app = open(sys.argv[1]).read(), open(sys.argv[2]).read()
run = api[api.index('if (run && plan.direct.isNotEmpty())'):]
run = run[:run.index('return out')]
w, t, i, dl = run.find('pendingImport(app).writeText(body)'), run.find('thread(name = "store-import-api")'), run.find('StoreImport.installMissing('), run.find('pendingImport(app).delete()')
assert 0 <= w < t < i < dl, ("order", w, t, i, dl)
assert 'fun resumePendingImport(ctx: Context)' in api and 'importInventory(ctx, body, run = true)' in api
assert 'StoreDebugApi.resumePendingImport(this)' in app
PY
}
echo "== the import survives a Store restart"
check "$API" "$APP" && ok "remembered before the run, forgotten after it finishes, resumed on Store start" || bad "an interrupted import is lost"
T="$(mktemp)"; trap 'rm -f "$T"' EXIT
sed 's/                pendingImport(app).delete()//' "$API" > "$T"; check "$T" "$APP" >/dev/null 2>&1 && bad "mutation (never forgotten) not caught" || ok "mutation caught: the record is never removed"
sed 's/StoreDebugApi.resumePendingImport(this)/Unit/' "$APP" > "$T"; check "$API" "$T" >/dev/null 2>&1 && bad "mutation (no resume on start) not caught" || ok "mutation caught: the Store does not resume on start"
echo; echo "== RESULT: $PASS passed, $FAIL failed =="; [ "$FAIL" -eq 0 ]
