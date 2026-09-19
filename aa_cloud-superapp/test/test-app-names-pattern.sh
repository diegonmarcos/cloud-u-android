#!/usr/bin/env bash
# Tester (#351): every application in the constellation has ONE name, it is
# written cloud-<name> (or c3-<name> for the c3 family), and no surface spells it
# any other way.
#
# THE FAILURE THIS EXISTS FOR. Configs ▸ Constellation listed
#   "Cloud Chat (Mattermost)", "Cloud Dialer (Fossify)", "Cloud Mail",
#   "Cloud Office", "Cloud Terminal (Nix)", "Cloud Terminal (Termux)", "Messenger"
# beside cloud-agenda, cloud-browser and the rest. The owner had asked for the
# pattern twice before. The root cause was not the list: fork-apps declared their
# name in .forks.<key>.label while top-level apps used .name, and the superapp
# restated every name again in ui.external_apps, onehand.apps,
# ui.ai_routing_peers, tiles and floating_nav — so a rename fixed one copy and
# the other copies kept the old spelling.
#
# The declaration is each application's own build.json::.name. regen.sh reads
# it into constellation-fleet.json for BOTH branches, and the fleet file is what
# this tester trusts as the list of names.
#
#   T1  every fleet entry of kind "app" is named cloud-<x> or c3-<x>, unique
#   T2  that name IS the app's build.json::.name, and no fork entry of the same
#       package declares a second name in .forks.<key>.label
#   T3  ui.external_apps restates no name: label absent (the id is shown) and the
#       id is a fleet name
#   T4  any object that names a fleet package (package/pkg) with a label uses the
#       fleet name for it
#   T5  sweep: no label/title/name anywhere in those build.json files spells a
#       fleet name the old way ("Cloud Chat (Mattermost)", "Cloud-IDE",
#       "C3-Watchdog") — the regression a new hand-written roster would bring back
#
# Scope: kind "app". Library APKs ("Lib: <module>") are a separate naming family
# and are not applications. Captions that name a FUNCTION rather than an app
# (tiles "Notes", "Mail", the "Messenger" inbox class) do not spell an app name
# and are left alone by T5 on purpose.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"                    # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → repository root
FLEET="$APP/data/constellation-fleet.json"
for f in "$FLEET" "$APP/build.json"; do
  [ -f "$f" ] || { echo "ERROR: $f missing — this tester resolved a path to nothing" >&2; exit 2; }
done
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }

python3 - "$ROOT" "$FLEET" "$APP/build.json" <<'PY'
import json, os, re, sys

root, fleet_path, superapp_path = sys.argv[1:4]
PATTERN = re.compile(r"^(cloud|c3)-[a-z0-9]+(-[a-z0-9]+)*$")
passed, failed = 0, 0
def check(condition, message):
    global passed, failed
    if condition:
        passed += 1
    else:
        failed += 1
        print("  FAIL: " + message)

fleet = [entry for entry in json.load(open(fleet_path))["apps"] if entry.get("kind") == "app"]
check(len(fleet) > 0, "constellation-fleet.json lists no application — nothing below would be checked")
names = [entry["label"] for entry in fleet]
by_package = {}
for entry in fleet:
    for package in (entry.get("package"), entry.get("alt_id")):
        if package:
            by_package[package] = entry["label"]

print("== T1: every application is named cloud-<x> or c3-<x> ==")
for entry in fleet:
    check(PATTERN.match(entry["label"]) is not None,
          "fleet entry %s is labelled %r" % (entry["id"], entry["label"]))
check(len(names) == len(set(names)), "two applications share a name: %s"
      % sorted({n for n in names if names.count(n) > 1}))

print("== T2: the name is declared once, in the app's own build.json::.name ==")
build_files = {}
tree = "/tree/main/"
for entry in fleet:
    directory = entry["repo_url"].split(tree, 1)[1] if tree in entry["repo_url"] else ""
    path = os.path.join(root, directory, "build.json")
    if not os.path.isfile(path):
        check(False, "fleet entry %s points at %s, which has no build.json" % (entry["id"], directory))
        continue
    data = json.load(open(path))
    build_files[directory] = data
    check(data.get("name") == entry["label"],
          "%s/build.json::name is %r but the fleet shows %r — regenerate data/regen.sh or fix the declaration"
          % (directory, data.get("name"), entry["label"]))
    for key, fork in (data.get("forks") or {}).items():
        if isinstance(fork, dict) and fork.get("app_id") == entry["package"] and "label" in fork:
            check(False, "%s/build.json::forks.%s.label = %r is a second name for %s — delete it, .name is the name"
                  % (directory, key, fork["label"], entry["package"]))

superapp = json.load(open(superapp_path))
build_files["aa_cloud-superapp"] = superapp

print("== T3: ui.external_apps shows the id and restates no name ==")
for app in superapp["ui"]["external_apps"]:
    check("label" not in app or app["label"] == app["id"],
          "ui.external_apps[%s].label = %r restates the name — delete it, the id is shown" % (app["id"], app.get("label")))
    check(app["id"] in names, "ui.external_apps id %r is not the name of any fleet application" % app["id"])

def walk(node, path=""):
    if isinstance(node, dict):
        yield path, node
        for key, value in node.items():
            yield from walk(value, path + "." + key)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from walk(value, "%s[%d]" % (path, index))

print("== T4: a label next to one of our packages is that application's name ==")
for directory, data in sorted(build_files.items()):
    for path, node in walk(data):
        package = node.get("package") if isinstance(node.get("package"), str) else node.get("pkg")
        label = node.get("label")
        if isinstance(package, str) and package in by_package and isinstance(label, str):
            check(label == by_package[package],
                  "%s/build.json%s.label = %r, but %s is named %r" % (directory, path, label, package, by_package[package]))

print("== T5: nothing spells an application name the old way ==")
def normalise(text):
    return re.sub(r"[^a-z0-9]", "", text.lower())
normalised_names = [normalise(n) for n in names]
for directory, data in sorted(build_files.items()):
    for path, node in walk(data):
        for key in ("label", "title", "name", "--with-app-name"):
            value = node.get(key)
            if not isinstance(value, str) or PATTERN.match(value):
                continue
            flat = normalise(value)
            if len(flat) <= len("cloud") + 1:
                continue
            if any(flat.startswith(n) or n.startswith(flat) for n in normalised_names):
                check(False, "%s/build.json%s.%s = %r spells an application name outside the pattern"
                      % (directory, path, key, value))

print("== T6: an app whose gradle reads its build.json takes its launcher label from .name ==")
# The home-screen label is the one surface every other check above cannot see:
# it lived in res/values/strings.xml and said "Cloud Browser". Where
# app/build.gradle already parses the app's own build.json, the label can be
# inherited, so a strings.xml copy is a defect. Fork-apps whose label sits inside
# vendored upstream source (Mattermost, Element, Fossify, Termux) have no such
# reader and are not covered here.
#
# #522: THE GRADLE MODULE PATH IS DATA, NOT A CONVENTION. This block used to
# look only at <dir>/app/build.gradle. ac_cloud-notes has never had that path —
# its Android project is nested inside a vendored AFFiNE monorepo at
# packages/frontend/apps/android/App/app — so T6 did not fail on it, it SKIPPED
# it, silently, and a launcher tile reading "AFFiNE" shipped behind 18 green
# checks. An app that needs a different path DECLARES it in
# build.json::android.gradle_module; "app" is the default nobody has to write.
reads_build_json = re.compile(r"JsonSlurper\(\)\s*\.parse\(\s*file\(")
derives_label = re.compile(r"resValue\s*\(?\s*[\"']string[\"']\s*,\s*[\"']app_name[\"']\s*,\s*buildJson\.name\b")
covered = 0
for directory in sorted(build_files):
    data = build_files[directory]
    module = (data.get("android") or {}).get("gradle_module") or "app"
    gradle = os.path.join(root, directory, module, "build.gradle")
    if not os.path.isfile(gradle) or not data.get("name", "").startswith("cloud-"):
        continue
    source = open(gradle).read()
    if not reads_build_json.search(source):
        # Fork-apps whose label sits inside vendored upstream source
        # (Mattermost, Element, Fossify, Termux, Nix-on-Droid) have no reader
        # and are out of scope here, exactly as before #522.
        continue
    covered += 1
    check(derives_label.search(source) is not None,
          "%s/%s/build.gradle reads build.json but does not set app_name from buildJson.name"
          % (directory, module))
    resources = os.path.join(root, directory, module, "src")
    for base, _, files in os.walk(resources):
        if "strings.xml" in files and os.path.basename(base).startswith("values"):
            path = os.path.join(base, "strings.xml")
            if re.search(r'<string\s+name="app_name"', open(path).read()):
                check(False, "%s restates app_name — the launcher label comes from build.json::name"
                      % os.path.relpath(path, root))
check(covered > 0, "T6 found no application whose gradle reads its build.json — the detection matched nothing")
# A declared gradle_module that points at nothing is a check that quietly
# stopped guarding: the app would drop out of T6 exactly as it did before #522.
for directory, data in sorted(build_files.items()):
    module = (data.get("android") or {}).get("gradle_module")
    if module:
        check(os.path.isfile(os.path.join(root, directory, module, "build.gradle")),
              "%s/build.json::android.gradle_module = %r has no build.gradle — T6 would skip this app in silence"
              % (directory, module))

print("== T7: a launcher tile shows a caption, never an application's identity ==")
# THE FAILURE THIS EXISTS FOR (#380, #381). Cloud ▸ Apps drew a Data Apps tile
# captioned "cloud-office" and an Inboxes tile captioned "cloud-matrix": the
# tiles printed the applications' IDENTITY because an identity string had been
# left sitting in the display field. On a tile, `label` is a CAPTION; identity
# travels in `target`, in ui.external_apps[].id and in the app's own
# build.json::name, and none of those changed when the captions were fixed.
#
# Nothing above caught it, and that is structural rather than bad luck: T5
# leaves function-captions ("Notes", "Mail") alone ON PURPOSE, and T4 only
# inspects objects carrying a `package`/`pkg` key, which a tile does not have.
# So the one check that would go red if a caption silently reverted to its
# identity string is this one.
#
# #390: the two AGI tiles #380/#381 left behind have captions again, so this
# set is empty. It is the regression's ledger, not a parked list: NEVER ADD TO
# IT — a tile captioned with an application name is the exact regression this
# test exists to catch, and a stale exemption re-opens the hole it closes.
pending_identity_captions = set()

tiles = [node for _, node in walk(superapp["ui"]["sections"])
         if isinstance(node.get("label"), str) and isinstance(node.get("target"), str)]
check(len(tiles) > 0, "T7 found no tiles under ui.sections — the detection matched nothing")
seen_pending = set()
for path, node in walk(superapp["ui"]["sections"], ".ui.sections"):
    label, target = node.get("label"), node.get("target")
    if not isinstance(label, str) or not isinstance(target, str) or label not in names:
        continue
    if node.get("id") in pending_identity_captions:
        seen_pending.add(node["id"])
        continue
    check(False, "aa_cloud-superapp/build.json%s.label = %r captions a tile with an "
                 "application's identity — labels are captions, identity belongs in target/id"
          % (path, label))
# A stale exemption is a check that quietly stopped guarding something.
check(seen_pending == pending_identity_captions,
      "T7 exempts tile ids %s that no longer caption themselves with an identity — delete them "
      "from pending_identity_captions" % sorted(pending_identity_captions - seen_pending))

print("== T8: the AGI terminal tile is captioned by function, not by the upstream app's own name (#499) ==")
# THE FAILURE THIS EXISTS FOR. #390 gave this tile the caption "Nix" — the
# same word Phone > Apps' own quickmark uses for the same package — but that
# word IS the upstream app's own name (ac_cloud-nix-on-droid ships as
# "Nix-on-Droid"/"cloud-terminal-nix" in the fleet), so the caption still
# spelled an application's identity rather than what tapping it does. Every
# other AGI tile with a target spells the FUNCTION (Browser, Navigation,
# Camera, MyIDE); this one has to as well.
#
# Neither T5 nor T7 can catch a reversion back to "Nix". T5 skips it on
# length: normalise("Nix") == "nix" is shorter than len("cloud")+1, and that
# filter exists so short incidental words do not false-positive T5's prefix
# check. T7 only matches a caption equal to a FULL fleet name
# ("cloud-terminal-nix"), never a three-letter fragment of one. So this is the
# one check that goes red if the caption reverts to the upstream app's name.
agi = next((node for _, node in walk(superapp["ui"]["sections"]) if node.get("title") == "AGI"), None)
check(agi is not None, "T8 found no ui.sections group titled 'AGI' — the detection matched nothing")
nix_tile = next((t for t in agi["tiles"] if t.get("id") == "ai-tmx"), None) if agi else None
check(nix_tile is not None, "T8 found no AGI tile with id 'ai-tmx' — the detection matched nothing")
if nix_tile is not None:
    check(nix_tile.get("label") == "Terminal",
          "aa_cloud-superapp/build.json AGI tile ai-tmx.label = %r, want 'Terminal' — "
          "it must not spell the upstream app's own name" % nix_tile.get("label"))

print()
print("── test-app-names-pattern: %d passed, %d failed ──" % (passed, failed))
sys.exit(1 if failed else 0)
PY
