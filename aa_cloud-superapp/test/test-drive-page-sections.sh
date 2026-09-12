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
#   T8  the verb list T4 checks against is READ OUT OF the dispatcher, not
#       retyped here — a `when` that grows or loses a branch moves the
#       assertion with it instead of leaving T4 green against a shell that no
#       longer agrees with it
#   T9  a tap that reaches launchUri cannot throw out of the click handler.
#       Notes targets `obsidian://open`, which Intent.parseUri turns into a
#       plain ACTION_VIEW, and startActivity on that raises
#       ActivityNotFoundException on any phone without Obsidian installed. An
#       uncaught throw there is a crash on a tap — strictly worse than the icon
#       doing nothing — so every parse and every launch in that function has to
#       sit inside a runCatching, and the function has to end by SAYING the tap
#       failed rather than returning silently.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
SHELL_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== Drive ▸ Connections: Apps row is derived, and every icon can fire =="

# The verbs the router actually branches on, read off the `when` itself.
# It lives in routeTarget, which is onTileClicked minus the click bookkeeping
# — the split exists so the shell can re-enter the router mid-tap without
# the re-entry counting as a second tap.
# Retyping them here is how T4 would go on passing against a dispatcher that
# had since dropped a branch — the row would still "match" a prefix this file
# remembers and the shell no longer honours. One list, and it lives in the
# Kotlin.
DISPATCH_PREFIXES="$(
  awk '/^    override fun routeTarget\(tileId: String\) \{/{f=1} f{print} f&&/^    \}$/{exit}' \
      "$SHELL_KT" |
  grep -o 'tileId\.startsWith("[^"]*")' | sed 's/.*("//;s/")//' | sort -u
)"
if [ -z "$DISPATCH_PREFIXES" ]; then
  bad "T8: read no startsWith verbs out of ShellActivity.routeTarget — the
       function was renamed or restructured, and T4 below is asserting against
       nothing"
else
  ok "T8: T4 checks against the dispatcher's own verbs: $(printf '%s' "$DISPATCH_PREFIXES" | tr '\n' ' ')"
fi

REPORT="$(python3 - "$APP" $DISPATCH_PREFIXES <<'PY'
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
# The `when` in ShellActivity.onTileClicked, handed in by the caller which read
# it out of the Kotlin. A target matching none of these renders an icon that
# does nothing at all when tapped.
PREFIXES = tuple(sys.argv[2:])
for t in row:
    if t.get("separator"):
        # A separator is a label, not a destination. GroupedTilesFragment draws the bare '|' and
        # never makes it clickable, so demanding a target here demands one that must NOT exist —
        # a target on a separator would be a tap that goes somewhere off a glyph meant to divide.
        # Task 303 put the first one in this very row, which is what made this check fail.
        continue
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

echo "== a tap that leaves the app grammar cannot crash it =="
# Notes is `obsidian://open`. Intent.parseUri turns a non-`intent:` URI into a
# plain ACTION_VIEW, and startActivity on that throws ActivityNotFoundException
# on every phone that does not have Obsidian. The same is true of the app://
# branch's store fallback and of any intent:// whose browser_fallback_url has
# no handler either. Guarding them is not optional: an uncaught throw on the
# click handler kills the launcher, which is a worse answer than the icon
# doing nothing.
T9="$(python3 - "$SHELL_KT" <<'PY'
import re, sys

src = open(sys.argv[1]).read().splitlines()
try:
    start = next(i for i, l in enumerate(src)
                 if l.startswith("    private fun launchUri(uri: String) {"))
except StopIteration:
    print("launchUri is gone or was renamed — this whole check is asserting nothing")
    raise SystemExit

end = next(i for i in range(start + 1, len(src)) if src[i] == "    }")
body = src[start:end + 1]

bad = []
# Brace-depth of the innermost open runCatching, or None when outside one.
guard = None
depth = 0
for line in body:
    for tok in re.findall(r'runCatching|startActivity\(|Intent\.parseUri\(|[{}]', line):
        if tok == "runCatching":
            if guard is None:
                guard = depth
        elif tok == "{":
            depth += 1
        elif tok == "}":
            depth -= 1
            if guard is not None and depth <= guard:
                guard = None
        elif guard is None:
            bad.append(tok.rstrip("("))

for name in dict.fromkeys(bad):
    print("%s is called outside any runCatching — a target with no installed "
          "handler throws straight out of the tap" % name)

if not any("snack(" in l for l in body[-6:]):
    print("launchUri no longer ends by saying the tap failed — a target that "
          "nothing handles would go silently dead")
PY
)" || { echo "  FAIL: launchUri checker crashed"; exit 1; }
if [ -z "$T9" ]; then
  ok "T9: every parse and launch in launchUri is caught, and a dead target says so"
else
  printf '%s\n' "$T9" | sed 's/^/    /'
  bad "T9: launchUri can throw out of a tap"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
