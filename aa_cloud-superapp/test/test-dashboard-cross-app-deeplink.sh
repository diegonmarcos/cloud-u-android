#!/usr/bin/env bash
# Tester: Cloud ▸ Dashboard's MyFin and MyHealth icons are cross-app deep links
# into Cloud-Me, and nothing of the two deleted SuperApp pages is left behind.
#
# MyFin and MyHealth were SuperApp pages (sections `myfin` and `health`). Both
# surfaces now live in Cloud-Me — Buro ▸ Fin and Projects ▸ Health — and the two
# icons stayed put, retargeted to `extapp:cloud-me#<target>`: launchExternalApp
# launches Cloud-Me and hands everything after the `#` to it as the
# `shortcut_action` extra, which Cloud-Me's MainActivity.onTarget resolves with
# the same `page:<section>/<page>` grammar.
#
# Three ways that can rot, all of them silent on a phone:
#   • the fragment names a page Cloud-Me does not have → the icon opens Cloud-Me
#     at its front door and looks like it half-worked,
#   • cloud-me loses its external_apps install_apk_url → the icon does nothing
#     at all when Cloud-Me is not installed,
#   • sections[health].metrics is "cleaned up" along with the pages → Configs ▸
#     Permissions silently reports 0 / 0 Health Connect grants.
#
# Static and data-driven: build.json on both sides is the only source of truth.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
ME_BJ="$APP/../ac_cloud-me/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== T1: the two SuperApp pages are gone =="
python3 - "$BJ" <<'PY' && ok "no myfin section, no health pages, no page:myfin/ or page:health/ target" || bad "a deleted page still exists or is still targeted"
import json, sys
d = json.load(open(sys.argv[1]))
secs = d["ui"]["sections"]
by_id = {s["id"]: s for s in secs}
assert "myfin" not in by_id, "sections[myfin] still declared"
assert not by_id["health"].get("pages"), "sections[health] still declares pages"

def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values(): yield from walk(v)
    elif isinstance(o, list):
        for v in o: yield from walk(v)

dead = [o["target"] for o in walk(d["ui"])
        if isinstance(o.get("target"), str)
        and o["target"].startswith(("page:myfin/", "page:health/"))]
assert not dead, f"targets still point at the deleted pages: {dead}"
PY

echo "== T2: both icons survive as extapp deep links with an install path =="
python3 - "$BJ" <<'PY' && ok "MyFin + MyHealth tiles → extapp:cloud-me#page:..., cloud-me installable" || bad "a dashboard icon is missing, mistargeted, or has no install path"
import json, sys
d = json.load(open(sys.argv[1]))
cloud = next(s for s in d["ui"]["sections"] if s["id"] == "cloud")
group = next(g for g in cloud["tile_groups"] if g["title"] == "Dashboard")
tiles = {t.get("id"): t for t in group["tiles"]}
want = {"myfin": "extapp:cloud-me#page:buro/fin",
        "myhealth": "extapp:cloud-me#page:projects/health"}
for tid, target in want.items():
    assert tid in tiles, f"Dashboard tile {tid} is gone — the icon must remain"
    assert tiles[tid]["target"] == target, (tid, tiles[tid]["target"], "!=", target)
    assert tiles[tid].get("icon"), f"{tid} tile has no icon"
# A missing target app must not be a dead icon: launchExternalApp falls back to
# downloading install_apk_url for install_package.
me = next(e for e in d["ui"]["external_apps"] if e.get("id") == "cloud-me")
assert me["hub_package"] == "com.diegonmarcos.cloudme", me
assert me["install_package"] and me["install_apk_url"].endswith(".apk"), me
PY

echo "== T3: every extapp deep-link fragment names a page Cloud-Me really has =="
if [ ! -f "$ME_BJ" ]; then
  echo "  SKIP: ac_cloud-me not checked out beside this repo"
else
python3 - "$BJ" "$ME_BJ" <<'PY' && ok "each #page:<section>/<page> resolves in ac_cloud-me/build.json" || bad "a deep link points at a Cloud-Me page that does not exist"
import json, sys
sa = json.load(open(sys.argv[1]))
me = json.load(open(sys.argv[2]))

# Every navigable id Cloud-Me's MainActivity.open can resolve: a section, a tab,
# a sub-tab, and a container tab (which opens its first child).
#
# RECURSIVE, because Cloud-Me's strips are no longer two deep — Projects >
# Health > Workout > Gym is three, and Section.page() still resolves the whole
# chain into one flat list per section. A fixed two-level walk would report a
# page that exists as missing, which fails this test for a tile that works.
def ids_of(pages_json):
    for p in pages_json:
        yield p["id"]
        yield from ids_of(p.get("pages", []))

pages = {s["id"]: list(ids_of(s.get("pages", []))) for s in me["ui"]["sections"]}

def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values(): yield from walk(v)
    elif isinstance(o, list):
        for v in o: yield from walk(v)

links = [o["target"] for o in walk(sa["ui"])
         if isinstance(o.get("target"), str)
         and o["target"].startswith("extapp:cloud-me#")]
assert links, "no extapp:cloud-me deep link left to check"
for t in links:
    frag = t.split("#", 1)[1]
    assert frag.startswith("page:"), f"{t}: only page: targets are deep-linkable today"
    sec, _, pg = frag.removeprefix("page:").partition("/")
    assert sec in pages, f"{t}: Cloud-Me has no section '{sec}'"
    assert pg in pages[sec], f"{t}: Cloud-Me section '{sec}' has no page '{pg}'"
PY
fi

echo "== T4: what the SuperApp still needs from Health stayed =="
python3 - "$BJ" <<'PY' && ok "sections[health].metrics intact (34 perms) and libs:health still a module; libs:fin gone" || bad "Configs ▸ Permissions lost its Health Connect taxonomy, or the module graph is inconsistent"
import json, sys
d = json.load(open(sys.argv[1]))
health = next(s for s in d["ui"]["sections"] if s["id"] == "health")
metrics = health.get("metrics") or []
assert metrics, "sections[health].metrics is empty — HealthMetrics.allPermissions would be 0"
perms = {k for m in metrics for k in m["perm_keys"]}
assert len(perms) == 34, f"expected 34 Health Connect perms, found {len(perms)}"
mods = d["modules"]
assert "libs:health" in mods, "libs:health dropped but Configs ▸ Permissions still uses it"
assert "libs:fin" not in mods, "libs:fin still in the module graph after MyFin left"
assert "libs:fin" not in mods["app"]["depends_on"], "app still depends on libs:fin"
assert "myfin_mock" not in d["ui"], "ui.myfin_mock outlived the MyFin page"
PY

echo "== T5: the Dashboard row reads left-to-right as declared, and every tile in it goes somewhere =="
python3 - "$BJ" <<'PY' && ok "MyBuro · MyProjects · MyFin · MyHealth · MySocials · | · PM Boards, separator inert" || bad "the Dashboard row is out of order, or carries a tile that leads nowhere"
import json, sys
d = json.load(open(sys.argv[1]))
cloud = next(s for s in d["ui"]["sections"] if s["id"] == "cloud")
group = next(g for g in cloud["tile_groups"] if g["title"] == "Dashboard")
tiles = group["tiles"]

# Order is the product decision this row exists to express: the four Cloud-Me
# deep links first, then MySocials, then a rule, then PM Boards last because it
# is a web service and — see its own _doc — a route that 404s until paca ships.
# Nothing about a JSON array's order fails on its own when someone appends to
# it, which is exactly why it is asserted here.
order = [t.get("id") for t in tiles]
assert order == ["myburo", "myprojects", "myfin", "myhealth",
                 "mysocials", "dashboard-sep", "pmboards"], order

# The separator is a glyph, not a control. A cell with no target that is still
# clickable is the dead-tap defect; declaring it keeps GroupedTilesFragment and
# Sections.TileGroup.destinations able to tell decoration from a destination.
sep = tiles[order.index("dashboard-sep")]
assert sep.get("separator") is True, sep
assert not sep.get("target"), f"a separator must not carry a target: {sep}"
assert sep.get("label"), "parseTilesInline reads label with getString — it must exist"

# A tile that is NOT a separator must go somewhere, in a grammar the launcher
# actually dispatches. This is the check that would have caught a tile pointing
# at a page nobody declares — T3 above then proves the cloud-me ones resolve.
apps = {a["id"] for a in d["ui"].get("external_apps", [])}
for t in tiles:
    if t.get("separator"):
        continue
    target = t.get("target", "")
    assert target, f"tile {t.get('id')} has no target — it would be a dead tap"
    assert not t.get("separator"), t
    if target.startswith("extapp:"):
        app = target.removeprefix("extapp:").split("#", 1)[0]
        assert app in apps, f"tile {t['id']} → no such ui.external_apps id '{app}'"
    else:
        assert target.startswith(("page:", "section:", "action:", "http")), \
            f"tile {t['id']} target '{target}' — unknown grammar"

want = {"myburo": "extapp:cloud-me#page:buro/summary",
        "myprojects": "extapp:cloud-me#page:projects/pm"}
for tid, target in want.items():
    got = next(t for t in tiles if t.get("id") == tid)
    assert got["target"] == target, (tid, got["target"], "!=", target)
    assert got.get("icon"), f"{tid} tile has no icon"
PY

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
