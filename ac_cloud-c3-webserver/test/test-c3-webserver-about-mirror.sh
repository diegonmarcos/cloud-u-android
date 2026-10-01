#!/bin/sh
# test-c3-webserver-about-mirror.sh — Home mirrors cloud-superapp's
# Configs ▸ About, label for label.
#
# The Home tab is an about-this-phone page in the SHAPE of superapp's About:
# macro groups under superapp's own headers, in superapp's order. The groups
# live in server/src/about.rs::MACROS; superapp's live in DevControlFragment.kt
# as macroHeader(ctx, "...") calls. A label typed slightly differently, a
# group superapp does not have, or two groups swapped is the drift this
# catches. Groups superapp has that a plain process cannot fill (REPO &
# RELEASES, MESH, DEV TOOLS, LOGCAT) may be absent: the check is "an ordered
# subset", not "equal".
#
# Kept out of test-c3-webserver.sh on purpose: this file reads superapp's
# source, so the test engine treats it as reaching outside this application.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
SUPER="$(cd "$APP/.." && pwd)/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt"
[ -f "$SUPER" ] || { echo "ERROR missing $SUPER — this tester is unrun, not passing"; exit 1; }

mirror() {  # mirror <about.rs> <DevControlFragment.kt>
  python3 - "$1" "$2" <<'PY'
import re, sys
about = open(sys.argv[1], encoding="utf-8").read()
block = re.search(r"pub const MACROS: &\[&str\] = &\[(.*?)\];", about, re.S)
if not block:
    print("  FAIL about.rs has no `pub const MACROS: &[&str]`"); sys.exit(1)
mine = re.findall(r'"([^"]+)"', block.group(1))
theirs = re.findall(r'macroHeader\(ctx, "([^"]+)"\)', open(sys.argv[2], encoding="utf-8").read())
bad = []
if not mine:
    bad.append("about.rs::MACROS is empty — Home would have no groups")
if not theirs:
    bad.append("no macroHeader(ctx, \"...\") in DevControlFragment.kt — the detection matched nothing")
pos = -1
for label in mine:
    if label not in theirs:
        bad.append("Home group %r is not one of superapp's About headers %r" % (label, theirs)); continue
    i = theirs.index(label, pos + 1) if label in theirs[pos + 1:] else -1
    if i < 0:
        bad.append("Home group %r is out of superapp's order" % label); continue
    pos = i
for b in bad:
    print("  FAIL " + b)
if bad:
    sys.exit(1)
print("  ok   Home's %d groups are superapp's About headers, in superapp's order" % len(mine))
PY
}

echo "== Home mirrors superapp's Configs ▸ About groups =="
mirror "$APP/server/src/about.rs" "$SUPER"

echo "== mutations =="
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0
plant() {  # plant <name> <python expression on s>
  cp "$APP/server/src/about.rs" "$WORK/about.rs"
  python3 - "$WORK/about.rs" "$2" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
new = eval(expr)
if new == s:
    sys.exit(3)
open(p, "w", encoding="utf-8").write(new)
PY
  rc=$?
  if [ "$rc" -ne 0 ]; then echo "  FAIL mutation $1 did not apply"; fails=$((fails + 1)); return 0; fi
  if mirror "$WORK/about.rs" "$SUPER" >/dev/null 2>&1; then
    echo "  FAIL mutation $1: stayed green"; fails=$((fails + 1))
  else
    echo "  ok   mutation $1: red"
  fi
}
set +e
plant label-retyped 's.replace("\"🔐  SECURITY\"", "\"🔐 SECURITY\"", 1)'
plant group-superapp-lacks 's.replace("\"🔌  API\",", "\"🔌  API\",\n    \"🧪  LAB\",", 1)'
plant groups-swapped 's.replace("\"🖥️  DEVICE\",\n    \"🔐  SECURITY\",", "\"🔐  SECURITY\",\n    \"🖥️  DEVICE\",", 1)'
set -e

[ "$fails" -eq 0 ] || { echo "$fails mutation(s) failed"; exit 1; }
echo "all mutations red"
