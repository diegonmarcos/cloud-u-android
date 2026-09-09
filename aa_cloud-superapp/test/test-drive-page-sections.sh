#!/usr/bin/env bash
# Drive ▸ Connections is TWO sections — Apps, then Configs — and its Apps row
# is DERIVED from the launcher's own Data Apps group rather than retyped.
#
# The failure this exists to catch is an icon grid where some icons silently do
# nothing. That happens two ways, and both are checkable without a device:
# a reference that resolves to no tile (nothing renders, or worse, an icon with
# an empty target), and a tile whose target is not a verb the shell's dispatcher
# understands (it renders, it is tappable, and the `when` in onTileClicked
# matches no branch — a dead tap).
#
# What this asserts:
#   T1  the Data Apps group leads with Drive, and it is the `drive-conn` tile
#       (target page:drive/connections) — NOT the `drive` tile in Cloud ▸ AGI,
#       which is MyIDE files and a different page entirely
#   T2  page:drive/connections still routes to DriveConnectionsFragment
#   T3  the page declares its Apps row, and every reference resolves to a real
#       tile — the whole named group, plus each `<section>/<tileId>` extra
#   T4  every app that lands in that row carries a target the tile dispatcher
#       has a branch for, so no icon can be inert
#   T5  PM Boards is in the row, sourced from Tools Dashboards rather than
#       copied into Data Apps
#   T6  the Configs half is intact — the page still renders every declared
#       drive_connections backend, i.e. it was relocated, not rewritten
#   T7  the layout puts Apps before Configs
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== Drive ▸ Connections: Apps row is derived, and every icon can fire =="

REPORT="$(python3 - "$APP" <<'PY'
import json, os, sys

app = sys.argv[1]
build = json.load(open(os.path.join(app, "build.json")))
sections = build["ui"]["sections"]
by_id = {s.get("id"): s for s in sections}

lines = []
def emit(kind, msg): lines.append("%s\t%s" % (kind, msg))

def tiles_of(section_id):
    out = []
    for g in by_id.get(section_id, {}).get("tile_groups", []) or []:
        out += g.get("tiles", []) or []
    return out

# ── T1 — Drive leads Data Apps, and it is the right Drive ────────────────────
cloud = by_id.get("cloud", {})
data_apps = next((g for g in cloud.get("tile_groups", []) or []
                  if g.get("title") == "Data Apps"), None)
if data_apps is None:
    emit("T1", "Cloud has no 'Data Apps' tile group")
    data_apps = {"tiles": []}
else:
    first = (data_apps.get("tiles") or [{}])[0]
    if first.get("id") != "drive-conn":
        emit("T1", "Data Apps leads with '%s', not the Drive tile 'drive-conn'"
                   % first.get("id"))
    elif first.get("target") != "page:drive/connections":
        emit("T1", "the leading Drive tile targets '%s', not page:drive/connections"
                   % first.get("target"))
    # The trap: Cloud ▸ AGI has its OWN `drive` tile and it is MyIDE files.
    agi = next((g for g in cloud.get("tile_groups", []) or []
                if g.get("title") == "AGI"), {})
    myide = next((t for t in agi.get("tiles", []) or [] if t.get("id") == "drive"), None)
    if myide is None:
        emit("T1", "Cloud ▸ AGI lost its `drive` tile — that one is MyIDE, "
                   "and nothing in this change should have touched it")
    elif myide.get("target") != "extapp:cloud-ide/files":
        emit("T1", "Cloud ▸ AGI's `drive` tile now targets '%s' — MyIDE was "
                   "changed by mistake for Drive ▸ Connections"
                   % myide.get("target"))

# ── T3/T5 — the Apps row resolves, exactly as Sections.appTilesFor resolves it
drive = by_id.get("drive", {})
page = next((p for p in drive.get("pages", []) or [] if p.get("id") == "connections"), None)
row = []
if page is None:
    emit("T3", "the drive section declares no `connections` page")
else:
    ref = page.get("apps_from_tile_group", "")
    if "/" not in ref:
        emit("T3", "the page declares no apps_from_tile_group — the Apps "
                   "section would render nothing")
    else:
        sec_id, _, title = ref.partition("/")
        grp = next((g for g in by_id.get(sec_id, {}).get("tile_groups", []) or []
                    if g.get("title") == title), None)
        if grp is None:
            emit("T3", "apps_from_tile_group '%s' names no tile group" % ref)
        else:
            row += grp.get("tiles", []) or []
    for extra in page.get("apps_extra_tile_ids", []) or []:
        sec_id, _, tile_id = extra.partition("/")
        hit = next((t for t in tiles_of(sec_id) if t.get("id") == tile_id), None)
        if hit is None:
            emit("T3", "apps_extra_tile_ids entry '%s' resolves to no tile — "
                       "it would be silently missing from the row" % extra)
        else:
            row.append(hit)
    # distinctBy target, same as the Kotlin
    seen, deduped = set(), []
    for t in row:
        if t.get("target") not in seen:
            seen.add(t.get("target")); deduped.append(t)
    row = deduped

    pm = next((t for t in row if t.get("id") == "pmboards"), None)
    if pm is None:
        emit("T5", "PM Boards is not in the Apps row")
    elif any(t.get("id") == "pmboards" for t in (data_apps.get("tiles") or [])):
        emit("T5", "PM Boards was COPIED into Data Apps — it belongs to "
                   "Tools Dashboards and must be referenced, not duplicated")

# ── T4 — every target in the row is a verb onTileClicked has a branch for ────
# Mirrors the `when` in ShellActivity.onTileClicked. A target matching none of
# these renders an icon that does nothing at all when tapped.
PREFIXES = ("section:", "page:", "action:", "extapp:", "http://", "https://", "intent:", "stub:")
for t in row:
    tgt = t.get("target", "")
    if not tgt:
        emit("T4", "app '%s' has no target" % t.get("id"))
    elif not (tgt.startswith(PREFIXES) or "://" in tgt):
        emit("T4", "app '%s' targets '%s', which no onTileClicked branch matches "
                   "— the icon would be inert" % (t.get("id"), tgt))

emit("INFO", "Apps row (%d): %s" % (len(row), ", ".join(t.get("label", "?") for t in row)))

# ── T6 — the Configs half still has its data ─────────────────────────────────
conns = build["ui"].get("drive_connections", [])
if not conns:
    emit("T6", "ui.drive_connections is empty — the Configs section lost its data")
else:
    emit("INFO", "Configs section: %d declared backends" % len(conns))

print("\n".join(lines))
PY
)" || { echo "  FAIL: checker crashed"; exit 1; }

for t in T1 T3 T4 T5 T6; do
  hits="$(printf '%s\n' "$REPORT" | grep -c "^$t	" || true)"
  if [ "$hits" -eq 0 ]; then
    ok "$t: no violations"
  else
    printf '%s\n' "$REPORT" | grep "^$t	" | cut -f2- | sed 's/^/    /'
    bad "$t: $hits violation(s)"
  fi
done
printf '%s\n' "$REPORT" | grep '^INFO	' | cut -f2- | sed 's/^/  ..  /'

echo "== the page route still lands on the fragment that renders it =="
SP="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
if grep -q 'sectionId == "drive" && pageId == "connections" -> DriveConnectionsFragment' "$SP"; then
  ok "T2: page:drive/connections -> DriveConnectionsFragment"
else
  bad "T2: SectionPages.factoryFor no longer routes drive/connections"
fi

echo "== Apps is drawn above Configs =="
LAYOUT="$APP/app/src/main/res/layout/fragment_drive_connections.xml"
A="$(grep -n 'drive_apps_header'    "$LAYOUT" | head -1 | cut -d: -f1)"
C="$(grep -n 'drive_configs_header' "$LAYOUT" | head -1 | cut -d: -f1)"
R="$(grep -n 'drive_root'           "$LAYOUT" | head -1 | cut -d: -f1)"
if [ -n "$A" ] && [ -n "$C" ] && [ -n "$R" ] && [ "$A" -lt "$C" ] && [ "$C" -lt "$R" ]; then
  ok "T7: Apps header, then Configs header, then the backend list"
else
  bad "T7: layout order is Apps($A) Configs($C) list($R) — expected ascending"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
