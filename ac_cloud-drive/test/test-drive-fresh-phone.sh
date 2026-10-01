#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #730 — a FRESH phone: no mesh, no prior state, cloud-drive + a terminal  ║
# ║ just installed. The store must seed and the terminal must mount IT.      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. On a freshly set-up phone ~/cloud-drive-shared-store was an
# empty directory in the terminal while an older phone held every repository.
# Read from the code, three independent defects produce exactly that:
#
#   * cloud-termux bound the store at the HOST path $HOME/cloud-drive-shared-store
#     while $HOME itself is bound as /root, so the guest's ~ never saw the bind —
#     only the empty mkdir made just before it;
#   * cloud-termux targets SDK 28 but asked only for All-Files-Access, which the
#     platform ignores below target 30: the legacy READ/WRITE grant was never
#     requested, so a fresh install could not read shared storage at all;
#   * cloud-drive's seed is scheduled by the same onCreate that sends the user to
#     the all-files toggle, so its first pass ran WITHOUT the grant, every clone
#     failed as a bare I/O error and the next attempt waited out a back-off.
#
# Each check is a function of its input files, so the MUT block below runs the
# SAME check on a copy with the defect planted back and requires it to go RED —
# after first proving the planted copy really differs from the original.
#
#   F1  the seed is scheduled on EVERY launch (first one included), and re-kicked
#       the moment the all-files grant arrives.
#   F2  without all-files access the seed attempts nothing, reports every declared
#       repository as needs-storage, and retries — before the migration touches
#       the store.
#   F3  every PUBLIC cloud*/front* repository the manifest declares is seeded.
#   F4  the seed's network constraint is declared in build.json, not hardcoded.
#   F5  both terminals LINK ~/cloud-drive-shared-store to THE store —
#       <shared storage>/<shared_root> from cloud-drive's build.json — and the link
#       resolves inside cloud-termux's root (#736).
#   F6  without storage access neither terminal leaves a link or an empty mount
#       point that reads as an empty store.
#   F7  cloud-termux asks for the grant its TARGET sdk needs.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
MAIN="$SRC/MainActivity.kt"
SEED="$SRC/StoreSeed.kt"
MANIFEST="$APP/data/drive-git-repos.json"
BUILD_JSON="$APP/build.json"
GRADLE="$APP/app/build.gradle"
ENTER="$ROOT/ac_cloud-termux/rootfs/enter.sh"
TACT="$ROOT/ac_cloud-termux/app/src/main/java/com/termux/app/TermuxActivity.java"
TMAN="$ROOT/ac_cloud-termux/app/src/main/AndroidManifest.xml"
TPROPS="$ROOT/ac_cloud-termux/gradle.properties"
BAKE="$ROOT/ac_cloud-nix-on-droid/app/src/main/cpp/bake_default_packages.py"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$MAIN" "$SEED" "$MANIFEST" "$BUILD_JSON" "$GRADLE" "$ENTER" "$TACT" "$TMAN" "$TPROPS" "$BAKE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
SHARED_ROOT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["storage"]["shared_root"])' "$BUILD_JSON")"
[ -n "$SHARED_ROOT" ] || { echo "ERROR build.json declares no storage.shared_root"; exit 1; }

# f1 <MainActivity.kt>
f1() {
    python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'override fun onCreate\(.*?\n    \}\n', s, re.S)
body = m.group(0) if m else ''
bad = []
# Top-level of onCreate (8 spaces), never under a condition: the first launch must schedule.
if not re.search(r'^        StoreSeedWorker\.schedule\(this\)$', body, re.M):
    bad.append('onCreate does not schedule the seed unconditionally')
r = re.search(r'override fun onResume\(\).*?\n    \}\n', s, re.S)
if not r or not re.search(r'if \(has && !hadStorageAccess\) StoreSeedWorker\.kick\(this\)', r.group(0)):
    bad.append('onResume does not re-kick the seed when the all-files grant arrives')
print('\n'.join('    ' + b for b in bad)); sys.exit(1 if bad else 0)
PY
}

# f2 <StoreSeed.kt>
f2() {
    python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'override fun doWork\(\).*?\n    \}\n', s, re.S)
body = m.group(0) if m else ''
gate = body.find('SeedReport.withoutStorage(Declarations.seedRepos.map { it.name }, Places.hasAllFilesAccess(applicationContext))')
mig = body.find('StoreMigration.migrate(')
bad = []
if gate < 0: bad.append('doWork has no all-files gate')
elif mig >= 0 and gate > mig: bad.append('the gate runs AFTER the migration has already touched the store')
else:
    tail = body[gate:gate + 600]
    if 'return Result.retry()' not in tail: bad.append('a pass without storage does not retry')
    if 'reportFile(applicationContext).writeText(blocked.text())' not in tail: bad.append('a pass without storage is not persisted for the Git page')
print('\n'.join('    ' + b for b in bad)); sys.exit(1 if bad else 0)
PY
}

# f3 <drive-git-repos.json> <StoreSeed.kt dir's Declarations.kt>
f3() {
    python3 - "$1" "$SRC/Declarations.kt" <<'PY'
import json, re, sys
repos = json.load(open(sys.argv[1]))['repos']
decl = open(sys.argv[2]).read()
bad = []
if not re.search(r'val seedRepos: List<GitRepoDecl> get\(\) = gitFamily\.repos\.filter \{ it\.seed && !it\.private \}', decl):
    bad.append('Declarations.seedRepos is no longer "declared seed and not private"')
pub = [r['name'] for r in repos if r.get('public') and not r.get('private') and re.match(r'(cloud|front)', r['name'])]
if not pub: bad.append('the manifest declares no public cloud*/front* repository at all')
miss = [n for n in pub if not next(r for r in repos if r['name'] == n).get('seed')]
if miss: bad.append('public repositories the seed never attempts: ' + ', '.join(miss))
print('\n'.join('    ' + b for b in bad)); sys.exit(1 if bad else 0)
PY
}

# f4 <StoreSeed.kt> <build.json> <build.gradle>
f4() {
    local bad=0
    python3 -c 'import json,sys; v=json.load(open(sys.argv[1]))["storage"]["seed"].get("require_unmetered_network"); sys.exit(0 if isinstance(v,bool) else 1)' "$2" \
        || { echo "    build.json::storage.seed.require_unmetered_network is not declared"; bad=1; }
    grep -q 'buildConfigField "boolean", "SEED_REQUIRE_UNMETERED"' "$3" || { echo "    gradle does not bake SEED_REQUIRE_UNMETERED"; bad=1; }
    grep -q 'if (BuildConfig.SEED_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED' "$1" \
        || { echo "    the seed's network type is not read from the declaration"; bad=1; }
    grep -q 'setRequiredNetworkType(NetworkType.UNMETERED)' "$1" && { echo "    the seed still hardcodes UNMETERED"; bad=1; }
    return $bad
}

# f5 <enter.sh> <bake_default_packages.py>
# #736: both terminals LINK ~/cloud-drive-shared-store to THE store. Binds were what a
# phone never saw (an older cloud-termux bound at host paths, leaving empty mkdir'd
# mount points), and a link is what the terminal's ~ resolves to without one.
f5() {
    local bad=0
    grep -q -- '-b "$HOME:/root"' "$1" || { echo "    enter.sh no longer binds \$HOME as /root — re-derive the guest path"; bad=1; }
    grep -qF "storage_link \"\$HOME/cloud-drive-shared-store\" /storage/emulated/0/$SHARED_ROOT" "$1" \
        || { echo "    cloud-termux does not link ~/cloud-drive-shared-store to /storage/emulated/0/$SHARED_ROOT"; bad=1; }
    # $HOME is /root inside the root: an absolute /storage/... link target resolves there
    # only because /storage/emulated/0 is bound at the SAME guest path.
    grep -qxF '    binds="$binds -b /storage/emulated/0"' "$1" \
        || { echo "    cloud-termux does not bind /storage/emulated/0 at its own path in the guest — the links dangle inside the root"; bad=1; }
    grep -q -- '-b /storage/emulated/0[^"]*:\(\$HOME\|/root\)/' "$1" && { echo "    cloud-termux still binds into ~ instead of linking"; bad=1; }
    grep -qF 'store = f"/storage/emulated/0/{shared_root_name}"' "$2" \
        && grep -qF 'cloud_storage_link "$HOME/cloud-drive-shared-store" "{store}"' "$2" \
        || { echo "    cloud-nix does not link ~/cloud-drive-shared-store to the store"; bad=1; }
    return $bad
}

# f6 <enter.sh> <bake_default_packages.py>
f6() {
    python3 - "$1" "$2" <<'PY2'
import re, sys
enter, bake = open(sys.argv[1]).read(), open(sys.argv[2]).read()
bad = []
# Termux: the links are made only inside the readable branch; the other branch removes them.
m = re.search(r'^if ls /storage/emulated/0 >/dev/null 2>&1; then\n(.*?)^else\n(.*?)^fi\n', enter, re.S | re.M)
if not m: bad.append('enter.sh has no readable/unreadable branch')
else:
    if re.search(r'^\s*(storage_link|mkdir -p) "\$HOME/(emulated|cloud-drive-shared-store)', enter[:m.start()], re.M):
        bad.append('enter.sh makes ~/emulated or ~/cloud-drive-shared-store before knowing storage is readable')
    for d in ('emulated', 'cloud-drive-shared-store'):
        if f'storage_link "$HOME/{d}"' not in m.group(1): bad.append(f'enter.sh does not link ~/{d} when storage is readable')
        if f'storage_unlink "$HOME/{d}"' not in m.group(2): bad.append(f'cloud-termux leaves ~/{d} behind when storage is not granted')
b = re.search(r"^def storage_setup\(.*?^    \)\n", bake, re.S | re.M)
if not b: bad.append('the nix bin/login storage block (storage_setup) is gone')
else:
    blk = b.group(0)
    iff, els = blk.find("'if CLOUD_LS_ERR="), blk.find("'else\\n'\n        '  cloud_trace \"storage: ls")
    for d in ('emulated', 'cloud-drive-shared-store'):
        mk = blk.find(f'cloud_storage_link "$HOME/{d}"')
        if mk < 0 or iff < 0 or mk < iff: bad.append(f'cloud-nix links ~/{d} before knowing storage is readable')
        if els < 0 or f'cloud_storage_unlink "$HOME/{d}"' not in blk[els:]: bad.append(f'cloud-nix leaves ~/{d} behind when storage is not granted')
    if 'storage_setup(shared_root_name)' not in bake: bad.append('bin/login is not given storage_setup')
print('\n'.join('    ' + x for x in bad)); sys.exit(1 if bad else 0)
PY2
}

# f7 <TermuxActivity.java> <AndroidManifest.xml> <gradle.properties>
f7() {
    local bad=0 target
    target="$(sed -n 's/^targetSdkVersion=//p' "$3")"
    if [ "${target:-0}" -lt 30 ]; then
        grep -q 'getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.R' "$1" || { echo "    TermuxActivity does not branch on its TARGET sdk ($target)"; bad=1; }
        grep -q 'String\[\] legacy = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}' "$1" \
            || { echo "    TermuxActivity does not ask for the legacy READ+WRITE grant target $target needs"; bad=1; }
        grep -q 'requestPermissions(legacy, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION)' "$1" || { echo "    the legacy grant is never requested"; bad=1; }
        grep -q 'android.permission.READ_EXTERNAL_STORAGE' "$2" || { echo "    the manifest does not declare READ_EXTERNAL_STORAGE, so it cannot be granted"; bad=1; }
    fi
    return $bad
}

echo "── #730 a fresh phone: the store seeds and the terminal mounts it ──"
run() { local id="$1" what="$2"; shift 2; local out; if out="$("$@" 2>&1)"; then pass "$id $what"; else fail "$id $what"; [ -n "$out" ] && echo "$out"; fi; }
run F1 "the seed is scheduled on first launch and re-kicked on the all-files grant" f1 "$MAIN"
run F2 "without all-files access the seed reports needs-storage for every repository and retries" f2 "$SEED"
run F3 "every public cloud*/front* repository in the manifest is seeded" f3 "$MANIFEST"
run F4 "the seed's network constraint is declared" f4 "$SEED" "$BUILD_JSON" "$GRADLE"
run F5 "both terminals link ~/cloud-drive-shared-store to /storage/emulated/0/$SHARED_ROOT, resolvable in the guest" f5 "$ENTER" "$BAKE"
run F6 "no link or empty mount point is left when storage is not granted" f6 "$ENTER" "$BAKE"
run F7 "cloud-termux asks for the grant its target sdk needs" f7 "$TACT" "$TMAN" "$TPROPS"

echo "── MUT: each check goes RED on its defect planted back ──"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
# mut <id> <src> <python-expr transforming s> <check> [extra args, MUTATED stands for the copy]
mut() {
    local id="$1" src="$2" expr="$3" check="$4"; shift 4
    local m="$T/$id.$(basename "$src")"
    python3 -c "import sys; s=open(sys.argv[1]).read(); s2=$expr; open(sys.argv[2],'w').write(s2)" "$src" "$m"
    if cmp -s "$src" "$m"; then fail "MUT $id: the mutation did not apply — the check is unproven"; return; fi
    local args=() a; for a in "$@"; do [ "$a" = MUTATED ] && args+=("$m") || args+=("$a"); done
    if "$check" "${args[@]}" >/dev/null 2>&1; then fail "MUT $id: still GREEN with the defect planted"; else pass "MUT $id goes RED"; fi
}
mut F1a "$MAIN" "s.replace('        StoreSeedWorker.schedule(this)\n', '        if (hadStorageAccess) StoreSeedWorker.schedule(this)\n')" f1 MUTATED
mut F1b "$MAIN" "s.replace('StoreSeedWorker.kick(this)', 'Unit')" f1 MUTATED
mut F2a "$SEED" "s.replace('return Result.retry()\n        }\n        val family', 'return Result.success()\n        }\n        val family')" f2 MUTATED
mut F2b "$SEED" "s.replace('SeedReport.withoutStorage(', 'SeedReport.withoutStorageX(')" f2 MUTATED
# F3 un-seeds one public repository through the JSON itself (a duplicate key would lose to the original).
mut F3 "$MANIFEST" "(lambda j, d: ([r.update(seed=False) for r in d['repos'] if r['name'] == 'front-data'], j.dumps(d, indent=1))[1])(__import__('json'), __import__('json').loads(s))" f3 MUTATED
mut F4 "$SEED" "s.replace('if (BuildConfig.SEED_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED', 'NetworkType.UNMETERED')" f4 MUTATED "$BUILD_JSON" "$GRADLE"
mut F5a "$ENTER" "s.replace('    binds=\"\$binds -b /storage/emulated/0\"\n', '')" f5 MUTATED "$BAKE"
mut F5b "$BAKE" "s.replace('cloud_storage_link \"\$HOME/cloud-drive-shared-store\" \"{store}\"', 'cloud_storage_link \"\$HOME/cloud-drive-shared-store\" \"{store}.old\"')" f5 "$ENTER" MUTATED
mut F6a "$ENTER" "s.replace('    storage_unlink \"\$HOME/cloud-drive-shared-store\"\n', '')" f6 MUTATED "$BAKE"
mut F6b "$BAKE" "s.replace(\"'  cloud_storage_unlink \\\"\$HOME/cloud-drive-shared-store\\\"\\\\n'\", \"''\")" f6 "$ENTER" MUTATED
mut F7a "$TACT" "s.replace('Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE', 'Manifest.permission.WRITE_EXTERNAL_STORAGE')" f7 MUTATED "$TMAN" "$TPROPS"
mut F7b "$TMAN" "s.replace('<uses-permission android:name=\"android.permission.READ_EXTERNAL_STORAGE\" />', '')" f7 "$TACT" MUTATED "$TPROPS"

echo
if [ "$FAILURES" -eq 0 ]; then echo "PASS — test-drive-fresh-phone"; exit 0; fi
echo "FAIL — test-drive-fresh-phone: $FAILURES check(s)"; exit 1
