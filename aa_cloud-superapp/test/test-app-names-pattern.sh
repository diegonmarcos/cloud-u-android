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

print()
print("── test-app-names-pattern: %d passed, %d failed ──" % (passed, failed))
sys.exit(1 if failed else 0)
PY
