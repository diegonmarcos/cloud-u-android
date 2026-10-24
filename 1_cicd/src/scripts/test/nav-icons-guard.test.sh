#!/usr/bin/env bash
# nav-icons-guard.test — prove the nav-icons guard (#1160) FAILS on each rule it holds, and passes on the real tree.
# (1) the real tree passes. (2) a synthetic tree (the real data file + the real catalog + one fixture app) passes, then is
# broken one rule at a time; each break must be proven to have landed and the guard must go red NAMING the rule.
# python3 and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-nav-icons-guard.py"
DATA=1_cicd/src/data/nav-icons.json
CAT=ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$CAT"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
T="$WORK/t"
FIX=ac_cloud-fix
KT=$FIX/app/src/main/kotlin/FixNav.kt

out="$(cd "$ROOT" && python3 "$GUARD" .)"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the real tree passes ($(tail -1 <<<"$out"))"; else fail "the real tree is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

fixture() {
    rm -rf "$T"; mkdir -p "$T/1_cicd/src/data" "$T/$(dirname "$CAT")" "$T/$(dirname "$KT")"
    cp "$ROOT/$DATA" "$T/$DATA"; cp "$ROOT/$CAT" "$T/$CAT"
    # the exceptions name real files in the real tree; the fixture has none of them, so drop them (and their I4 check)
    python3 - "$T/$DATA" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["exceptions"] = {}; json.dump(d, open(p, "w"))
PY
    cat > "$T/$FIX/build.json" <<'J'
{"ui": {"bottom_nav": ["inbox", "me"], "sections": [{"id": "inbox", "label": "Inbox", "icon": "inbox", "pages": [{"id": "a", "label": "A", "icon": "search"}]}, {"id": "me", "label": "Me", "icon": "profile"}]}}
J
    cat > "$T/$KT" <<'K'
package fix
import com.diegonmarcos.superapp.bottomnav.islandEntries
// a comment may name Icons.Filled.Home, androidx.compose.material.icons and BottomNavEntry( without breaking anything
fun entries(nav: NavDecl) = nav.islandEntries()
K
}
run() { (cd "$T" && python3 "$GUARD" . 2>&1); }
expect_red() { # name, rule fragment
    local out; out="$(run)"; local rc=$?
    if [ "$rc" -ne 0 ] && grep -q -- "$2" <<<"$out"; then ok "$1 -> red ($2)"; else fail "$1 stayed green or named the wrong rule (rc=$rc): $out"; fi
}
apply() { # python replace on a fixture file; fail loudly if it did not land
    python3 - "$T/$1" "$2" "$3" <<'PY' || { fail "the break did not land: $2"; return 1; }
import sys
p, old, new = sys.argv[1:4]; s = open(p).read(); n = s.replace(old, new, 1)
sys.exit(1) if n == s else open(p, "w").write(n)
PY
}

fixture
out="$(run)"; rc=$?
[ "$rc" -eq 0 ] && ok "the fixture passes (comments naming forbidden things stay green)" || { fail "the fixture is red"; printf '%s\n' "$out"; }

fixture; apply "$KT" "nav.islandEntries()" "nav.islandEntries { rememberVectorPainter(Icons.Filled.Home) }" && expect_red "lambda overload (the app picks glyphs)" "I2 .*lambda islandEntries"
fixture; apply "$KT" "nav.islandEntries()" "listOf(BottomNavEntry(\"a\", \"A\", p))" && expect_red "hand-built BottomNavEntry" "I2 .*BottomNavEntry by hand"
fixture; apply "$KT" "nav.islandEntries()" "nav.viewItems { 0 }" && expect_red "drawable-id feed" "I2 .*viewItems"
fixture; apply "$KT" "package fix" "package fix
import androidx.compose.material.icons.Icons" && expect_red "material import in a nav file" "I2 .*imports a material icon"
fixture; apply "$KT" "nav.islandEntries()" "nav.islandEntries().also { Icons.Filled.Home }" && expect_red "material glyph in a nav file" "I2 .*Icons"
fixture; apply "$FIX/build.json" '"icon": "profile"' '"icon": "no_such_glyph"' && expect_red "a section icon outside the catalog" "I3 .*no_such_glyph"
fixture; apply "$FIX/build.json" '"icon": "search"' '"icon": "no_such_page_glyph"' && expect_red "a page icon outside the catalog" "I3 .*no_such_page_glyph"
fixture; apply "$CAT" '"profile" to' '"profilx" to' && expect_red "a semantic name dropped from the catalog" "I1 .*profile"
fixture; apply "$CAT" '"assistant" to assistantMark' '"assistan" to assistantMark' && expect_red "the brand entry dropped" "I1 .*assistant"
fixture; rm "$T/$KT"; expect_red "no nav declaration at all (vacuous)" "I5"
fixture; python3 - "$T/$DATA" "$KT" <<'PY'
import json, sys
p, kt = sys.argv[1:3]; d = json.load(open(p)); d["exceptions"] = {kt: "stale"}; json.dump(d, open(p, "w"))
PY
expect_red "an exception that already passes is stale" "I4"
fixture; python3 - "$T/$DATA" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["exceptions"] = {"ac_cloud-gone/X.kt": "gone"}; json.dump(d, open(p, "w"))
PY
expect_red "an exception for a missing file" "I4"

[ "$FAILURES" -eq 0 ] && { echo "nav-icons-guard.test: all assertions passed"; exit 0; }
echo "nav-icons-guard.test: $FAILURES FAILED"; exit 1
