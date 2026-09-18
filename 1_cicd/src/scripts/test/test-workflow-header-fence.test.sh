#!/usr/bin/env bash
# test-workflow-header-fence.test.sh — task #483 regression guard.
#
# THE DEFECT: the paths-header generator recognised its own header by EXACT text
# match against the CURRENT header and emitted every other comment as the
# author's. Re-word one header line and every old-wrapping line stopped
# matching, was reclassified as authored, and was re-emitted below the new
# header forever — the generator laundering its own stale output into
# hand-written content. The fix fences the managed region with BEGIN/END markers
# and replaces the whole region on every run.
#
# WHY THIS TEST CANNOT GO GREEN-BY-GONE: it does not grep today's .yml output for
# the stranded string (that string is gone the day the file is regenerated, while
# the mechanism would still be sitting there waiting for the next header edit).
# It imports the SAME module the engine runs and drives it with a header-text
# mutation, asserting the managed region regenerates wholesale and authored prose
# is carried through verbatim. A check that fetches its own subject every run
# cannot pass on the absence of a bug.
#
# No gradle, no device, no network: seconds on a runner.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# CWP_PATH_MOD lets a harness point at a DEFECTIVE copy of the module and prove
# this tester is not a check-that-cannot-fail (see below).
MOD="${CWP_PATH_MOD:-$DIR/../cloud_android_workflow_paths.py}"
[ -f "$MOD" ] || { echo "FATAL: $MOD missing — engine module not a sibling"; exit 1; }

pass=0; fail=0
ok()   { printf '  [PASS] %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  [FAIL] %s\n' "$1"; fail=$((fail+1)); }

# The three behaviours the fence must hold, driven through the real module via
# python3. Each block is printed back for inspection on failure.
run_py() {
  python3 - "$MOD" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("cwp", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
BEG = m.BEG; END = m.END

def entries(*bodies):
    return ['      - "%s"' % b for b in bodies]

def has(block, sub):
    return any(sub in l for l in block)

failures = []

# ── 1. The ORIGINAL defect shape (unfenced, pre-migration file) ──────
# A file generated under the OLD engine: current header, a STALE old-wrapped
# header fragment (the exact orphan this ticket strands), and real author prose.
unfenced = ([
    "      # MANAGED by cloud-android-ship-repo-workflow-engine.sh: every dir",
    "      # aa_app/build.json::modules declares is added automatically, dead",
    "      # this list X so nothing can trigger a build the publish gate does",  # old wording
    "      # not weigh. Extra entries no module map can express",                 # old wording
    "      # author: this app deliberately watches no shared lib",
    "      # so an unrelated aar bump never rebuilds it",
] + entries("aa_app/**"))
blk = m.rewrite_paths_block(unfenced, ["aa_app/**"], "aa_app")
if has(blk, "old wording") or has(blk, "this list X"):
    failures.append("1: stale old-header fragment escaped into the output instead of being dropped (the #483 mechanism still strands)")
if not has(blk, "author: this app deliberately watches no shared lib") or not has(blk, "so an unrelated aar bump never rebuilds it"):
    failures.append("1: real authored prose was eaten by the fix (the #237 regression — must never return)")
if sum(1 for l in blk if l.strip() == BEG.strip()) != 1:
    failures.append("1: managed region not fenced exactly once")
if sum(1 for l in blk if l.strip() == END.strip()) < 1:
    failures.append("1: no end marker")

# ── 2. The mutation that actually tests the fix ─────────────────────
# An ALREADY-fenced block whose managed region holds header text from some
# FUTURE (or past) edit. Regeneration must replace that whole region with the
# engine's CURRENT header and preserve authored prose — nothing may strand, and
# no old header text may leak into authored.
fenced_old = [
    BEG,
    "      # MANAGED by cloud-android-ship-repo-workflow-engine.sh: every dir",
    "      # aa_app/build.json::modules declares is added automatically, dead",
    "      # this list Q different wording entirely",
    END,
    "      # author: expands when the trigger set changes",
    "      # keep me byte-identical",
]
blk2 = m.rewrite_paths_block(fenced_old, ["aa_app/**"], "aa_app")
if has(blk2, "different wording entirely"):
    failures.append("2: a re-worded header number stranded into the output (the mechanism this ticket kills)")
if not has(blk2, "keep me byte-identical") or not has(blk2, "expands when the trigger set changes"):
    failures.append("2: authored prose did not survive the regeneration")

# ── 3. Idempotency ──────────────────────────────────────────────────
# Regenerating an already-fenced block must reproduce the SAME block (one
# fence), never stack a second one.
once = m.rewrite_paths_block([BEG] + m.make_header("aa_app") + [END, "# author line"] + entries("aa_app/**"),
                             ["aa_app/**"], "aa_app")
twice = m.rewrite_paths_block(once[1:], ["aa_app/**"], "aa_app")  # region excludes the leading "    paths:"
if once != twice:
    failures.append("3: regeneration is not idempotent — the managed region changed between runs")

for f in failures:
    sys.stderr.write("FAIL: %s\n" % f)
sys.exit(1 if failures else 0)
PY
}

if out=$(run_py 2>&1); then
  ok "engine module: fence holds under header mutation (defect shape dead), authored survives, idempotent"
else
  bad "engine module assertion failed"
  printf '%s\n' "$out"
fi

echo "── workflow-header-fence tester: $pass passed, $fail failed ──"
[ "$fail" -eq 0 ]