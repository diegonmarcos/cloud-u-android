#!/usr/bin/env bash
# #678 Store ▸ Cloud ▸ Details: the libs an app uses, and the App settings
# button only for an installed package.
#
# The lib list is DERIVED: each app's own build.json::modules.app.depends_on
# (libs:*) is carried by data/regen.sh onto its fleet row as `libs`, and the
# Details sheet reads that row. This guard iterates every build.json, so its
# assertion count moves with the declarations — no list lives here or in Kotlin.
#
# Overrides for mutation runs: FLEET, LIBS, ROOT.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="${ROOT:-$(cd "$APP/.." && pwd)}"
python3 - "$ROOT" "${FLEET:-$APP/data/constellation-fleet.json}" "${LIBS:-$ROOT/ab_cloud-libs-shared/libs}" <<'PY'
import glob, json, os, re, sys
root, fleet_p, libs = sys.argv[1:4]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def fn(src, name):
    m = re.search(r"\n    (?:private )?fun %s\(.*?(?=\n    (?:private |internal )?(?:fun|val|/\*\*|//)|\n}\s*$)" % name, src, re.S)
    return m.group(0) if m else ""

fleet = json.loads(read(fleet_p))["apps"]
by_id = {a["id"]: a for a in fleet}
by_pkg = {a.get("package"): a for a in fleet}

print("== T1: every app row's libs == its own build.json depends_on ==")
seen = 0
for bj in sorted(glob.glob(os.path.join(root, "*/build.json")) + glob.glob(os.path.join(root, "*/*/build.json"))):
    try: d = json.loads(read(bj))
    except Exception: continue
    deps = ((d.get("modules") or {}).get("app") or {}).get("depends_on") or []
    want = ["lib-" + x[5:] for x in deps if isinstance(x, str) and x.startswith("libs:")]
    pkg = (d.get("android") or {}).get("application_id")
    row = by_pkg.get(pkg) if pkg else None
    if row is None or row.get("kind", "app") == "lib": continue
    seen += 1
    got = row.get("libs", [])
    rel = os.path.relpath(bj, root)
    # one assertion per declared edge, so adding a dependency moves the count
    for l in want:
        if l in got: ok("%s -> %s (declared in %s)" % (row["id"], l, rel))
        else: bad("%s: %s declares %s but the fleet row's libs lack it" % (row["id"], rel, l))
    extra = [l for l in got if l not in want]
    if extra: bad("%s: fleet libs %s are not in %s depends_on — not derived" % (row["id"], extra, rel))
if seen: ok("%d app rows checked against their declarations" % seen)
else: bad("no app row matched any build.json — the check measured nothing")

print("== T2: Details reads the row, resolves each lib to its Store row ==")
kt = os.path.join(libs, "appstore/src/main/java/com/diegonmarcos/superapp/appstore")
sheet = read(os.path.join(kt, "ApkDetailSheet.kt"))
fleetkt = read(os.path.join(libs, "updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"))
body = fn(sheet, "renderLibs")
if 'optJSONArray("libs")' in fleetkt: ok("Fleet.parse reads the row's `libs`")
else: bad("Fleet.parse does not read `libs` from the fleet row")
if "app.libs" in body and "app.engineLibs" in body: ok("renderLibs iterates app.libs and app.engineLibs")
else: bad("renderLibs does not draw from the fleet row's libs/engineLibs")
if re.search(r'"lib-[a-z]', body): bad("renderLibs names a lib literally")
else: ok("no lib id is written in renderLibs")
if "Fleet.parse(" in body and "fleet[id]" in body and "show(activity, row" in body:
    ok("each lib resolves through the fleet to its own row's Details")
else: bad("a lib in Details is not resolved to its Store row")
if "no Store row for" in body: ok("an unresolved lib says it has no Store row")
else: bad("an unresolved lib is not reported")
dangling = sorted({l for a in fleet for l in a.get("libs", []) if l not in by_id})
print("  info: libs with no Store row (shown as such): %s" % (dangling or "none"))

print("== T3: compiled-in is never presented as installable ==")
m = re.search(r'group\("Compiled in", app\.libs, "([^"]*)"\)', body)
if m and "nothing to install" in m.group(1): ok("compiled-in libs say: %r" % m.group(1))
else: bad("compiled-in libs are not labelled as needing no install")
m = re.search(r'group\("Installed separately", app\.engineLibs, "([^"]*)"\)', body)
if m and "installed" in m.group(1): ok("engine libs say: %r" % m.group(1))
else: bad("separately-installed engine libs are not labelled as such")

print("== T4: App settings is absent for an uninstalled app ==")
show = fn(sheet, "show")
if re.search(r'a\.id == "app_settings" && Fleet\.installedId\(ctx, app\) == null\) continue', show):
    ok("the app_settings button is skipped when the package is not installed")
else: bad("the app_settings button is drawn for an uninstalled app")

print("\nRESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
