#!/usr/bin/env bash
# density-guard.test — prove cloud-android-density-guard.py FAILS on each defect and PASSES the clean fleet.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-density-guard.py"
POLICY="$ROOT/1_cicd/src/data/density-guard.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK:?}"' EXIT

OWNER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["owner"])' "$POLICY")"
APP="ac_cloud-store/app/src/main/java/demo"

fixture() {  # $1 = dir
  mkdir -p "$1/$(dirname "$OWNER")" "$1/$APP"
  cat > "$1/$OWNER" <<'KT'
fun kitTypography() = Typography()
private fun TextStyle.dense() = copy(lineHeight = TextUnit.Unspecified)
fun KitDenseMaterial() { LocalMinimumInteractiveComponentSize provides 36.dp }
KT
  cat > "$1/$APP/Main.kt" <<'KT'
fun Shell() {
    KitDenseMaterial(darkColorScheme()) { Scaffold(contentWindowInsets = WindowInsets(0)) { } }
    MaterialTheme(colorScheme = c, typography = kitTypography()) { }
}
KT
}
expect() {  # name want(0|1) dir
  python3 "$GUARD" "$3" --policy "$POLICY" >"$WORK/out" 2>&1; local rc=$?
  [ "$rc" = "$2" ] && ok "$1" || { fail "$1 (exit $rc, wanted $2)"; cat "$WORK/out"; }
}
mutate() {  # name file-to-append text
  local d="$WORK/$1"; fixture "$d"; printf '%s\n' "$3" >> "$d/$2"; expect "$1" 1 "$d"
}

fixture "$WORK/clean"; expect "clean fleet passes" 0 "$WORK/clean"
mutate bare-material-theme "$APP/Main.kt" 'fun X() { MaterialTheme(colorScheme = darkColorScheme()) { } }'
mutate scaffold-without-insets "$APP/Main.kt" 'fun Y() { Scaffold(topBar = { }) { } }'
mutate nested-system-bars "$APP/Main.kt" 'fun Z() { Box(Modifier.systemBarsPadding()) }'
mutate nested-safe-drawing-insets "$APP/Main.kt" 'fun Z2() { Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) }'
# a comment naming the defect is not a defect
d="$WORK/comment"; fixture "$d"; echo '// MaterialTheme(colorScheme = x) and Scaffold( are banned' >> "$d/$APP/Main.kt"; expect "comments are ignored" 0 "$d"
# a test may name them
d="$WORK/intest"; fixture "$d"; mkdir -p "$d/ac_cloud-store/app/src/test/java"; echo 'fun t() { MaterialTheme(colorScheme = c) }' > "$d/ac_cloud-store/app/src/test/java/T.kt"; expect "tests are skipped" 0 "$d"
# owner decays
for needle in "fun kitTypography" "fun KitDenseMaterial" "LocalMinimumInteractiveComponentSize" "lineHeight = TextUnit.Unspecified"; do
  d="$WORK/owner-${needle//[^a-zA-Z]/_}"; fixture "$d"; python3 - "$d/$OWNER" "$needle" <<'PY'
import sys
p,n=sys.argv[1:]; s=open(p).read().replace(n,"REMOVED"); open(p,"w").write(s)
PY
  expect "owner lost '$needle'" 1 "$d"
done
d="$WORK/noowner"; fixture "$d"; rm "$d/$OWNER"; expect "owner file missing" 1 "$d"
mkdir -p "$WORK/empty"; expect "nothing scanned is refused" 2 "$WORK/empty"
python3 "$GUARD" "$ROOT" >/dev/null 2>&1 && ok "real tree passes" || { fail "real tree"; python3 "$GUARD" "$ROOT"; }
[ "$FAILURES" = 0 ] && echo "density-guard.test: all ok" || { echo "$FAILURES failed"; exit 1; }
