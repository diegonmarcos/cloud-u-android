#!/usr/bin/env bash
# Home ▸ down containers — the WIRING the JVM suite cannot see.
#
# FleetDownTest proves the model: a declared-but-exited container is listed, an
# unreachable API is Report.Unreachable, an action reads its state back. None of that
# helps if the screen drops a Report variant on the floor or the sheet calls the API
# directly. So:
#
#   D1  Home renders EVERY FleetDown.Report variant (read from FleetDown.kt, not listed
#       here) — a variant with no branch is the "unreachable reads as empty" bug.
#   D2  every container action in the sheet goes through FleetDown.act, which reads the
#       state back — no bare OpsClient.container call that would print a 2xx as success.
#   D3  every stop in the sheet (container AND service) is behind confirmStop, and no
#       action list spells "stop" around the constant the confirmation keys on.
#   M   each check is shown able to go RED on a mutated copy.
#
# OWN-SOURCE ONLY. python3.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REL="app/src/main/java/com/diegonmarcos/cloudc3"
FAILURES=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }

d1() { python3 - "$1/$REL/cloud/FleetDown.kt" "$1/$REL/pages/SimplePages.kt" <<'PY'
import re, sys
model, home = (open(p).read() for p in sys.argv[1:3])
variants = re.findall(r'(?:data class|object)\s+(\w+)\s*(?:\([^)]*\))?\s*:\s*Report\(\)', model)
if len(variants) < 3: print("    fewer than three Report variants found: %s" % variants); sys.exit(1)
if "FleetDown.load(" not in home: print("    Home never calls FleetDown.load"); sys.exit(1)
miss = [v for v in variants if not re.search(r'(?:is\s+)?FleetDown\.Report\.%s\s*->' % v, home)]
for v in miss: print("    Home has no branch for FleetDown.Report.%s" % v)
sys.exit(1 if miss else 0)
PY
}

d2() { python3 - "$1/$REL/cloud/ContainerSheet.kt" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
calls = len(re.findall(r'OpsClient\.container\(', s))
wrapped = len(re.findall(r'FleetDown\.act\(\s*\w+\s*,\s*\{\s*OpsClient\.container\(', s))
if calls == 0: print("    no container action found at all")
elif wrapped != calls: print("    %d OpsClient.container calls, only %d are FleetDown.act's call argument" % (calls, wrapped))
sys.exit(0 if calls and wrapped == calls else 1)
PY
}

d3() { python3 - "$1/$REL/cloud/ContainerSheet.kt" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
bad = []
if re.search(r'to\s+"stop"', s): bad.append('an action list spells "stop" instead of FleetDown.STOP')
lists = len(re.findall(r'to\s+FleetDown\.STOP\b', s))
guarded = len(re.findall(r'if \(act == FleetDown\.STOP\) confirmStop\(', s))
if lists < 2: bad.append("expected the container AND the service stop, found %d" % lists)
if guarded != lists: bad.append("%d stop actions but %d confirmStop guards" % (lists, guarded))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

echo "── Home ▸ down containers: every outcome rendered, every action read back, every stop confirmed ──"
d1 "$APP" && pass "D1 Home renders every FleetDown.Report variant" || fail "D1 a Report variant has no branch on Home"
d2 "$APP" && pass "D2 every container action goes through FleetDown.act (read-back)" || fail "D2 a container action skips the read-back"
d3 "$APP" && pass "D3 every stop (container and service) asks first" || fail "D3 a stop runs without confirmation"

echo
echo "── mutation proof: each check must be able to go RED ──"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
MUT_FAIL=0
mutate() { # <name> <check-fn> <file-rel> <python-replace-old> <python-replace-new>
    local name="$1" check="$2" f="$WORK/a/$REL/$3"
    rm -rf "$WORK/a"; mkdir -p "$WORK/a"; cp -r "$APP/app" "$WORK/a/app"
    if ! "$check" "$WORK/a" >/dev/null 2>&1; then echo "  VOID    $name — the unmutated copy is already red"; MUT_FAIL=$((MUT_FAIL + 1)); return; fi
    if ! python3 - "$f" "$4" "$5" <<'PY'
import sys
p, old, new = sys.argv[1:4]
s = open(p).read()
if old not in s: sys.exit(1)
open(p, "w").write(s.replace(old, new, 1))
PY
    then echo "  VOID    $name — the mutation did not apply (its anchor text is gone)"; MUT_FAIL=$((MUT_FAIL + 1)); return; fi
    if cmp -s "$f" "$APP/$REL/$3"; then echo "  VOID    $name — the mutated file is identical to the original"; MUT_FAIL=$((MUT_FAIL + 1)); return; fi
    if "$check" "$WORK/a" >/dev/null 2>&1; then echo "  HOLLOW  $name — stayed GREEN"; MUT_FAIL=$((MUT_FAIL + 1)); else echo "  RED     $name"; fi
}

mutate "Home drops the Unreachable branch"          d1 pages/SimplePages.kt "is FleetDown.Report.Unreachable ->" "else ->"
mutate "a new Report variant nothing renders"       d1 cloud/FleetDown.kt   "object NothingDeclared : Report()" "object NothingDeclared : Report()
        object Stale : Report()"
mutate "a container action calls the API directly"  d2 cloud/ContainerSheet.kt "val r = FleetDown.act(act, { OpsClient.container(vm, name, act, bearer) }," "val r0 = OpsClient.container(vm, name, act, bearer); val r = FleetDown.act(act, { r0 },"
mutate "the service stop loses its confirmation"    d3 cloud/ContainerSheet.kt "if (act == FleetDown.STOP) confirmStop(ctx, service, go) else go()" "go()"
mutate "a stop is spelled around the constant"      d3 cloud/ContainerSheet.kt '"Stop" to FleetDown.STOP' '"Stop" to "stop"'

echo
if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"; exit 1
fi
echo "PASS  3 properties asserted, 5 mutations each proved able to go red"
