#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ rebuild-fanout.test — #796: what one change REBUILDS cannot widen   ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# Runs cloud_android_ci_fanout.py --builds over every scenario in
# 1_cicd/src/data/rebuild-scenarios.json against the SOURCE workflows and the
# real publish-gate engines (source-identity explain, lib-apks module-paths,
# the fork engines' companion-paths), and holds each scenario's rebuilt
# app/lib/rootfs counts to its `expect` — both ways, like the lib-classes
# backlog: above is a fan-out regression, below is progress that must be
# locked in by lowering the number in the same commit. `ship` is a ceiling.
#
# First proves its own comparator goes red on a row above and on a row below
# the expectation, so a broken comparison cannot pass as a green ratchet.
# python3, bash, git, sh only; no network, no gradle, ~1 minute.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
SIM="$ROOT/1_cicd/src/scripts/cloud_android_ci_fanout.py"
SCENARIOS="$ROOT/1_cicd/src/data/rebuild-scenarios.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# compare <rows-json>: prints one line per scenario, exits 1 on any mismatch.
compare() {
    python3 - "$1" <<'PY'
import json, sys
rows = json.loads(sys.argv[1])
bad = 0
for r in rows:
    e = r.get("expect")
    if not e:
        print("FAIL %-46s has no `expect` — declare it" % r["id"]); bad += 1; continue
    got = {k: r[k] for k in ("apps", "libs", "rootfs")}
    want = {k: e.get(k) for k in ("apps", "libs", "rootfs")}
    verdict = []
    for k in ("apps", "libs", "rootfs"):
        if got[k] > want[k]:
            verdict.append("%s %d > %d: FAN-OUT REGRESSION (rebuilt: %s)" % (k, got[k], want[k], " ".join(r["built"][k])))
        elif got[k] < want[k]:
            verdict.append("%s %d < %d: progress — lower expect.%s to %d in rebuild-scenarios.json" % (k, got[k], want[k], k, got[k]))
    if "ship" in e and r["ship"] > e["ship"]:
        verdict.append("ship runs %d > ceiling %d" % (r["ship"], e["ship"]))
    line = "%-46s runs=%-3d ship=%-3d apps=%-3d libs=%-3d rootfs=%d" % (r["id"], r["runs"], r["ship"], r["apps"], r["libs"], r["rootfs"])
    if verdict:
        bad += 1; print("FAIL " + line); [print("       " + v) for v in verdict]
    else:
        print("ok   " + line)
sys.exit(1 if bad else 0)
PY
}

# ── the comparator itself goes red where it must ────────────────────────
row='{"id":"x","runs":1,"ship":1,"apps":2,"libs":0,"rootfs":0,"built":{"apps":["a","b"],"libs":[],"rootfs":[]},"expect":{"apps":%d,"libs":0,"rootfs":0,"ship":1}}'
if compare "[$(printf "$row" 1)]" >/dev/null; then fail "comparator: a scenario rebuilding MORE than expected stayed green"; else ok "comparator goes red above the expectation"; fi
if compare "[$(printf "$row" 3)]" >/dev/null; then fail "comparator: a scenario rebuilding LESS than expected stayed green (the ratchet would not lock in)"; else ok "comparator goes red below the expectation"; fi
if compare "[$(printf "$row" 2)]" >/dev/null; then ok "comparator is green on the expectation"; else fail "comparator is red on an exact match"; fi
if compare '[{"id":"x","runs":1,"ship":1,"apps":0,"libs":0,"rootfs":0,"built":{"apps":[],"libs":[],"rootfs":[]}}]' >/dev/null; then fail "an undeclared expectation passed"; else ok "a scenario without expect goes red"; fi

# ── the real tree ────────────────────────────────────────────────────────
rows="$(cd "$ROOT" && python3 "$SIM" --wf 1_cicd/src/cicd --scenarios "$SCENARIOS" --json)" || { fail "simulator failed"; rows="[]"; }
if compare "$rows"; then ok "every scenario rebuilds exactly what rebuild-scenarios.json expects"; else fail "a scenario moved off its expectation (see above)"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "== RESULT(#796 rebuild fan-out): held =="; exit 0; fi
echo "== RESULT(#796 rebuild fan-out): $FAILURES check(s) failed =="; exit 1
