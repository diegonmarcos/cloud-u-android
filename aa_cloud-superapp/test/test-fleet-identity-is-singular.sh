#!/usr/bin/env bash
# Tester: ONE package is ONE updatable identity, declared in ONE place, and the
# batch that claims to update everything really enumerates everything.
#
# THE FAILURE THIS EXISTS FOR (#415, and it is the same defect as #406).
#
# The SuperApp is a member of its own fleet: constellation-fleet.json carries
# id=aa_cloud-superapp, package=com.diegonmarcos.superapp, and it is the FIRST
# member of the `apps` group. That one fact produced both halves of #415:
#
#   1. TWO IDENTITY KEYS FOR ONE PACKAGE. UpdateChecker.available() — the
#      self-updater — decides "is this build current" by GIT REVISION
#      (layer.revision == BuildConfig.GIT_SHORT_SHA), because our builds are
#      not byte-reproducible and a rebuild of the same commit has a different
#      APK sha. Fleet.status() decided it by BYTES: releaseStatus() compares
#      the release .sha256 sidecar against the installed APK. Fleet.status's
#      own code-identity guard existed but sat BELOW releaseStatus's early
#      return, and this app publishes a release asset — so for the one entry
#      that guard was written for, it never ran. The fleet row therefore
#      reported UpdateAvailable while the self-updater reported the same
#      package up to date: a second cloud-sa, under the fleet's label
#      ("cloud-superapp", the module slug), demanding an update that
#      installing could never satisfy, on every pass, forever.
#
#   2. THE BATCH KILLED ITSELF AT ENTRY ONE. UpdateWorker.doWork and
#      ConstellationFragment.updateAll each carry the SAME comment: the
#      self-update goes LAST, because installing our own APK replaces it and
#      Android kills this process, taking the rest of the batch with it.
#      Neither could honour that, because the list they hand to the batch
#      CONTAINS this package, first. That is #99 and #274 — "Update all fails
#      for every app and lib", "auto-update ON and yet no app has updated":
#      the pass was not failing, it was being killed before entry two.
#
# A suppression list would have hidden the notification and left the updater
# still demanding an update for an identity that can never match. The fix is at
# the identity layer, in the engine: self is answered by code identity, and self
# is not a batch entry. T4 and T5 are the regression locks for exactly that.
#
# Nothing here touches a device. Every assertion is over declared data or over
# engine source, because that is all this container can honestly read.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → cloud-u-android
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq      >/dev/null || { echo "ERROR: jq required"      >&2; exit 2; }
command -v python3 >/dev/null || { echo "ERROR: python3 required" >&2; exit 2; }

# The updater engine's own directory, read from build.json::modules — the same
# map settings.gradle resolves. A hardcoded path here would be a second
# statement of where the shared module lives, which is the class of bug this
# whole tester is about.
UPDATER_DIR="$(jq -r '.modules["libs:updater"].dir // empty' "$BJ")"
[ -n "$UPDATER_DIR" ] || { echo "ERROR: build.json::modules[libs:updater].dir is not declared" >&2; exit 2; }
FLEET_KT="$APP/$UPDATER_DIR/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"
[ -f "$FLEET_KT" ] || { echo "ERROR: engine not found at $FLEET_KT" >&2; exit 2; }

# ── T1/T2/T3/T6 — the declared data ──────────────────────────────────────────
# One python pass, one compact line per finding: long multi-value output gets
# garbled in transit, and a garbled verdict is a wrong verdict.
DATA_REPORT="$(python3 - "$ROOT" "$APP" <<'PY'
import json, os, sys
root, app = sys.argv[1], sys.argv[2]
out = []
def emit(tid, okflag, msg): out.append(("%s|%s|%s" % (tid, "PASS" if okflag else "FAIL", msg)))

# Every applicationId this repo DECLARES, and the file that declares it.
declared = {}           # applicationId -> [declaring paths]
restated = []           # (path, android.application_id, forks.<k>.app_id)
def claim(aid, where):
    declared.setdefault(aid, []).append(where)

for dirpath, dirnames, filenames in os.walk(root):
    depth = os.path.relpath(dirpath, root).count(os.sep)
    dirnames[:] = [d for d in dirnames
                   if d not in ('z_archive', 'build', 'node_modules', '.git') and depth < 3]
    if 'build.json' not in filenames:
        continue
    p = os.path.join(dirpath, 'build.json')
    rel = os.path.relpath(p, root)
    try:
        b = json.load(open(p))
    except Exception:
        continue
    aid = (b.get('android') or {}).get('application_id')
    if aid:
        claim(aid, rel)
    for k, v in (b.get('forks') or {}).items():
        if k != '_doc' and isinstance(v, dict) and v.get('app_id'):
            # A single-app fork states its package TWICE, for two different
            # engines: android.application_id is what the fleet generator and
            # gradle read, forks.<k>.app_id is what the fork engine's
            # identity-assert compares the built APK against. Both are live
            # consumers, so this is one fact in two fields — and a rename that
            # moves one and not the other is exactly the drift that leaves a
            # published identity nothing on the device can ever match. Recorded
            # as a restatement (T1b checks they agree), not as a collision.
            if aid:
                restated.append((rel, k, aid, v['app_id']))
            else:
                # A thin-hub repo whose only identity IS the fork's.
                claim(v['app_id'], rel + '::forks.' + k)
    # lib-apks mints one applicationId per shipped library module, by the rule
    # in its app/build.gradle: prefix + module name with '-' stripped.
    lib = b.get('lib_apks')
    if lib:
        prefix = lib['application_id_prefix']
        excl = set((lib.get('exclude') or {}).keys())
        for scan in lib.get('scan') or []:
            base = os.path.normpath(os.path.join(dirpath, scan))
            if not os.path.isdir(base):
                continue
            for mod in sorted(os.listdir(base)):
                if mod in excl:
                    continue
                if os.path.isfile(os.path.join(base, mod, 'build.gradle')):
                    claim("%s.%s" % (prefix, mod.replace('-', '')),
                          rel + '::lib_apks[' + mod + ']')

# T1 — no two INDEPENDENT declarations may mint the same package. Two
# installable things claiming one applicationId is one package presenting as
# two updatable apps before the updater has even run.
dupes = {a: w for a, w in declared.items() if len(w) > 1}
emit("T1", not dupes,
     ("every applicationId is minted by exactly one declaration (%d declarations)" % len(declared))
     if not dupes else
     ("%d applicationId(s) minted by more than one declaration: %s"
      % (len(dupes), "; ".join("%s <- %s" % (a, ",".join(w)) for a, w in sorted(dupes.items())))))

# T1b — where one build.json states its package TWICE, the two must agree.
# They feed different engines (gradle + the fleet generator read
# android.application_id; the fork engine's identity-assert reads
# forks.<k>.app_id), so a rename that moves one and not the other publishes
# under an identity nothing installed can ever match — the W3 ML-lib rename
# hazard, in the one place the repo still states a package in two fields.
drift = [(p, k, a, f) for (p, k, a, f) in restated if a != f]
emit("T1b", not drift,
     ("all %d single-app forks state one package in two fields and the two agree" % len(restated))
     if not drift else
     ("%d build.json state(s) disagree with themselves: %s"
      % (len(drift), "; ".join("%s forks.%s app_id=%s but android.application_id=%s"
                               % (p, k, f, a) for (p, k, a, f) in drift))))
if restated:
    print("NOTE|-|%d app(s) state their package in BOTH android.application_id and "
          "forks.<k>.app_id (one fact, two live consumers): %s"
          % (len(restated), ", ".join("%s::forks.%s" % (p, k) for (p, k, _a, _f) in restated)))

fleet = json.load(open(os.path.join(app, 'data', 'constellation-fleet.json')))
rows = fleet['apps']
host_aid = json.load(open(os.path.join(app, 'build.json')))['android']['application_id']

# T2 — the generated fleet may not invent an identity nobody declared.
orphan = sorted({r['package'] for r in rows} - set(declared))
emit("T2", not orphan,
     ("all %d fleet rows name a declared applicationId" % len(rows)) if not orphan else
     ("%d fleet row(s) name an applicationId no build.json declares: %s"
      % (len(orphan), ",".join(orphan))))

# T3 — the host's TWO remote identities must be one. The self-updater reads
# BuildConfig (baked from release.ghcr + release.auto_update); the fleet path
# reads the row. Two sources for one package is how they come to disagree.
hb = json.load(open(os.path.join(app, 'build.json')))['release']
hrow = [r for r in rows if r['package'] == host_aid]
if len(hrow) != 1:
    emit("T3", False, "expected exactly 1 fleet row for the host %s, found %d" % (host_aid, len(hrow)))
else:
    h = hrow[0]
    want = {'registry':  hb['ghcr']['registry'],
            'namespace': hb['ghcr']['namespace'],
            'image':     hb['ghcr']['image'],
            'tag':       (hb.get('auto_update') or {}).get('tag', 'latest')}
    diff = {k: (h.get(k), v) for k, v in want.items() if h.get(k) != v}
    emit("T3", not diff,
         "host fleet row and build.json::release agree on registry/namespace/image/tag"
         if not diff else
         ("host row disagrees with build.json::release on %s"
          % "; ".join("%s row=%s declared=%s" % (k, a, b) for k, (a, b) in sorted(diff.items()))))

# T6 — coverage. Every shipped lib has a row; every cloudlib row is shipped;
# every group member resolves to a row or to a reference-only catalogue entry.
shipped = {a for a, w in declared.items() if any('::lib_apks[' in x for x in w)}
missing = sorted(shipped - {r['package'] for r in rows})
emit("T6a", not missing,
     ("all %d shipped library APKs are enumerated by the fleet" % len(shipped)) if not missing else
     ("%d library APK(s) are built but absent from the fleet, so update-all can never reach them: %s"
      % (len(missing), ",".join(missing))))

ids = {r['id'] for r in rows}
cat = {c['id'] for c in fleet.get('catalogue', [])}
unresolved = []
for g in fleet['groups']:
    for m in g['members']:
        if m not in ids and m not in cat:
            unresolved.append("%s/%s" % (g['id'], m))
emit("T6b", not unresolved,
     ("every member of all %d groups resolves to an installable row or a reference row" % len(fleet['groups']))
     if not unresolved else
     ("%d group member(s) resolve to nothing at all: %s" % (len(unresolved), ",".join(unresolved))))

for line in out:
    print(line)
PY
)"
RC=$?
[ "$RC" -eq 0 ] || { echo "ERROR: the declared-data pass exited $RC" >&2; exit 2; }

echo "== The declared data: one identity, one declaration, full coverage =="
while IFS='|' read -r tid verdict msg; do
    [ -n "$tid" ] || continue
    case "$verdict" in
        PASS) ok  "$tid $msg" ;;
        FAIL) bad "$tid $msg" ;;
        # A standing finding, printed every run so it cannot be lost, and
        # deliberately not a verdict — it names a shape to watch, and T1b is
        # the assertion that actually fails when that shape goes wrong.
        *)    echo "  NOTE: $msg" ;;
    esac
done <<EOF
$DATA_REPORT
EOF

# ── T4/T5 — the engine ───────────────────────────────────────────────────────
# These two are the regression locks for the #415 fix. They read Fleet.kt
# because there is no JVM here and never will be: a static guard is the only
# honest proof this container can offer, and it is strictly better than none.
echo "== The engine: the host is answered once, and is not its own batch entry =="

# T4 — installAllLocked must drop the host from the work-list. Without this the
# batch installs our own APK, Android kills the process, and every entry after
# it is silently never reached.
BATCH_BODY="$(sed -n '/private fun installAllLocked/,/^        Log.i(TAG, "\$mode scanned/p' "$FLEET_KT")"
if printf '%s' "$BATCH_BODY" | grep -E 'if \(app\.pkg == ctx\.packageName\)' >/dev/null; then
    ok "T4 installAllLocked excludes the host package from the batch work-list"
else
    bad "T4 installAllLocked does NOT exclude ctx.packageName — the batch can install the host first, and Android then kills the process before any other entry is reached (#99/#274)"
fi

# T5 — status() must not let the byte-compare path answer for the host, because
# the code-identity guard that makes a non-reproducible rebuild read as "current"
# lives BELOW it and returns too late to be reached.
if grep -E 'if \(app\.pkg != ctx\.packageName\) releaseStatus\(app, installed\)' "$FLEET_KT" >/dev/null; then
    ok "T5 status() routes the host past the byte-compare so its code-identity guard is reachable"
else
    bad "T5 status() lets releaseStatus() answer for the host — the APK-sha compare returns before the revision guard, so a rebuild of the SAME commit reports a phantom update the install can never satisfy (#415)"
fi

# T5b — and the guard it is protecting must still be there. T5 alone would pass
# against an engine that had simply deleted the thing it routes towards.
if grep -E 'layer\.revision == BuildConfig\.GIT_SHORT_SHA' "$FLEET_KT" >/dev/null; then
    ok "T5b the code-identity guard the host is routed to still exists in status()"
else
    bad "T5b status() no longer compares layer.revision to BuildConfig.GIT_SHORT_SHA — the host has no code-identity answer left, so every rebuild is an update"
fi

echo
echo "fleet-identity-is-singular: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
