#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ top-bar-guard.test — prove the guard FAILS, and prove it can     ║
# ║ pass, so a green result means something                          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard only ever watched succeeding is indistinguishable from `exit 0`.
# cloud-android-top-bar-guard.py holds every app that declares build.json::ui.top_bar to libs:bottomnav's
# AppTopBar. This file builds a small fleet around the REAL policy (1_cicd/src/data/top-bar-guard.json, so the
# rules and needles are the ones CI runs), requires GREEN for the clean fleet and for the shapes that must stay
# legal (a test naming the icons, an app that does NOT declare the bar and keeps its own, a web tree), then plants
# each defect and requires RED: a hand-rolled hamburger, drawer, top app bar and Cloud Account launch (the
# "copy of the bar" cases), a declared bar that is never drawn, one that is not baked, a settings entry the bar
# does not know, a bar that declares nothing, a missing libs:bottomnav / libs:ai, an AI panel nobody fills, an
# Account / Configs entry nobody wires, an owner that lost a file or a needle, no owner. Nothing declared at all
# must be REFUSED (2), not passed. Finally the guard runs on the real tree.
#
# Nothing outside coreutils + python3.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-top-bar-guard.py"
POLICY="$ROOT/1_cicd/src/data/top-bar-guard.json"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK:?}"' EXIT

OWNER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["owner"])' "$POLICY")"
APPKT="app/src/main/java/demo"

# The fleet the policy expects to be green: the owner holds the bar (stubs carrying exactly the needles the
# policy names), one app declares the bar and draws it with the shared scaffold, one app does not declare it.
build_fixture() {
  local t="$1"
  python3 - "$POLICY" "$t" <<'PY'
import json, os, sys
policy, t = sys.argv[1], sys.argv[2]
cfg = json.load(open(policy))
for need in cfg["owner_must_hold"]:
    p = os.path.join(t, cfg["owner"], need["file"])
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write("\n".join(need["needles"]) + "\n")
PY
  mkdir -p "$t/ac_cloud-demo/$APPKT" "$t/ac_cloud-demo/app"
  cat > "$t/ac_cloud-demo/build.json" <<'EOF'
{"name":"cloud-demo","modules":{"app":{},"libs:bottomnav":{"dir":"../x"},"libs:ai":{"dir":"../y"}},
 "ui":{"bottom_nav":["a","b"],"top_bar":{"drawer":true,"ai":true,"settings":["account","configs"]}}}
EOF
  cat > "$t/ac_cloud-demo/app/build.gradle" <<'EOF'
android { defaultConfig { buildConfigField "String", "UI_TOP_BAR_B64", "\"${uiTopBarB64}\"" } }
EOF
  cat > "$t/ac_cloud-demo/$APPKT/Main.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
// A host: the bar is the shared one, the pages fill its drawer.
@Composable
fun Shell(sel: Sel) {
    AppTopBarScaffold(
        decl = TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64)!!,
        onAccount = rememberAccountLauncher(),
        onConfigs = { sel.configs() },
        aiPanel = { AiFleetChatPanel(BuildConfig.AI_GATEWAY_URL) },
    ) { Page() }
}
@Composable
fun Page() { TopBarDrawer { close -> Text("x") } }
EOF
  # an app that does not declare the bar keeps its own: not this guard's business
  mkdir -p "$t/ac_cloud-other/$APPKT"
  cat > "$t/ac_cloud-other/build.json" <<'EOF'
{"name":"cloud-other","ui":{"bottom_nav":["a","b"]}}
EOF
  cat > "$t/ac_cloud-other/$APPKT/Own.kt" <<'EOF'
package demo
import androidx.compose.runtime.Composable
@Composable
fun Own() { TopAppBar(); Icon(Icons.Filled.Menu, null); ModalNavigationDrawer() }
EOF
  # tests may name the icons and the intent to prove the bar's behaviour; a web tree is wasm, not the bar's app
  mkdir -p "$t/ac_cloud-demo/app/src/test/java/demo" "$t/ac_cloud-demo/web/src/wasmJsMain"
  cat > "$t/ac_cloud-demo/app/src/test/java/demo/BarTest.kt" <<'EOF'
package demo
class BarTest { val a = "com.diegonmarcos.cloudaccount.OPEN"; val i = Icons.Filled.Menu }
EOF
  cat > "$t/ac_cloud-demo/web/src/wasmJsMain/Web.kt" <<'EOF'
package web
fun x() { TopAppBar() }
EOF
}

# expect <exit code> <label> <fixture dir>
expect() {
  local want="$1" label="$2" dir="$3" out got
  out="$(python3 "$GUARD" "$dir" --policy "$POLICY" 2>&1)"; got=$?
  if [ "$got" -eq "$want" ]; then ok "$label (exit $got)"; else fail "$label: expected exit $want, got $got: $out"; fi
  LAST="$out"
}

# mutate <name>: a fresh clean fixture at $WORK/<name>
mutate() {
  local name="$1"
  rm -rf "${WORK:?}/${name:?}"; mkdir -p "${WORK:?}/${name:?}"; build_fixture "$WORK/$name"
}

# edit <name> <file under the fixture> <python expr over s>
edit() {
  python3 - "$WORK/$1/$2" "$3" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p).read()
t = eval(expr)
if t == s:
    sys.exit("mutation did not change " + p)
open(p, "w").write(t)
PY
}

contains() { [ "${1#*"$2"}" != "$1" ]; }

# ── GREEN: the clean fleet, and the shapes that must stay legal ──────────────
mutate clean
expect 0 "a clean fleet is green (shared scaffold, an app with no top_bar keeping its own, tests and web naming the parts)" "$WORK/clean"

# ── RED: a copy of the bar in the declaring app ──────────────────────────────
mutate hamburger
cat > "$WORK/hamburger/ac_cloud-demo/$APPKT/Bar.kt" <<'EOF'
package demo
fun Mine() { IconButton(onClick = {}) { Icon(Icons.Filled.Menu, "menu") } }
EOF
expect 1 "an app declaring ui.top_bar with its own hamburger icon is red" "$WORK/hamburger"
contains "$LAST" "own-hamburger" && ok "  ... naming the rule" || fail "  ... the message does not name the rule: $LAST"
contains "$LAST" "AppTopBar" && ok "  ... and teaching the owner" || fail "  ... the message does not teach where the bar lives: $LAST"

mutate hamburger2
cat > "$WORK/hamburger2/ac_cloud-demo/$APPKT/Bar.kt" <<'EOF'
package demo
fun Mine() { Icon(Icons.AutoMirrored.Filled.Menu, "menu") }
EOF
expect 1 "the auto-mirrored Menu icon is red too" "$WORK/hamburger2"

mutate drawer
cat > "$WORK/drawer/ac_cloud-demo/$APPKT/Bar.kt" <<'EOF'
package demo
fun Mine() { ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { } }) { } }
EOF
expect 1 "an app declaring ui.top_bar with its own navigation drawer is red" "$WORK/drawer"

mutate topappbar
cat > "$WORK/topappbar/ac_cloud-demo/$APPKT/Bar.kt" <<'EOF'
package demo
fun Mine() { CenterAlignedTopAppBar(title = { Text("x") }) }
EOF
expect 1 "an app declaring ui.top_bar with its own top app bar is red" "$WORK/topappbar"

mutate account
cat > "$WORK/account/ac_cloud-demo/$APPKT/Bar.kt" <<'EOF'
package demo
fun open(c: Context) { c.startActivity(Intent("com.diegonmarcos.cloudaccount.OPEN")) }
EOF
expect 1 "a hand-rolled Cloud Account launch is red" "$WORK/account"

# ── RED: the declared bar is not the shared one, or is wrong ─────────────────
mutate nobar
edit nobar "ac_cloud-demo/$APPKT/Main.kt" "s.replace('AppTopBarScaffold(', 'MyBar(')"
expect 1 "a declared bar nobody draws with the scaffold is red" "$WORK/nobar"

mutate nobake
edit nobake "ac_cloud-demo/app/build.gradle" "s.replace('UI_TOP_BAR_B64', 'UI_TOP')"
expect 1 "a declaration the gradle never bakes is red" "$WORK/nobake"

mutate unknownsetting
edit unknownsetting "ac_cloud-demo/build.json" "s.replace('\"configs\"]', '\"configs\",\"logout\"]')"
expect 1 "a settings entry the bar does not know is red" "$WORK/unknownsetting"

mutate dupsetting
edit dupsetting "ac_cloud-demo/build.json" "s.replace('\"account\",\"configs\"', '\"account\",\"account\",\"configs\"')"
expect 1 "a settings entry declared twice is red" "$WORK/dupsetting"

mutate empty_decl
edit empty_decl "ac_cloud-demo/build.json" "s.replace('\"drawer\":true,\"ai\":true,\"settings\":[\"account\",\"configs\"]', '\"drawer\":false')"
expect 1 "a top_bar that declares nothing is red" "$WORK/empty_decl"

mutate nobottomnav
edit nobottomnav "ac_cloud-demo/build.json" "s.replace('\"libs:bottomnav\":{\"dir\":\"../x\"},', '')"
expect 1 "an app that declares the bar but does not compile libs:bottomnav is red" "$WORK/nobottomnav"

mutate noai
edit noai "ac_cloud-demo/build.json" "s.replace(',\"libs:ai\":{\"dir\":\"../y\"}', '')"
expect 1 "ai declared without libs:ai is red" "$WORK/noai"

mutate aipanel
edit aipanel "ac_cloud-demo/$APPKT/Main.kt" "s.replace('AiFleetChatPanel(', 'Placeholder(')"
expect 1 "ai declared but the panel is not libs:ai's chat is red" "$WORK/aipanel"

mutate noaccount
edit noaccount "ac_cloud-demo/$APPKT/Main.kt" "s.replace('rememberAccountLauncher()', '{}')"
expect 1 "Account declared but not wired to the shared launcher is red" "$WORK/noaccount"

mutate noconfigs
edit noconfigs "ac_cloud-demo/$APPKT/Main.kt" "s.replace('onConfigs =', 'onOther =')"
expect 1 "Configs declared but not wired is red" "$WORK/noconfigs"

# ── RED: the owner stops holding the bar ─────────────────────────────────────
mutate ownerfile
rm "$WORK/ownerfile/$OWNER/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBar.kt"
expect 1 "an owner that lost the bar's file is red" "$WORK/ownerfile"

mutate ownerneedle
edit ownerneedle "$OWNER/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBar.kt" "s.replace('fun TopBarDrawer(', 'fun SomethingElse(')"
expect 1 "an owner whose drawer slot is no longer there is red" "$WORK/ownerneedle"

mutate noowner
rm -rf "${WORK:?}/noowner/${OWNER:?}"
expect 1 "no owner tree at all is red" "$WORK/noowner"

# ── 2: nothing declared is not a verdict ─────────────────────────────────────
mutate none
rm -rf "${WORK:?}/none/ac_cloud-demo"
expect 2 "a fleet where no app declares a top bar is refused, not passed" "$WORK/none"

# ── the real tree ────────────────────────────────────────────────────────────
expect 0 "the real fleet is green" "$ROOT"

if [ "$FAILURES" -eq 0 ]; then
  echo "top-bar-guard.test: all assertions passed"
else
  echo "top-bar-guard.test: $FAILURES assertion(s) FAILED"
  exit 1
fi
