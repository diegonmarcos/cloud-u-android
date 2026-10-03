#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ rebuild-fanout.test — #796: what one change REBUILDS cannot widen   ║
# ║ (#836: and a shared-lib edit ships only its primary consumer)       ║
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
    KEYS = ("apps", "libs", "rootfs", "refresh")
    if any(e.get(k) is None for k in KEYS):
        print("FAIL %-46s expect lacks one of %s — declare it" % (r["id"], "/".join(KEYS))); bad += 1; continue
    got = {k: r.get(k, 0) for k in KEYS}
    want = {k: e.get(k) for k in KEYS}
    verdict = []
    for k in KEYS:
        if got[k] > want[k]:
            verdict.append("%s %d > %d: FAN-OUT REGRESSION (rebuilt: %s)" % (k, got[k], want[k], " ".join(r["built"].get(k, []))))
        elif got[k] < want[k]:
            verdict.append("%s %d < %d: progress — lower expect.%s to %d in rebuild-scenarios.json" % (k, got[k], want[k], k, got[k]))
    if "ship" in e and r["ship"] > e["ship"]:
        verdict.append("ship runs %d > ceiling %d" % (r["ship"], e["ship"]))
    line = "%-46s runs=%-3d ship=%-3d apps=%-3d libs=%-3d rootfs=%d refresh=%d" % (r["id"], r["runs"], r["ship"], r["apps"], r["libs"], r["rootfs"], r.get("refresh", 0))
    if verdict:
        bad += 1; print("FAIL " + line); [print("       " + v) for v in verdict]
    else:
        print("ok   " + line)
sys.exit(1 if bad else 0)
PY
}

# ── the comparator itself goes red where it must ────────────────────────
row='{"id":"x","runs":1,"ship":1,"apps":2,"libs":0,"rootfs":0,"refresh":0,"built":{"apps":["a","b"],"libs":[],"rootfs":[]},"expect":{"apps":%d,"libs":0,"rootfs":0,"refresh":0,"ship":1}}'
if compare "[$(printf "$row" 1)]" >/dev/null; then fail "comparator: a scenario rebuilding MORE than expected stayed green"; else ok "comparator goes red above the expectation"; fi
if compare "[$(printf "$row" 3)]" >/dev/null; then fail "comparator: a scenario rebuilding LESS than expected stayed green (the ratchet would not lock in)"; else ok "comparator goes red below the expectation"; fi
if compare "[$(printf "$row" 2)]" >/dev/null; then ok "comparator is green on the expectation"; else fail "comparator is red on an exact match"; fi
if compare '[{"id":"x","runs":1,"ship":1,"apps":0,"libs":0,"rootfs":0,"built":{"apps":[],"libs":[],"rootfs":[]}}]' >/dev/null; then fail "an undeclared expectation passed"; else ok "a scenario without expect goes red"; fi

# ── #836: the per-push filter is what is held, so a mutant must go red ──
# Two mutants of the REAL source workflows, each run through the same simulator
# and comparator as the real tree:
#   M1  every deferred shared-lib input put back into its app's push list —
#       the pre-#836 fan-out (a lib edit starts every consumer's ship again)
#   M2  every deferred input deleted — the libs vanish from the identity and
#       from fleet-refresh.yml's view, so a lib fix would never reach the apps
# Both must go red, or the scenarios table is not actually holding the gate.
mutant() {  # mutant <M1|M2> <dir>
    cp -r "$ROOT/1_cicd/src/cicd/." "$2/"
    python3 - "$1" "$2" <<'PY'
import glob, re, sys
mode, d = sys.argv[1], sys.argv[2]
for wf in glob.glob(d + "/ship-*.yml"):
    t = open(wf).read()
    libs = re.findall(r'^      #   input: "(ab_cloud-libs-shared/libs/[^"]+)"\s*$', t, re.M)
    if not libs:
        continue
    if mode == "M1":
        t = t.replace("      # ── end MANAGED-PATHS-HEADER ──\n",
                      "      # ── end MANAGED-PATHS-HEADER ──\n" + "".join('      - "%s"\n' % l for l in libs), 1)
    else:
        t = re.sub(r'^      #   input: "ab_cloud-libs-shared/libs/[^"]+"\s*\n', "", t, flags=re.M)
    open(wf, "w").write(t)
PY
}
for M in M1 M2; do
    MD="$(mktemp -d)"
    mutant "$M" "$MD"
    mrows="$(cd "$ROOT" && python3 "$SIM" --wf "$MD" --scenarios "$SCENARIOS" --json 2>/dev/null)" || mrows=""
    rm -rf "$MD"
    if [ -z "$mrows" ]; then fail "mutant $M: simulator failed"; continue; fi
    if compare "$mrows" >/dev/null; then
        fail "mutant $M stayed green — the per-push lib filter (#836) is not held by rebuild-scenarios.json"
    else
        ok "mutant $M (deferred libs $( [ "$M" = M1 ] && echo 'back in the push lists' || echo 'dropped')) goes red"
    fi
done

# ── the real tree ────────────────────────────────────────────────────────
rows="$(cd "$ROOT" && python3 "$SIM" --wf 1_cicd/src/cicd --scenarios "$SCENARIOS" --json)" || { fail "simulator failed"; rows="[]"; }
if compare "$rows"; then ok "every scenario rebuilds exactly what rebuild-scenarios.json expects"; else fail "a scenario moved off its expectation (see above)"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "== RESULT(#796 rebuild fan-out): held =="; exit 0; fi
echo "== RESULT(#796 rebuild fan-out): $FAILURES check(s) failed =="; exit 1
