#!/usr/bin/env bash
# Tester: every Notify tab opens newest-first, and the ordering is decided in
# ONE place that all six tabs go through.
#
# BEFORE: the six Notify tabs are one page cloned six times, differing only in
# their filters_<pageid> defaults. That shape makes a very specific failure
# cheap: fix the ordering on the tab you happened to open, leave the other five
# on whatever they were, and the page looks right to whoever checked it. The
# same trap exists inside the fragment — the per-app boxes obeyed the Sort
# toggle, and the ntfy CHANNEL boxes were sorted alphabetically under BOTH
# modes, so the one surface every tab draws ignored the setting all six tabs
# declared.
#
# NOW: "time" is the declared default on every tab, and both box builders read
# sortMode. These are the invariants that would regress silently:
#   T1 the Notify tabs are discovered from the data, not from a list here
#   T2 EVERY tab declares a sort filter — a tab with none falls to Kotlin
#   T3 EVERY tab declares default "time", and they all declare the SAME one
#   T4 the Kotlin fallback, used by a page that declares no filter row, is time
#   T5 the per-app boxes are ordered by their newest notification under Time
#   T6 the ntfy channel boxes read sortMode too — no unconditional sort
#   T7 an unmeasured/unreachable channel is not ranked as if it had been polled
#   T8 ordering INSIDE a box is always newest-first, whatever Sort says
#   T9 the reading is per-app BOXES, not a flat list: the group controls that
#      the boxes exist to carry are still there
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null; }

AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"

# CODE-ONLY view. The KDoc necessarily NAMES the alphabetical ordering it
# explains this file no longer does, so the prose must not be searchable here.
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
python3 - "$AGG" "$CODE" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
io.open(sys.argv[2], "w", encoding="utf-8").write(s)
PY

echo "== every generated Notify tab carries the same default =="
# THE assertion the "fixed on one tab only" failure walks straight through.
# The tabs are read out of build.json by SHAPE — the section that declares a
# notification_center panel — so adding a seventh tab puts it under this test
# automatically and renaming the section cannot make the test go blind.
python3 - "$APP/build.json" <<'PY2'
import io, json, sys
b = json.load(io.open(sys.argv[1], encoding="utf-8"))
fails, tabs, defaults, fallback = [], [], {}, []
for sec in b["ui"]["sections"]:
    stacks = {k[len("stack_"):]: v for k, v in sec.items()
              if k.startswith("stack_") and not k.startswith("_doc") and isinstance(v, list)}
    for page, panels in stacks.items():
        if not any(p.get("kind") == "notification_center" for p in panels):
            continue
        name = "%s/%s" % (sec["id"], page)
        tabs.append(name)
        filters = sec.get("filters_" + page)
        srt = next((f for f in (filters or []) if f.get("id") == "sort"), None)
        if srt is None:
            # Legitimate and covered elsewhere: a notification surface may
            # decline the toggle row entirely (C3 Obsv does), and it then
            # inherits the Kotlin fallback that T4 pins to "time". What is NOT
            # allowed is declaring a filter row that forgets `sort` — that is a
            # visible toggle whose default nobody wrote down.
            if filters:
                fails.append("%s declares a filter row but no `sort` toggle" % name)
            else:
                fallback.append(name)
            continue
        d = srt.get("default")
        defaults[name] = d
        if d != "time":
            fails.append("%s sort default is %r, not 'time'" % (name, d))
        if not any(o.get("id") == d for o in srt.get("options", [])):
            fails.append("%s default %r is not one of its own options" % (name, d))
if not tabs:
    fails.append("no notification surface found at all — the page moved and this test went blind")
if not defaults:
    fails.append("no surface declares a sort toggle — the six Notify tabs lost their filter rows")
print("  ok: T1: %d notification surfaces discovered from the data: %s"
      % (len(tabs), ", ".join(tabs)))
if fallback:
    print("  ok: T1: %d of them declare no toggle row and ride the T4 fallback: %s"
          % (len(fallback), ", ".join(fallback)))
uniq = set(defaults.values())
if len(uniq) > 1:
    fails.append("the tabs disagree about the default: %r" % defaults)
print("  ok: T2/T3: all %d toggle-bearing tabs declare sort, all on %r"
      % (len(defaults), uniq.pop() if uniq else None)
      if not fails else "  FAIL: T1-T3: " + "; ".join(fails))
sys.exit(1 if fails else 0)
PY2
[ $? -eq 0 ] && PASS=$((PASS+2)) || FAIL=$((FAIL+1))

echo "== the Kotlin fallback agrees with the data =="
# A page carrying notification boxes without a toggle row (C3 Obsv) never
# assigns sortMode, so this literal IS its ordering.
has "$AGG" 'private var sortMode   = "time"' \
  && ok "T4: the no-filter-row fallback is time too" \
  || bad "T4: the Kotlin sortMode fallback is no longer 'time'"

echo "== both box builders read the toggle =="
has "$AGG" 'else visible.sortedByDescending { it.newest }' \
  && ok "T5: per-app boxes are ordered by their newest notification under Time" \
  || bad "T5: renderGroups no longer orders boxes by recency"
# The regression this test exists for: renderNtfyGroups ordered its boxes with
# a bare topics.sorted(), alphabetically, whatever the toggle said.
if grep -q 'topics.sorted()' "$CODE"; then
  bad "T6: renderNtfyGroups sorts unconditionally again — Sort=Time is dead on every tab"
else
  ok "T6: no unconditional sort left in the channel builder"
fi
# sortMode must be read in BOTH builders. One is the app boxes, one is the
# channel boxes; one reader means one of the two is ignoring the setting.
n=$(grep -c 'sortMode == "app"' "$CODE")
[ "$n" -eq 2 ] \
  && ok "T6: both builders branch on sortMode ($n sites)" \
  || bad "T6: $n builder(s) read sortMode — expected 2 (per-app boxes + channels)"
has "$AGG" 'compareByDescending<String> { newestMeasured(it) }.then(byLabel))' \
  && ok "T6: channels under Time are ordered by their last measurement" \
  || bad "T6: the channel Time ordering is gone"

echo "== an unpolled channel makes no claim =="
has "$AGG" '?.takeIf { it.ok }?.rows?.maxOfOrNull { it.ts } ?: Long.MIN_VALUE' \
  && ok "T7: unmeasured and unreachable both sort last, not as fresh" \
  || bad "T7: a channel we never read can now be ranked as though we had"

echo "== sorting can never bury something fresh =="
has "$AGG" 'return shown.sortedByDescending { it.ts }' \
  && ok "T8: inside a box the order is newest-first under both modes" \
  || bad "T8: within-group ordering now depends on the Sort toggle"

echo "== the layout is still per-app boxes, so the box controls survive =="
# "Sort by time" was read as: the BOXES are ordered by their most recent
# notification. The flat-list reading would delete the box, and with it the
# per-app clear-all and Archive controls that were built on purpose.
b=$(grep -c 'private fun groupBlock' "$CODE")
[ "$b" -eq 1 ] && ok "T9: one box builder, so every stream's boxes are the same boxes" \
  || bad "T9: $b groupBlock builders"
has "$AGG" 'header.addView(archivePill(ctx, g.key, block, homeBody))' \
  && ok "T9: the per-app Archive control still rides on the box header" \
  || bad "T9: the Archive control is gone — the boxes were flattened away"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
