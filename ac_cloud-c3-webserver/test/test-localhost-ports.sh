#!/bin/sh
# test-localhost-ports.sh -- no two apps declare the same localhost port.
#
# THE FAILURE THIS EXISTS FOR (option 37.1). Every Android terminal now serves its own disk
# through my-webserver on a loopback port, and this app (the Tauri server for
# /storage/emulated/0) used to bind 127.0.0.1:8000 too. All of them share the phone's ONE
# loopback: two listeners on one port is not an error anybody sees at build time -- the second
# to start gets EADDRINUSE on the phone, and which one "wins" depends on what the owner opened
# first that day.
#
# THE RULE. An app's localhost listening ports are declared in its build.json under `runtime`:
#   runtime.port           this app's one server     (ac_cloud-c3-webserver -> 8002; its second, runtime.diag.port -> 8003)
#   runtime.<name>.port    one per named server      (the terminals' runtime.webserver: 8000, 8001)
# This guard reads every <app>/build.json at the repo root and fails when
#   P1  two declarations name the same port,
#   P2  a declared port is not an integer in 1024..65535,
#   P3  a declared port lands on the fleet's reserved debug ports
#       (1_cicd/src/data/debug-ports.json: the gateway, every assigned port, the whole range),
#   P4  the option-37.1 assignment moved: c3-webserver 8002 (the drive) and 8003 (the fleet-diagnostics
#       page, runtime.diag.port), termux 8000, nix-on-droid 8001. These numbers are the owner's decision,
#       so a change to them must be a change here. (The names that stand for them are
#       test-c3-webserver-ports.sh's business.)
#
#   sh test-localhost-ports.sh          the guard on the real tree, then the mutations below
#   sh test-localhost-ports.sh check    the guard alone
#
# Offline, python3 only.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

guard() {
  python3 - "$1" <<'PY'
import glob, json, os, sys

root = sys.argv[1]
problems, seen = [], {}

def declared(app, blob):
    rt = blob.get("runtime")
    if not isinstance(rt, dict):
        return
    if "port" in rt:
        yield "runtime.port", rt["port"]
    for key, val in rt.items():
        if isinstance(val, dict) and "port" in val:
            yield "runtime.%s.port" % key, val["port"]

for path in sorted(glob.glob(os.path.join(root, "*", "build.json"))):
    app = os.path.basename(os.path.dirname(path))
    try:
        blob = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError) as e:
        problems.append("%s/build.json does not parse: %s" % (app, e))
        continue
    for where, port in declared(app, blob):
        name = "%s %s" % (app, where)
        if not isinstance(port, int) or isinstance(port, bool) or not 1024 <= port <= 65535:
            problems.append("P2 %s = %r is not an integer in 1024..65535" % (name, port))
            continue
        seen.setdefault(port, []).append(name)

for port, names in sorted(seen.items()):
    if len(names) > 1:
        problems.append("P1 127.0.0.1:%d is declared by %s" % (port, " AND ".join(names)))

dbg_path = os.path.join(root, "1_cicd", "src", "data", "debug-ports.json")
if os.path.isfile(dbg_path):
    dbg = json.load(open(dbg_path, encoding="utf-8"))
    reserved = {}
    gw = (dbg.get("gateway") or {}).get("port")
    if isinstance(gw, int):
        reserved[gw] = "the SuperApp gateway"
    for pkg, p in (dbg.get("ports") or {}).items():
        if isinstance(p, int):
            reserved[p] = "the debug port of " + pkg
    lo, hi = (dbg.get("range") or [0, -1])[:2]
    for port, names in sorted(seen.items()):
        why = reserved.get(port) or ("the debug-port range %d..%d" % (lo, hi) if lo <= port <= hi else None)
        if why:
            problems.append("P3 %s = %d collides with %s (1_cicd/src/data/debug-ports.json)" % (names[0], port, why))

want = {8002: "ac_cloud-c3-webserver runtime.port",
        8003: "ac_cloud-c3-webserver runtime.diag.port",
        8000: "ac_cloud-termux runtime.webserver.port",
        8001: "ac_cloud-nix-on-droid runtime.webserver.port"}
for port, name in sorted(want.items()):
    if name not in seen.get(port, []):
        problems.append("P4 option 37.1 assigns 127.0.0.1:%d to %s; it is not declared there" % (port, name))

for p in problems:
    print("::error::FAIL -- " + p)
if problems:
    sys.exit(1)
print("  ok   -- %d localhost ports, each declared once: %s" % (
    len(seen), ", ".join("%d %s" % (p, n[0]) for p, n in sorted(seen.items()))))
PY
}

echo "── localhost port uniqueness [$(basename "$(dirname "$HERE")")] ──"
guard "$REPO" || exit 1
[ "${1:-}" = check ] && exit 0

# ── mutations: a copy of every build.json (and the debug table) with one thing broken ──
T="$(mktemp -d)" && [ -n "$T" ] && [ -d "$T" ] || { echo "::error::FAIL -- mktemp -d failed"; exit 1; }
trap 'rm -rf "$T"' EXIT
fails=0
fresh() {
    rm -rf "$T/r"; mkdir -p "$T/r/1_cicd/src/data"
    for f in "$REPO"/*/build.json; do
        d="$T/r/$(basename "$(dirname "$f")")"; mkdir -p "$d"; cp "$f" "$d/"
    done
    [ ! -f "$REPO/1_cicd/src/data/debug-ports.json" ] || cp "$REPO/1_cicd/src/data/debug-ports.json" "$T/r/1_cicd/src/data/"
}
set_port() {  # <app> <python path expr> <value>
    python3 - "$T/r/$1/build.json" "$2" "$3" <<'PY'
import json, sys
p, expr, val = sys.argv[1], sys.argv[2], json.loads(sys.argv[3])
d = json.load(open(p)); node = d
keys = expr.split(".")
for k in keys[:-1]:
    node = node.setdefault(k, {})
node[keys[-1]] = val
json.dump(d, open(p, "w"), indent=2)
PY
}
must_fail() {  # <label> <expected problem tag>
    if out="$(guard "$T/r" 2>&1)"; then
        echo "::error::FAIL -- mutation '$1' was NOT caught"; fails=$((fails + 1))
    else
        case "$out" in
            *"$2"*) echo "  ok   -- mutation caught: $1" ;;
            *) echo "::error::FAIL -- mutation '$1' failed for the wrong reason:"; echo "$out"; fails=$((fails + 1)) ;;
        esac
    fi
}

fresh; set_port ac_cloud-c3-webserver runtime.port 8000
must_fail "c3-webserver back on 8000, the termux terminal's port" "P1 127.0.0.1:8000"
fresh; set_port ac_cloud-nix-on-droid runtime.webserver.port 8000
must_fail "both terminals on 8000" "P1 127.0.0.1:8000"
fresh; set_port ac_cloud-calc runtime.extra.port 8001
must_fail "a third app takes the nix terminal's 8001" "P1 127.0.0.1:8001"
fresh; set_port ac_cloud-c3-webserver runtime.port '"8002"'
must_fail "a port written as a string" "P2"
fresh; set_port ac_cloud-c3-webserver runtime.port 80
must_fail "a privileged port" "P2"
fresh; set_port ac_cloud-calc runtime.port 38080
must_fail "an app on the SuperApp gateway port" "P3"
fresh; set_port ac_cloud-c3-webserver runtime.port 8004
must_fail "c3-webserver moved off 8002" "P4"
fresh; set_port ac_cloud-c3-webserver runtime.diag.port 8001
must_fail "the diagnostics page takes the nix terminal's 8001" "P1 127.0.0.1:8001"
fresh; set_port ac_cloud-c3-webserver runtime.diag.port 8004
must_fail "the diagnostics page moved off 8003" "P4"

if [ "$fails" -ne 0 ]; then
    echo "::error::FAIL -- $fails mutation(s) not caught"
    exit 1
fi
echo "PASS -- localhost ports unique and the option 37.1 assignment holds"
