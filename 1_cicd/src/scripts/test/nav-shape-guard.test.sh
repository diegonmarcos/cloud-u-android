#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ nav-shape-guard.test — prove the nav-shape guard FAILS on each rule   ║
# ║ it holds, and passes on the real tree                                 ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# #868. Two parts. (1) the real tree must pass: today every app is exempt with a
# reason. (2) a synthetic fleet — one fully migrated app, one exempt app, the
# real data file and the real libs:bottomnav sources — is required to pass, then
# broken one rule at a time; each break must be proven to have landed and the
# guard must go red NAMING the rule. A comment that merely mentions a forbidden
# widget must stay green (the guard reads code, not prose).
#
# python3, git and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-nav-shape-guard.py"
DATA=1_cicd/src/data/nav-shape.json
LIB=ab_cloud-libs-shared/libs/bottomnav
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabs.kt"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
T="$WORK/t"
FIX=ac_cloud-fix
KT=$FIX/app/src/main/kotlin/Main.kt

# ── (1) the real tree ───────────────────────────────────────────────────────
out="$(cd "$ROOT" && python3 "$GUARD" .)"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the real tree passes ($(tail -1 <<<"$out"))"
else fail "the real tree is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

# the real tree with an unmigrated app's exemption dropped: it is held to N1-N5 and fails
rm -rf "$T"; mkdir -p "$T"
(cd "$ROOT" && git ls-files -z | grep -zE '^a[ac]_[^/]+/(build\.json|build\.gradle(\.kts)?|app/build\.gradle(\.kts)?)$' \
    | xargs -0 cp --parents -t "$T")
mkdir -p "$T/1_cicd/src/data" "$T/$LIB"; cp "$ROOT/$DATA" "$T/$DATA"; cp -r "$ROOT/$LIB/src" "$T/$LIB/src"
# (whichever app is still exempt AND has no ui.bottom_nav yet: every migration batch removes one, so none is named here)
dropped="$(python3 - "$T" "$DATA" <<'PY'
import json, os, sys
t, data = sys.argv[1:3]
p = os.path.join(t, data); d = json.load(open(p))
for app in d["exempt"]:
    try:
        ui = json.load(open(os.path.join(t, app, "build.json"))).get("ui") or {}
    except (OSError, ValueError):
        continue
    if not ui.get("bottom_nav"):
        del d["exempt"][app]
        with open(p, "w") as fh:
            json.dump(d, fh)
        print(app)
        break
PY
)"
out="$(python3 "$GUARD" "$T")"; rc=$?
if [ -n "$dropped" ] && [ "$rc" -eq 1 ] && grep -qF "N1 $dropped" <<<"$out"; then ok "dropping an unmigrated app's exemption goes red (N1 $dropped)"
else fail "dropping ${dropped:-any unmigrated app}'s exemption stayed green (rc=$rc)"; printf '%s\n' "$out" | tail -3; fi

# ── (2) the synthetic fleet ─────────────────────────────────────────────────
stage() {
    rm -rf "$T"; mkdir -p "$T/$FIX/app/src/main/res/layout" "$T/$FIX/app/src/main/kotlin" "$T/ac_cloud-old" "$T/1_cicd/src/data" "$T/$LIB"
    cp -r "$ROOT/$LIB/src" "$T/$LIB/src"
    python3 - "$ROOT/$DATA" "$T/$DATA" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
d["exempt"] = {"ac_cloud-old": "Batch Z (synthetic): this fixture app has not migrated yet."}
json.dump(d, open(sys.argv[2], "w"))
PY
    cat > "$T/$FIX/build.json" <<'J'
{"ui": {"bottom_nav": ["a", "b"], "default_section": "a",
        "sections": [{"id": "a", "label": "A", "icon": "x", "pages": [{"id": "p1", "label": "P1"}, {"id": "p2", "label": "P2", "pages": [{"id": "q1", "label": "Q1"}]}]},
                     {"id": "b", "label": "B", "icon": "y"}]}}
J
    echo '{"ui": {}}' > "$T/ac_cloud-old/build.json"
    cat > "$T/$FIX/app/build.gradle" <<'G'
dependencies { implementation project(':libs:bottomnav') }
android { defaultConfig {
    buildConfigField "String", "UI_BOTTOM_NAV", "\"[]\""
    buildConfigField "String", "UI_SECTIONS_B64", "\"\""
} }
G
    cat > "$T/$KT" <<'K'
package fix
// A comment may talk about BottomNavigationView and TabLayout and TabRow( freely.
fun go() { PageTabs(pages, null, {}); Sections.byId("a"); open("page:a/q1"); FleetChrome.apply(this)
    BottomNavIsland(entries, null, onSelect = { height = 4 }) }
K
    echo '<LinearLayout/>' > "$T/$FIX/app/src/main/res/layout/main.xml"
    mkdir -p "$T/$FIX/app/src/main/res/values"
    echo '<resources><!-- bottom_nav_pill in prose is fine --><color name="app_bg">#000000</color></resources>' > "$T/$FIX/app/src/main/res/values/colors.xml"
}

# mutate <label> <file> <python-edit-of-s> <expected-substring>
mutate() {
    stage
    local before after out rc
    before="$(cat "$T/$2")"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$T/$2"
    after="$(cat "$T/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
}
J() { printf "d=json.loads(s); %s; s=json.dumps(d)" "$1"; }

stage
out="$(python3 "$GUARD" "$T")"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the unbroken synthetic fleet passes ($(tail -1 <<<"$out"))"
else fail "the unbroken synthetic fleet is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

mutate "the lib loses PageTabsView.kt's class"  "$LIB/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabsView.kt" \
    "s=s.replace('class PageTabsView','class Gone')" "N0 $LIB: PageTabsView.kt no longer declares"
mutate "the lib loses PageTabs.kt's function (#876: it lives in commonMain)" "$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabs.kt" \
    "s=s.replace('fun PageTabs(','fun Gone(')" "N0 $LIB: PageTabs.kt no longer declares"
mutate "six ids on the bar"                      "$FIX/build.json" "$(J "d['ui']['bottom_nav']=list('abcdef')")" "N1 $FIX: ui.bottom_nav has 6 ids"
mutate "a bar id that is no section"             "$FIX/build.json" "$(J "d['ui']['bottom_nav']=['a','zzz']")" "N1 $FIX: ui.bottom_nav id 'zzz'"
mutate "a default outside the bar"               "$FIX/build.json" "$(J "d['ui']['default_section']='nope'")" "N1 $FIX: ui.default_section 'nope'"
mutate "no bottomnav dependency"                 "$FIX/app/build.gradle" "s=s.replace(\"implementation project(':libs:bottomnav')\",'')" "N2 $FIX: does not compile libs:bottomnav"
mutate "a local TabLayout"                       "$KT" "s+='\nval t = TabLayout(ctx)\n'" "N3 $FIX: $KT:6"
mutate "a Material TabRow"                       "$KT" "s+='\nfun f() { TabRow(0) {} }\n'" "(TabRow)"
mutate "a local @Composable BottomNav"           "$KT" "s+='\n@Composable fun BottomNav() {}\n'" "(local BottomNav)"
mutate "a NavigationBar"                         "$KT" "s+='\nfun f() { NavigationBar { } }\n'" "(NavigationBar)"
mutate "a BottomNavigationView in a layout"      "$FIX/app/src/main/res/layout/main.xml" "s='<LinearLayout><com.google.android.material.bottomnavigation.BottomNavigationView/></LinearLayout>'" "(BottomNavigationView)"
mutate "the sections blob is not baked"          "$FIX/app/build.gradle" "s=s.replace('UI_SECTIONS_B64','OTHER')" "N4 $FIX: build.gradle does not bake UI_SECTIONS_B64"
mutate "a section literal build.json lacks"      "$KT" "s=s.replace('byId(\"a\")','byId(\"zzz\")')" "names section 'zzz'"
mutate "a page deep link build.json lacks"       "$KT" "s=s.replace('page:a/q1','page:a/zzz')" "names page:a/zzz"
mutate "multi-page sections drawn without strips" "$KT" "s=s.replace('PageTabs(pages, null, {});','')" "N5 $FIX:"
# N7: the look is the lib's alone (the unbroken fixture above passes with a nested `height = 4` inside the onSelect lambda: only top-level arguments count)
mutate "a colour scheme handed to the island"    "$KT" "s+='\nfun f() { BottomNavIsland(entries, null, {}, colorScheme = scheme) }\n'" "N7 $FIX: $KT:6 passes \`colorScheme\` to BottomNavIsland"
mutate "an inset handed to the host"             "$KT" "s+='\nfun f() { BottomNavHost(entries, null, {}, insets = WindowInsets(0)) {} }\n'" "passes \`insets\` to BottomNavHost"
mutate "a height handed to the strip"            "$KT" "s+='\nfun f() { PageTabs(pages, null, {}, height = 9) }\n'" "passes \`height\` to PageTabs"
mutate "a scheme assigned on the island view"    "$KT" "s+='\nfun g(v: BottomNavIslandView) { v.colorScheme = x }\n'" "assigns \`colorScheme\` on an island/strip view"
mutate "an inset set inside a strip view's apply" "$KT" "s+='\nval t = PageTabsView(c).apply {\n    insets = z\n}\n'" "assigns \`insets\` on an island/strip view"
mutate "an island colour of the app's own"       "$FIX/app/src/main/res/values/colors.xml" "s=s.replace('app_bg','bottom_nav_pill')" "declares \`bottom_nav_pill\`"
mutate "an island dimen of the app's own"        "$FIX/app/src/main/res/values/colors.xml" "s=s.replace('<color name=\"app_bg\">#000000</color>','<dimen name=\"page_tabs_pill_radius\">4dp</dimen>')" "declares \`page_tabs_pill_radius\`"
mutate "the window chrome is not called"         "$KT" "s=s.replace('FleetChrome.apply(this)','')" "N7 $FIX: no source calls FleetChrome.apply("
mutate "an exemption without a reason"           "$DATA" "$(J "d['exempt']['ac_cloud-old']='tbd'")" "N6 ac_cloud-old: exemption needs a reason"
mutate "an exemption for an app that is gone"    "$DATA" "$(J "d['exempt']['ac_cloud-gone']='Batch Z (synthetic): there is no such app any more.'")" "N6 ac_cloud-gone: exempt"
mutate "a migrated app still exempt"             "$DATA" "$(J "d['exempt']['$FIX']='Batch Z (synthetic): migrated but nobody deleted this.'")" "N6 $FIX: passes N1-N5 but is still exempt"

echo
[ "$FAILURES" -eq 0 ] && echo "nav-shape-guard.test: OK" || { echo "nav-shape-guard.test: $FAILURES FAILED"; exit 1; }
