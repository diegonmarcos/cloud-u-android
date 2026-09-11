# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ship-repo-workflow-engine.sh ───
#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-ship-repo-workflow-engine                          ║
# ║                                                                  ║
# ║ Compiles each config tier's src/ → that tier's dist/ and deploys ║
# ║ generated artifacts to .github/, .gitmodules, gitconfig, hooks.   ║
# ║                                                                  ║
# ║ Invoked as: ./9_others/build.sh (via symlink)                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -eu

# Repo root by upward search, not a fixed ../ count. This script has moved
# twice now (1_configs/src/scripts → 1_configs/src/gha/scripts →
# 1_cicd/src/scripts) and each time a literal ../../.. would have resolved one
# level off, silently, with every path built from it landing in the wrong place.
CLOUD_ANDROID_ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"

# Config tiers. Each owns its own dist/, so a source and its compiled form sit
# together instead of every artifact landing in one flat 1_configs/dist.
GIT_SRC="$CLOUD_ANDROID_ROOT/0_git/src";     GIT_DIST="$CLOUD_ANDROID_ROOT/0_git/dist"
APPS_SRC="$CLOUD_ANDROID_ROOT/0_apps/src";   APPS_DIST="$CLOUD_ANDROID_ROOT/0_apps/dist"
CICD_SRC="$CLOUD_ANDROID_ROOT/1_cicd/src";   CICD_DIST="$CLOUD_ANDROID_ROOT/1_cicd/dist"
LIB_SRC="$CLOUD_ANDROID_ROOT/9_others/src"

. "$CICD_SRC/scripts/cloud-android-ship-lib.sh"

# 1_cicd/dist is DERIVED IN FULL from 1_cicd/src, so it is rebuilt from empty
# rather than copied over. Copying over only ever adds: a file deleted from
# src/ stayed in dist/ forever, and dist/scripts is what GitHub actually runs
# (.github/workflows/scripts symlinks to it). That accumulation is exactly the
# drift this generator is supposed to prevent, and it had already produced
# three fossils -- a literal file named "*.sh" from a glob that once failed to
# expand, and two guards that src/ deleted but dist/ kept serving.
#
# Only these three directories are purged. .github/workflows is NOT, because
# it holds workflows with no source (enhance-silence-guard.yml,
# ship-superapp-data-regen.yml) that a purge would silently delete; and the
# dotfiles tier is additive on purpose because its targets mix managed config
# with per-machine state.
rm -rf "$CICD_DIST/scripts" "$CICD_DIST/cicd" "$CICD_DIST/actions" "$CICD_DIST/data"
mkdir -p "$CICD_DIST/scripts" "$CICD_DIST/cicd" "$CICD_DIST/actions" "$CICD_DIST/data" \
         "$GIT_DIST/hooks" "$GIT_DIST/modules"

# ── scripts: copy source → dist with read-only header ──────────────
log_step "dist/scripts"
for f in "$CICD_SRC/scripts/"*.sh "$CICD_SRC/scripts/"*.py; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    {
        echo "# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/$base ───"
        cat "$f"
    } > "$CICD_DIST/scripts/$base"
    chmod +x "$CICD_DIST/scripts/$base"
done

# ── prune dist artifacts whose source is gone ──────────────────────
#
# Every loop in this generator WRITES into dist/ and none of them removes, so a
# renamed or deleted source leaves its old artifact behind for ever. That twin is
# executable, carries the same GENERATED header and looks entirely current, and
# `.github/workflows/generated-up-to-date` then fails on it — not only for the
# commit that caused it, but for the next stranger who pushes on top, who has no
# idea what the diff is about. Renaming the enhance silence guard did exactly that.
#
# dist/ is derived in full, so a file in it with no source is not history, it is
# residue. Deliberately limited to the two dist directories this script generates:
# .github/workflows/ is NOT pruned, because a workflow can legitimately live there
# without a source in this tier and deleting a live one is not a generator's call.
log_step "prune dist artifacts with no source"
for _pair in "scripts" "cicd"; do
    _dist="$CICD_DIST/$_pair"; _src="$CICD_SRC/$_pair"
    for _f in "$_dist"/*; do
        [ -e "$_f" ] || continue
        _b=$(basename "$_f")
        [ -e "$_src/$_b" ] || { echo "  pruned $_pair/$_b — 1_cicd/src/$_pair/$_b no longer exists"; rm -f "$_f"; }
    done
done
unset _pair _dist _src _f _b

# ── cicd: engine ↔ vendored build.sh parity ────────────────────────
#
# Every app ships a VENDORED COPY of its engine at <app>/build.sh, and the ship
# workflow runs THAT file — 1_cicd/src/scripts/*-engine.sh is only its source.
# Editing the engine alone changes nothing at ship time, which is exactly how
# the ABI gate and the GHCR auto-delete gate came to exist in the engine and
# not in the file GitHub executed. That drift then produced two wrong outage
# diagnoses in one day, because the code being read was not the code running.
#
# So the copy relationship is now DECLARED (build.json::vendored_engine) and
# ENFORCED here: byte-identical or the generator goes red. It is also why the
# publish gate hashes the vendored build.sh rather than the engine — a
# parity-fixing sync lands in the app tree and correctly republishes that app.
log_step "verify engine ↔ vendored build.sh parity"
_parity_bad=0
for _bj in "$CLOUD_ANDROID_ROOT"/*/build.json; do
    [ -e "$_bj" ] || continue
    _app="$(basename "$(dirname "$_bj")")"
    _eng="$(jq -r '.vendored_engine // empty' "$_bj")"
    [ -n "$_eng" ] || continue
    _src="$CICD_SRC/scripts/$_eng"
    if [ ! -f "$_src" ]; then
        log_error "$_app/build.json declares vendored_engine=$_eng — no such engine in 1_cicd/src/scripts/"
        _parity_bad=1; continue
    fi
    if ! cmp -s "$_src" "$CLOUD_ANDROID_ROOT/$_app/build.sh"; then
        log_error "$_app/build.sh has DRIFTED from its engine $_eng — the vendored copy is what CI runs"
        log_error "  resync with: cp 1_cicd/src/scripts/$_eng $_app/build.sh"
        _parity_bad=1
    fi
done
unset _bj _app _eng _src
[ "$_parity_bad" = "0" ] || exit 1
unset _parity_bad

# ── cicd: derive trigger paths from the data that declares them ─────
#
# on:push:paths was hand-maintained beside build.json, so it drifted silently
# and in the worst way: a workflow watching nothing real never runs and nobody
# gets an error. Three had already drifted when a check was added here -- two
# watching their own generated copy (self-triggering on unrelated commits),
# one watching a sibling repo a path filter can never see.
#
# Checking was not enough: the check told you a module-map dir was unwatched
# and then you hand-added it, which is the drift it was meant to end. Every
# dir the module map declares is now ADDED here automatically, and dead
# entries (paths no longer in the repo, and the workflow's own generated copy,
# which self-triggers on every unrelated commit that runs this generator) are
# dropped.
#
# It UNIONS rather than replaces, deliberately. A module map is a floor, not a
# ceiling: the fork apps have no `modules` block at all and re-point
# projectDir from settings.gradle.kts, and ab_cloud-libs-shared/lib-apks
# legitimately builds EVERY shared module — an input set no map expresses. A
# derivation that replaced the block silently deleted five real triggers from
# media-center on its first run, which is the exact failure mode (a workflow
# watching nothing real never runs and reports no error) this section exists
# to prevent.
#
# The publish gate then hashes THIS list rather than a second, parallel one —
# see cloud-android-source-identity.sh. Anything that can start a build is
# therefore also something the gate weighs, so the gate can never skip a
# rebuild that a real change triggered.
log_step "sync workflow triggers"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYEOF' || exit 1
import glob, json, os, re, sys

root = sys.argv[1]
apps = {d for d in os.listdir(root)
        if os.path.exists(os.path.join(root, d, "build.json"))}
bad = []

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/*.yml"))):
    name = os.path.basename(wf)
    text = open(wf).read()
    lines = text.split("\n")
    start = next((i for i, l in enumerate(lines) if l.strip() == "paths:"), None)
    if start is None:
        continue
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].strip() and not lines[i].startswith("      ")), len(lines))

    entries = [m.group(1) for m in
               (re.match(r'^      - "([^"]+)"', l) for l in lines[start + 1:end]) if m]

    # The app is the workflow's declared WORK_DIR, not its filename. Two lib
    # aggregators (ship-cloud-libs, ship-cloud-keyboard-libs) build a
    # directory under ab_cloud-libs-shared/ whose name does not match their
    # slug, and a slug match sent the derivation at the wrong build.json.
    m = re.search(r'^  WORK_DIR:\s*(\S+)\s*$', text, re.M)
    app = m.group(1) if m and os.path.exists(
        os.path.join(root, m.group(1), "build.json")) else None
    if not app:
        slug = re.sub(r"^(ship|test)-|\.yml$", "", name)
        app = next((a for a in apps if a.split("_", 1)[-1] == slug), None)
    if not app:
        # Not an app workflow (ship.yml, health.yml, ...) -- only validate.
        for e in entries:
            base = e.split("*")[0].rstrip("/")
            if base and not os.path.exists(os.path.join(root, base)):
                bad.append(f"{name}: watches a path not in this repo: {e}")
        continue

    derived = [f"{app}/**"]
    modules = json.load(open(os.path.join(root, app, "build.json"))).get("modules", {})
    if isinstance(modules, dict):
        for mod in modules.values():
            if not isinstance(mod, dict) or not mod.get("dir"):
                continue
            shared = os.path.normpath(os.path.join(app, mod["dir"]))
            if shared.startswith(app + os.sep):
                continue
            derived.append(shared + "/**")
    derived.append(f"1_cicd/src/cicd/{name}")

    # Hand-written entries survive unless they are dead (a path a filter can
    # never match) or the workflow's own generated copy.
    kept = [e for e in entries
            if not e.startswith(".github/workflows/")
            and os.path.exists(os.path.join(root, e.split("*")[0].rstrip("/") or "."))]
    for e in entries:
        if e not in kept:
            print(f"  dropped {name}: {e}")

    final = sorted(set(derived) | set(kept))
    header = ["      # MANAGED by cloud-android-ship-repo-workflow-engine.sh: every dir",
              f"      # {app}/build.json::modules declares is added automatically, dead",
              "      # entries are dropped, and cloud-android-source-identity.sh hashes",
              "      # exactly this list — so nothing can trigger a build the publish",
              "      # gate does not weigh. Extra entries no module map can express",
              "      # (settings.gradle.kts references, scan roots) are kept: add them",
              "      # here and they stay."]

    # A comment inside paths: is an author explaining why a trigger is, or is
    # deliberately not, there -- and this block is the only place that reasoning
    # lives. Rebuilding from the header template alone DELETED it:
    # ship-c3-morpheus.yml lost eight lines explaining why it watches no shared
    # lib directory, and it lost them silently, which is a worse failure than the
    # drift the rewrite exists to fix. Anything that is not one of OUR header
    # lines belongs to the author and is carried through verbatim.
    managed = {line.strip() for line in header}

    def is_header_line(line):
        text = line.strip()
        # The second header line carries the app name, so it is matched by shape
        # rather than by text: a renamed app must not leave its old line behind.
        return text in managed or bool(re.match(
            r'#\s+\S+/build\.json::modules declares is added automatically, dead$', text))

    authored = [line for line in lines[start + 1:end]
                if line.strip().startswith("#") and not is_header_line(line)]

    block = ["    paths:"] + header + authored + [f'      - "{e}"' for e in final]
    new = "\n".join(lines[:start] + block + lines[end:])
    if new != text:
        open(wf, "w").write(new)
        print(f"  synced {name}: {len(entries)} → {len(final)} trigger paths")

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYEOF

# ── cicd: a ship run that published nothing must not be green ──────
#
# The guard existed on ship-cloud-superapp ALONE, written after the 2026-09-05
# update deadlock, and was never generalised — so the other 27 ship workflows
# could each report success having published nothing, which is the failure the
# owner pays for most often: he installs, sees no change, and reports the
# FEATURE as broken.
#
# INJECTED, not hand-copied into 28 files. The condition that decides whether a
# leg was SELECTED to publish is the publish step's own `if:`, and it is carried
# across here verbatim. That is the whole point: hand-copying it would create a
# second answer to "was this app selected?", the two would drift, and the guard
# would start firing on runs where publishing nothing is correct — which is how
# a guard gets disabled within a day. This repository has already paid for two
# copies of one idea twice (#228, #209).
#
# The body lives in cloud-android-publish-guard.sh — ONE implementation, called
# by all of them.
#
# REFUSES TO GENERATE if a ship workflow runs the publish gate and no step in
# that job carries `id: publish`. A new ship workflow therefore cannot be added
# without answering the question, and `generated files are up to date` runs this
# on every push, so it cannot rot back out either.
log_step "inject the published-nothing guard"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYGUARD' || exit 1
import glob, os, re, sys

root = sys.argv[1]
BEG = "      # ── MANAGED-PUBLISH-GUARD: injected by cloud-android-ship-repo-workflow-engine.sh ──"
END = "      # ── end MANAGED-PUBLISH-GUARD ──"
DOC = [
    "      # Runs on everything but a cancellation, and is HANDED the two facts it",
    "      # judges: the publish gate's own answer to \"did this source move?\", and the",
    "      # `id: publish` step's own `if:` rendered to true/false. Putting that `if:`",
    "      # on this step instead — the obvious generalisation — would stop the guard",
    "      # running in exactly the case it exists for, a publish skipped by its own",
    "      # condition. Copied verbatim from that step, so there is one copy in the",
    "      # file and the two cannot drift.",
    "      # Edit 1_cicd/src/scripts/cloud-android-publish-guard.sh, never this block.",
]
bad = []

def strip_managed(lines):
    out, i = [], 0
    while i < len(lines):
        if lines[i] == BEG:
            while i < len(lines) and lines[i] != END:
                i += 1
            i += 1                                    # past END
            if i < len(lines) and lines[i].strip() == "":
                i += 1                                # and its trailing blank
            while out and out[-1].strip() == "":
                out.pop()                             # and its leading blank
            continue
        out.append(lines[i]); i += 1
    return out

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/ship-*.yml"))):
    name = os.path.basename(wf)
    text = open(wf).read()
    lines = strip_managed(text.split("\n"))

    marks = [i for i, l in enumerate(lines) if l == "        id: publish"]
    if not marks:
        # Only workflows that actually gate a publish owe a guard. A workflow
        # with no publish gate publishes nothing this can be asserted about.
        if "cloud-android-publish-gate.sh check" in text:
            bad.append("%s: runs the publish gate but no step carries `id: publish`"
                       " -- the published-nothing guard would have no subject" % name)
        new = "\n".join(lines)
        if new != text:
            open(wf, "w").write(new)
        continue

    for m in reversed(marks):                          # back to front: indices hold
        start = m
        while start >= 0 and not lines[start].startswith("      - "):
            start -= 1
        if start < 0:
            bad.append("%s: `id: publish` outside any step" % name); continue

        end = start + 1
        while end < len(lines) and (lines[end].strip() == ""
                                    or lines[end].startswith("        ")):
            end += 1
        while end > start + 1 and lines[end - 1].strip() == "":
            end -= 1

        cond = "true"
        for j in range(start + 1, end):
            if not lines[j].startswith("        if:"):
                continue
            # A wrapped `if:` cannot be carried across by this line-based
            # rewrite, and half a condition is worse than none. Refuse.
            if j + 1 < end and lines[j + 1].startswith("          "):
                bad.append("%s: the `id: publish` step's `if:` spans several lines;"
                           " put it on one line so the guard can carry it verbatim" % name)
                cond = None
                break
            v = lines[j].split("if:", 1)[1].strip()
            if v.startswith("${{") and v.endswith("}}"):
                v = v[3:-2].strip()
            # A status-check function is legal ONLY inside an `if:`. This
            # condition is HANDED to the guard as an argument, and GitHub
            # refuses to parse a workflow that calls one anywhere else -- it
            # then reports the run under the FILENAME instead of the workflow
            # name, which is how six ship workflows silently stopped existing
            # on 2026-09-10. Refusing here is the only place that can be caught
            # before a push. `success()` is also redundant: GitHub applies it
            # implicitly to any `if:` that names no status function, so
            # deleting it changes nothing about when the publish runs.
            if re.search(r"\b(success|failure|cancelled|always)\(\)", v):
                bad.append("%s: the `id: publish` step's `if:` calls a status function"
                           " (%s). It is redundant -- GitHub adds success() implicitly --"
                           " and it makes this workflow unparseable once the guard is"
                           " handed the expression. Remove it." % (name, v))
                cond = None
                break
            cond = v
            break
        if cond is None:
            continue

        # The gate's answer, but ONLY where a gate exists. Two ship workflows
        # (ship-cloud-libs, which gates per-asset inside its own build.sh, and
        # ship-garmin-watchface, which has no gate at all) define no step with
        # `id: gate`, and a reference to a step that is not there renders as an
        # empty string that reads like an answer. publish-gate-blast-radius
        # already forbids exactly that, fleet-wide, and caught this. Where there
        # is no gate, the literal `false` is the truthful value: no gate skipped
        # this application, because there is no gate.
        jstart = 0
        for j in range(start, -1, -1):
            if re.match(r"^  [A-Za-z_][A-Za-z0-9_-]*:\s*$", lines[j]):
                jstart = j; break
        jend = len(lines)
        for j in range(start + 1, len(lines)):
            if re.match(r"^  [A-Za-z_][A-Za-z0-9_-]*:\s*$", lines[j]):
                jend = j; break
        gate = ("${{ steps.gate.outputs.skip }}"
                if any(l == "        id: gate" for l in lines[jstart:jend]) else "false")

        app = re.sub(r"^ship-|\.yml$", "", name)
        for j in range(start, -1, -1):
            mo = re.match(r"^\s*WORK_DIR:\s*(\S+)\s*$", lines[j])
            if mo:
                app = mo.group(1); break

        guard = ["", BEG] + DOC + [
            "      - name: A run that published nothing must not be green",
            "        if: ${{ !cancelled() }}",
            "        run: sh 1_cicd/dist/scripts/cloud-android-publish-guard.sh"
            ' "%s" "${{ steps.publish.outcome }}" "%s"'
            ' "${{ %s }}" "${{ github.event_name }}"' % (app, gate, cond),
            END,
            "",
        ]
        lines[end:end] = guard

    new = "\n".join(lines)
    if new != text:
        open(wf, "w").write(new)
        print("  guarded %s" % name)

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYGUARD

# ── the test-coverage inventory, as data ──────────────────────────
#
# 24 of the 28 ship workflows executed no test of any kind, so for 24
# applications the pipeline's only question was "did Gradle exit 0" — and an
# application with zero testers produced the same green tick as one with fifty
# passing ones. A gap nobody can see is a gap nobody schedules.
#
# DERIVED, not written down. A hand-maintained inventory is a list that is true
# on the day it is written and silently wrong a week later — this repository has
# had three of those (a workflow watching nothing real, a dist artefact with no
# source, a tester counting participants). Everything countable is counted from
# the tree here; the only authored half is the one that cannot be derived,
# "what would the cheapest meaningful first test be", which lives in
# 1_cicd/src/data/test-coverage.json.
#
# REFUSES if an application has no note, or a note names something that is no
# longer here — so adding a ship workflow forces an answer about its coverage,
# and deleting one cannot leave a stale entry behind. `generated files are up to
# date` runs this on every push, so the counts cannot drift.
log_step "test-coverage inventory"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYCOV' || exit 1
import glob, json, os, re, sys

root = sys.argv[1]
notes_path = os.path.join(root, "1_cicd/src/data/test-coverage.json")
notes = json.load(open(notes_path))["apps"]
apps, bad, seen = {}, [], set()

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/ship-*.yml"))):
    name = os.path.basename(wf)
    text = open(wf).read()
    m = re.search(r"^  WORK_DIR:\s*(\S+)\s*$", text, re.M)
    key = m.group(1) if m else name
    seen.add(key)
    if key not in notes:
        bad.append("%s: no entry in 1_cicd/src/data/test-coverage.json for %r --"
                   " every ship workflow owes an answer about what it does not test" % (name, key))
        continue

    d = os.path.join(root, key)
    bj = os.path.join(d, "build.json")
    cfg = json.load(open(bj)) if os.path.isfile(bj) else {}
    tests = cfg.get("tests") or {}
    tdir = os.path.join(d, (tests.get("shell") or {}).get("dir") or "test")

    units = 0
    for dirpath, _, files in os.walk(d):
        if os.sep + "src" + os.sep + "test" + os.sep in dirpath + os.sep:
            units += sum(1 for f in files if f.endswith((".kt", ".java")))

    unit = tests.get("unit") or {}
    apps[key] = {
        "workflow": name,
        "shell_testers": len(glob.glob(os.path.join(tdir, "test-*.sh"))),
        "unit_test_sources": units,
        "unit_task_declared": bool(unit.get("task")) and unit.get("enabled") is not False,
        "ship_runs_testers": "cloud-android-test-engine.sh" in text,
        "ship_has_published_nothing_guard":
            "cloud-android-publish-guard.sh" in text and "id: publish" in text,
        "first_test": notes[key],
    }

for k in notes:
    if k not in seen:
        bad.append("1_cicd/src/data/test-coverage.json: %r has no ship workflow --"
                   " delete the entry rather than leaving a note about nothing" % k)

covered = sum(1 for a in apps.values() if a["shell_testers"] or a["unit_task_declared"])
out = {
    "_doc": "GENERATED by cloud-android-ship-repo-workflow-engine.sh from the tree"
            " plus 1_cicd/src/data/test-coverage.json. Do not edit.",
    "applications": len(apps),
    "with_executed_tests": covered,
    "with_no_tests_at_all": len(apps) - covered,
    "apps": apps,
}
dest = os.path.join(root, "1_cicd/dist/data/test-coverage.json")
os.makedirs(os.path.dirname(dest), exist_ok=True)
open(dest, "w").write(json.dumps(out, indent=2, sort_keys=True) + "\n")
print("  %d applications, %d with tests, %d with none"
      % (len(apps), covered, len(apps) - covered))

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYCOV

# ── cicd: copy YAMLs → dist/cicd then into .github/workflows ───────
log_step "dist/cicd → .github/workflows"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/workflows"
for f in "$CICD_SRC/cicd/"*.yml; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$CICD_DIST/cicd/$base"
    cp "$f" "$CLOUD_ANDROID_ROOT/.github/workflows/$base"
done

# ── name an orphaned workflow; do not delete it ────────────────────
#
# The prune above deliberately stops at dist/, because "a workflow can
# legitimately live in .github/workflows without a source in this tier and
# deleting a live one is not a generator's call". That stays true. What was
# missing is that the same policy makes a RENAME silent: renaming
# ship-cloud-sheets.yml to ship-cloud-office.yml pruned the dist twin and left
# .github/workflows/ship-cloud-sheets.yml behind, still triggering on
# `ac_cloud-sheets/**` — a path that no longer exists. GitHub keeps running it,
# it can never go green again, and nothing in this generator's output mentioned
# it. The stale file is a permanent red for whoever pushes next, who did not
# rename anything.
#
# So: REPORT, never remove. A source-less workflow that is intentional (
# enhance-silence-guard.yml, ship-superapp-data-regen.yml) is listed once per
# run and ignored; one that is residue from a rename is listed the same way and
# is then obvious. Deleting it is still a human's `git rm`, deliberately.
for _wf in "$CLOUD_ANDROID_ROOT/.github/workflows/"*.yml; do
    [ -e "$_wf" ] || continue
    _b=$(basename "$_wf")
    [ -e "$CICD_SRC/cicd/$_b" ] || \
        echo "  NO SOURCE: .github/workflows/$_b has no 1_cicd/src/cicd/$_b — intentional, or residue from a rename that needs 'git rm'"
done

# scripts folder for GHA (symlink inside .github/workflows)
[ -e "$CLOUD_ANDROID_ROOT/.github/workflows/scripts" ] || \
    ln -sf ../../1_cicd/dist/scripts "$CLOUD_ANDROID_ROOT/.github/workflows/scripts"

# ── actions: composite actions ─────────────────────────────────────
log_step "dist/actions → .github/actions"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/actions"
if [ -d "$CICD_SRC/actions" ]; then
    cp -r "$CICD_SRC/actions/"* "$CICD_DIST/actions/" 2>/dev/null || true
    cp -r "$CICD_SRC/actions/"* "$CLOUD_ANDROID_ROOT/.github/actions/" 2>/dev/null || true
fi

# ── hooks: copied + chmodded ───────────────────────────────────────
log_step "dist/hooks"
for f in "$GIT_SRC/hooks/"*; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$GIT_DIST/hooks/$base"
    chmod +x "$GIT_DIST/hooks/$base"
done

# ── root dotfiles: gitmodules / gitignore / gitattributes / LICENSE ─
# git reads these three out of the WORKING TREE, so each is written to the git
# tier's dist and then copied to the repo root. gitconfig below is the one
# exception — it is included from .git/config instead, and a .gitconfig at a
# repo root would mean nothing to git at all.
log_step "0_git/dist & repo-root dotfiles"
for _f in gitmodules gitignore gitattributes; do
    [ -f "$GIT_SRC/$_f" ] || continue
    cp "$GIT_SRC/$_f" "$GIT_DIST/.$_f"
    cp "$GIT_SRC/$_f" "$CLOUD_ANDROID_ROOT/.$_f"
done
# gitmodules also keeps its legacy dist/modules/ copy: the ship workflows read
# it from there.
[ -f "$GIT_SRC/gitmodules" ] && cp "$GIT_SRC/gitmodules" "$GIT_DIST/modules/gitmodules"
# LICENSE is copied VERBATIM — GitHub's licence detector and SPDX scanners
# match on the text, and a generated-file banner breaks them.
if [ -f "$GIT_SRC/LICENSE" ]; then
    cp "$GIT_SRC/LICENSE" "$GIT_DIST/LICENSE"
    cp "$GIT_SRC/LICENSE" "$CLOUD_ANDROID_ROOT/LICENSE"
fi
unset _f
if [ -f "$GIT_SRC/gitconfig" ]; then
    cp "$GIT_SRC/gitconfig" "$GIT_DIST/gitconfig"
    # Deploy via .git/config [include] + reconcile shadow keys.
    _gc_section=""
    while IFS= read -r line; do
        case "$line" in
            \[*\])
                _gc_section=$(printf '%s' "$line" | sed 's/^\[\([^]]*\)\]$/\1/' | tr '[:upper:]' '[:lower:]')
                ;;
            *=*)
                [ -z "$_gc_section" ] && continue
                _gc_key=$(printf '%s' "$line" | sed -n 's/^[[:space:]]*\([a-zA-Z][a-zA-Z0-9]*\)[[:space:]]*=.*/\1/p' | tr '[:upper:]' '[:lower:]')
                [ -n "$_gc_key" ] && git -C "$CLOUD_ANDROID_ROOT" config --local --unset "${_gc_section}.${_gc_key}" 2>/dev/null || true
                ;;
        esac
    done < "$GIT_DIST/gitconfig"
    unset _gc_section _gc_key
    git -C "$CLOUD_ANDROID_ROOT" config --local include.path ../0_git/dist/gitconfig 2>/dev/null || true
fi

log_info "Workflow build complete. Tier dists under 0_git/, 0_apps/, 1_cicd/"

# ── dotfiles ────────────────────────────────────────────────────────────────
# src/apps/<tool>/ → dist/dotfiles/<tool>/ → <repo>/<target>/, plus
# root_targets for single files at the repo root (.mcp.json). Same module every
# repo under cloud carries.
if [ -d "$APPS_SRC" ]; then
    sh "$LIB_SRC/deploy-dotfiles.sh" "$APPS_SRC" "$APPS_DIST/dotfiles" "$CLOUD_ANDROID_ROOT"
fi
