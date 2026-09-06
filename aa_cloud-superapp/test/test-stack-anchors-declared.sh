#!/usr/bin/env bash
# Anchors are DECLARED, exactly like pages, so a broken one is caught here
# rather than by a finger on a tile that does nothing.
#
# A tile whose target reads `anchor:<id>` scrolls the stack it is already on
# to a view registered under `<id>`. Registration used to happen in Kotlin as
# panels rendered, with ids slugged out of display labels — so build.json did
# not know what anchors a page had, and renaming a label silently renamed an
# anchor. Now every anchor id is written down: a panel's own `anchor`, plus
# the `anchors: [{id, group, subgroup?}]` it declares for the headers it draws
# inside itself.
#
# What this asserts:
#   T1  every `anchor:` target resolves to an id declared on the SAME stack
#   T2  declared ids are unique within a stack
#   T3  every declared {group, subgroup} binding exists in cloud_services.json
#   T4  no anchor id is derived from a label anywhere in Kotlin
#   T5  the panels the C3 Index leads to are still declared (no regression)
#   W   declared-but-unreferenced ids are reported, not failed
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== stack anchors are declared in build.json =="

REPORT="$(python3 - "$APP" <<'PY'
import json, sys, os
app = sys.argv[1]
build = json.load(open(os.path.join(app, "build.json")))
dash  = json.load(open(os.path.join(app, "data", "cloud_services.json")))

groups = {g.get("id"): g for g in dash.get("groups", [])}
sublabels = {gid: {s.get("label") for s in g.get("subgroups", [])}
             for gid, g in groups.items()}

def tile_targets(node, out):
    """Every `target` string anywhere under a stack, tiles nest arbitrarily."""
    if isinstance(node, dict):
        t = node.get("target")
        if isinstance(t, str):
            out.append(t)
        for v in node.values():
            tile_targets(v, out)
    elif isinstance(node, list):
        for v in node:
            tile_targets(v, out)

lines = []
def emit(kind, msg): lines.append("%s\t%s" % (kind, msg))

for sec in build.get("ui", {}).get("sections", []):
    for key, stack in sec.items():
        if not key.startswith("stack_") or not isinstance(stack, list):
            continue
        where = "%s.%s" % (sec.get("id"), key)
        declared, dupes = [], []
        for panel in stack:
            if not isinstance(panel, dict):
                continue
            own = panel.get("anchor", "")
            if own:
                (dupes if own in declared else declared).append(own)
            for a in panel.get("anchors", []) or []:
                aid = a.get("id", "")
                if not aid:
                    emit("T2", "%s: an anchors[] entry has no id" % where)
                    continue
                (dupes if aid in declared else declared).append(aid)
                # T3 — the binding must name something the data really draws.
                gid = a.get("group", "")
                if gid not in groups:
                    emit("T3", "%s: anchor '%s' binds to group '%s', "
                               "absent from cloud_services.json" % (where, aid, gid))
                elif a.get("subgroup") and a["subgroup"] not in sublabels[gid]:
                    emit("T3", "%s: anchor '%s' binds to subgroup '%s' under "
                               "'%s', which has no such sub-header"
                               % (where, aid, a["subgroup"], gid))
                # A card only draws group headers when it renders >1 group.
                wanted = panel.get("groups") or ([panel["group"]] if panel.get("group") else [])
                if gid in groups and not a.get("subgroup") and len(wanted) < 2:
                    emit("T3", "%s: anchor '%s' targets the group header of "
                               "'%s', but the panel renders %d group(s) so no "
                               "group header is drawn" % (where, aid, gid, len(wanted)))
                if gid and gid not in wanted:
                    emit("T3", "%s: anchor '%s' binds to group '%s', which "
                               "this panel does not render" % (where, aid, gid))
        for d in dupes:
            emit("T2", "%s: anchor id '%s' declared twice" % (where, d))

        targets = []
        tile_targets(stack, targets)
        refs = [t[len("anchor:"):] for t in targets if t.startswith("anchor:")]
        for r in refs:
            if r not in declared:
                emit("T1", "%s: tile targets 'anchor:%s', declared by no panel"
                           % (where, r))
        for d in declared:
            if d not in refs:
                emit("W", "%s: anchor '%s' is declared but nothing points at it"
                          % (where, d))
        emit("INFO", "%s: %d declared, %d referenced" % (where, len(declared), len(refs)))

print("\n".join(lines))
PY
)" || { echo "  FAIL: checker crashed"; exit 1; }

for t in T1 T2 T3; do
  hits="$(printf '%s\n' "$REPORT" | grep -c "^$t	" || true)"
  if [ "$hits" -eq 0 ]; then
    ok "$t: no violations"
  else
    printf '%s\n' "$REPORT" | grep "^$t	" | cut -f2- | sed 's/^/    /'
    bad "$t: $hits violation(s)"
  fi
done

printf '%s\n' "$REPORT" | grep '^INFO	' | cut -f2- | sed 's/^/  ..  /'
warns="$(printf '%s\n' "$REPORT" | grep '^W	' | cut -f2- || true)"
[ -n "$warns" ] && printf '%s\n' "$warns" | sed 's/^/  warn: /'

echo "== ids are never derived from a label =="
ANCH="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/StackAnchors.kt"
AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
if grep -qE 'fun slug|registerChild' "$ANCH" "$AGG" 2>/dev/null; then
  bad "T4: a label-slugging path is back — anchor ids must come from build.json"
else
  ok "T4: no slug()/registerChild — headers register only declared ids"
fi

echo "== C3 Index still leads somewhere =="
python3 - "$APP" <<'PY' && ok "T5: every C3 Index anchor is declared" || bad "T5: a C3 Index anchor lost its declaration"
import json, sys, os
b = json.load(open(os.path.join(sys.argv[1], "build.json")))
want = {
    "stack_topology": ["pub-urls", "pvt-urls", "containers-infra", "containers-user",
                       "stack/providers", "stack/vms", "stack/dbs", "stack/apis"],
    "stack_observability": ["gha-runs", "repos", "dagu-workflows", "dagu-runs"],
}
sec = next(s for s in b["ui"]["sections"] if s.get("id") == "c3")
missing = []
for key, ids in want.items():
    have = set()
    for p in sec.get(key, []):
        if p.get("anchor"):
            have.add(p["anchor"])
        have |= {a.get("id") for a in (p.get("anchors") or [])}
    missing += ["%s:%s" % (key, i) for i in ids if i not in have]
if missing:
    print("    undeclared: " + ", ".join(missing))
    sys.exit(1)
PY

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
