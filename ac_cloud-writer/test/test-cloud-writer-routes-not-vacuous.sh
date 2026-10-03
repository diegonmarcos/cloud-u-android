#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-cloud-writer-routes.sh can actually go red                  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# One planted defect per assertion, each in a COPY of the app (never the
# working tree), and the sibling tester must go red on the named check. An
# anchor that no longer matches exactly once is itself a failure: a break that
# plants nothing proves nothing.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
TESTER="ac_cloud-writer/test/test-cloud-writer-routes.sh"
[ -f "$ROOT/$TESTER" ] || { echo "FAIL   $TESTER is missing — there is nothing to prove"; exit 1; }

python3 - "$ROOT" "$TESTER" <<'PYEOF'
import json, os, shutil, subprocess, sys, tempfile

root, tester = sys.argv[1], sys.argv[2]
APP = "ac_cloud-writer"
SRC = APP + "/app/src/main/java/com/diegonmarcos/cloudwriter/"
RES = APP + "/app/src/main/res/"


def drop_key(key):
    def edit(text):
        d = json.loads(text)
        del d["update"][key]
        return json.dumps(d, indent=2)
    return edit


# (label, file, (old, new) or callable, the check that must go red)
BREAKS = [
    ("probe asks before the bind", SRC + "ServingApp.kt",
     ("            if (!runner.awaitBound(bindWaitMs)) return ServingApp(pkg, label, version, State.SILENT)\n", ""), "S1"),
    ("awaitBound stops polling", SRC + "WriterTools.kt",
     ("while (!client.isConnected()) {", "if (!client.isConnected()) {"), "S1"),
    ("too-old string names no app", RES + "values/strings.xml",
     ("<string name=\"status_serving_app_too_old\">%1$s (version", "<string name=\"status_serving_app_too_old\">The app (version"), "S2"),
    ("silent folded into too old", SRC + "MainActivity.kt",
     ("ServingApp.State.SILENT -> getString(", "ServingApp.State.SILENT -> getText("), "S2"),
    ("no Store button", SRC + "MainActivity.kt",
     ("storeRepair.value = serving.needsStore", "storeRepair.value = false"), "S3"),
    ("no Store fallback", APP + "/build.json", drop_key("fallback_url"), "S4"),
]

failures = []
for label, rel, edit, expect in BREAKS:
    stage = tempfile.mkdtemp(prefix="writer-routes-")
    try:
        open(os.path.join(stage, ".git"), "w").close()
        shutil.copytree(os.path.join(root, APP), os.path.join(stage, APP),
                        ignore=shutil.ignore_patterns("build", ".gradle"))
        target = os.path.join(stage, rel)
        text = open(target, encoding="utf-8").read()
        if callable(edit):
            try:
                planted = edit(text)
            except (KeyError, ValueError) as exc:
                failures.append("%s: the planted edit could not be applied (%s)" % (label, exc)); continue
        else:
            old, new = edit
            if text.count(old) != 1:
                failures.append("%s: the anchor matched %d times, not once — this harness no longer breaks what it names"
                                % (label, text.count(old))); continue
            planted = text.replace(old, new, 1)
        if planted == text:
            failures.append("%s: the planted edit changed nothing" % label); continue
        open(target, "w", encoding="utf-8").write(planted)
        out = subprocess.run(["bash", os.path.join(stage, tester)], capture_output=True, text=True)
        red = [l for l in out.stdout.split("\n") if l.startswith("FAIL") and (" %s " % expect) in l]
        if out.returncode == 0:
            failures.append("%s: the tester still passed — %s proves nothing" % (label, expect))
        elif not red:
            seen = "; ".join(l for l in out.stdout.split("\n") if l.startswith("FAIL"))
            failures.append("%s: went red but not on %s (saw: %s)" % (label, expect, seen or out.stderr[-300:]))
        else:
            print("ok     watched red: %s (%s)" % (label, expect))
    finally:
        shutil.rmtree(stage, ignore_errors=True)

print("── %d of %d planted defects were caught ──" % (len(BREAKS) - len(failures), len(BREAKS)))
for f in failures:
    print("FAIL   " + f)
sys.exit(1 if failures else 0)
PYEOF
