#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #348 — the proot + glibc runtime is BAKED IN, and baked for THIS app id  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Two defects this app actually shipped, and one that it nearly shipped:
#
#  1. FIRST-RUN DOWNLOAD. TermuxInstaller pre-filled an EDITABLE dialog with
#     https://nix-on-droid.unboiled.info/... and streamed bootstrap-<arch>.zip
#     into a ZipInputStream on first launch. Offline phone -> no shell, ever.
#     A host nobody here controls in the boot path. And zero integrity checking
#     before the extracted files were marked executable.
#
#  2. WRONG PACKAGE ID BAKED INTO THE ROOTFS. bin/login inside the bootstrap is
#     a GENERATED shell script with /data/data/com.termux.nix/files/... written
#     in 22 times (8 more in usr/lib/login-inner). This fork is cld.termux.nix.
#     build.json used to assert the paths "follow applicationId automatically";
#     they do not, and an unpatched rootfs execs a proot-static under a package
#     directory that is not installed -> ENOENT naming ANOTHER APP'S path,
#     which reads like anything except its cause.
#
#  3. The rewrite only works because both ids are the SAME BYTE LENGTH. Nothing
#     enforced that here; a future rename to a longer id would silently produce
#     a corrupt bootstrap.
#
# Everything is read from build.json. This tester hardcodes no url, no sha, no
# id and no arch — change the declaration and the assertions follow it.
#
# Offline by design: it asserts over SOURCE, so it runs in the shell phase
# before anything is built and needs no network and no Android SDK.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_JSON="$DIR/build.json"
INSTALLER="$DIR/app/src/main/java/com/termux/app/TermuxInstaller.java"
GRADLE="$DIR/app/build.gradle"
CONSTANTS="$DIR/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java"

fails=0
ok()   { echo "  ok   — $1"; }
bad()  { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

q() { python3 -c "
import json, sys
node = json.load(open('$BUILD_JSON'))
for key in sys.argv[1].split('.'):
    node = node[key] if isinstance(node, dict) and key in node else None
    if node is None:
        print(''); raise SystemExit(0)
print(node)
" "$1" 2>/dev/null; }

echo "── #348 baked-bootstrap assertions [cloud-terminal-nix] ──"

# A1 — the declaration exists at all.
URL_BASE="$(q forks.nixdroid.bootstrap.url_base)"
RELEASE="$(q forks.nixdroid.bootstrap.release)"
ASSET="$(q forks.nixdroid.bootstrap.asset_name)"
FROM="$(q forks.nixdroid.bootstrap.package_name_rewrite.from)"
TO="$(q forks.nixdroid.bootstrap.package_name_rewrite.to)"
if [ -n "$URL_BASE" ] && [ -n "$RELEASE" ] && [ -n "$ASSET" ] && [ -n "$FROM" ] && [ -n "$TO" ]; then
    ok "build.json declares forks.nixdroid.bootstrap (release $RELEASE, asset $ASSET)"
else
    bad "build.json has no complete forks.nixdroid.bootstrap block — the APK would fall back to a first-run download"
fi

# A2 — every declared arch carries a real sha256 pin. An unpinned arch means
#      the build accepts whatever that third-party host serves that day.
PINS="$(python3 -c "
import json, re
archs = json.load(open('$BUILD_JSON'))['forks']['nixdroid']['bootstrap']['archs']
bad = [a for a, v in archs.items() if not re.fullmatch(r'[0-9a-f]{64}', str(v.get('sha256', '')))]
print('BAD ' + ','.join(bad) if bad else 'OK ' + str(len(archs)))
" 2>/dev/null)"
case "$PINS" in
    OK\ *) ok "all ${PINS#OK } declared ABIs carry a 64-hex sha256 pin" ;;
    *)     bad "archs without a valid sha256 pin: ${PINS#BAD }" ;;
esac

# A3 — the equal-length invariant the whole byte rewrite rests on.
if [ "${#FROM}" -eq "${#TO}" ]; then
    ok "package_name_rewrite is length-preserving ($FROM -> $TO, ${#FROM} bytes)"
else
    bad "package_name_rewrite $FROM (${#FROM} B) -> $TO (${#TO} B) changes length; every offset baked into the bootstrap would shift"
fi

# A4 — the rewrite target is THIS app's real id, from both places that set it.
GRADLE_ID="$(sed -n 's/.*applicationId  *"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
GRADLE_SUFFIX="$(sed -n 's/.*applicationIdSuffix  *"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
CONST_ID="$(sed -n 's/.*TERMUX_PACKAGE_NAME = "\([^"]*\)".*/\1/p' "$CONSTANTS" | head -1)"
if [ "$TO" = "${GRADLE_ID}${GRADLE_SUFFIX}" ] && [ "$TO" = "$CONST_ID" ]; then
    ok "rewrite target $TO matches applicationId+suffix and TermuxConstants"
else
    bad "rewrite target $TO does not match applicationId+suffix (${GRADLE_ID}${GRADLE_SUFFIX}) and/or TermuxConstants ($CONST_ID) — the rootfs would point at a package that is not installed"
fi

# A5 — the UNVERIFIED first-run download is GONE, not merely unused. #628's
#      companion lib is what replaces it, and the difference from defect (1)
#      is the whole point: no editable dialog, no third-party host at runtime,
#      and the bytes are refused unless they hash to the digest the companion's
#      own manifest declares. That gate is asserted (and mutation-proved) in
#      the #628 section below.
if grep -q 'openStream()' "$INSTALLER" || grep -q 'setEditable\|EditText' "$INSTALLER"; then
    bad "TermuxInstaller still streams the bootstrap from a user-editable url — defect (1) is back"
else
    ok "TermuxInstaller has no user-editable, unverified bootstrap download"
fi
# grep -q writes nothing, so piping it into a filter tests the filter's view of
# an empty stream and is green whatever the file says. Filter FIRST, then match.
if grep -vE '^[[:space:]]*(\*|//|/\*)' "$INSTALLER" | grep -qE 'https?://[^"]*bootstrap'; then
    bad "TermuxInstaller still carries a live bootstrap url outside a comment"
else
    ok "no live bootstrap url left in TermuxInstaller"
fi

# A6 — #628: the runtime is not named or read out of THIS app's own assets/
#      at all any more (it never was fetched into them either, under #618).
#      It is read out of the companion lib's assets/, under a name that lib's
#      own manifest declares — asserted in full in the #628 section below.
if grep -q 'BOOTSTRAP_ASSET_NAME' "$INSTALLER" || grep -q 'getAssets().open(BOOTSTRAP_ASSET_NAME)' "$INSTALLER"; then
    bad "TermuxInstaller still names a BOOTSTRAP_ASSET_NAME baked into ITS OWN APK — #628 moved the payload out of this app entirely"
else
    ok "installer does not read the runtime out of its own assets/ (#628: it lives in the companion lib)"
fi

# A7 — something actually PUTS the payload somewhere the companion lib can
#      package it. bakeBootstrap must exist and run before asset merging, same
#      as it always has; what it stages into has moved (asserted in the #628
#      section below), not whether it runs at all.
if grep -q 'task bakeBootstrap' "$GRADLE" \
   && grep -q 'merge.*Assets/.*dependsOn bakeBootstrap' "$GRADLE"; then
    ok "app/build.gradle bakes the rootfs and wires it ahead of asset merging"
else
    bad "app/build.gradle does not define bakeBootstrap and run it before merge*Assets"
fi

# A8 — the rewrite gate the bake step shells out to is reachable.
if [ -r "$DIR/app/src/main/cpp/patch_bootstrap_ids.py" ]; then
    ok "patch_bootstrap_ids.py is reachable from this app"
else
    bad "app/src/main/cpp/patch_bootstrap_ids.py is missing or dangling — the bake step would fail, or worse, bake an unpatched rootfs"
fi


# ── #595 — agent tooling (git, node, claude) baked into the same store ─────
PIN="$(q forks.nixdroid.bootstrap.default_packages.nixpkgs_pin)"
PROFILE_LINK="$(q forks.nixdroid.bootstrap.default_packages.profile_link)"
FALLBACK="$(q forks.nixdroid.bootstrap.default_packages.fallback_init_script)"
ATTRS="$(python3 -c "
import json
attrs = json.load(open('$BUILD_JSON'))['forks']['nixdroid']['bootstrap']['default_packages']['attrs']
print(' '.join(attrs))
" 2>/dev/null)"
if [ -n "$PIN" ] && [ -n "$PROFILE_LINK" ] && [ -n "$FALLBACK" ] && [ -n "$ATTRS" ]; then
    ok "build.json declares default_packages (pin $(echo "$PIN" | cut -c1-12)..., attrs: $ATTRS)"
else
    bad "build.json has no complete forks.nixdroid.bootstrap.default_packages block — a fresh install would ship Nix and nothing else"
fi

# B2 — for every declared attr's binaries, something declares what to expect on PATH.
if python3 -c "
import json, sys
d = json.load(open('$BUILD_JSON'))['forks']['nixdroid']['bootstrap']['default_packages']
attrs, provides, binaries = set(d.get('attrs', [])), d.get('provides', {}), set(d.get('binaries', []))
missing_provides = attrs - set(provides)
provided = {b for bins in provides.values() for b in bins}
sys.exit(0 if not missing_provides and provided <= binaries else 1)
" 2>/dev/null; then
    ok "every declared attr has a provides[] entry, and binaries[] covers all of them"
else
    bad "default_packages.provides/binaries are incomplete for the declared attrs"
fi

# B3 — the bake script this all runs through is reachable.
if [ -r "$DIR/app/src/main/cpp/bake_default_packages.py" ]; then
    ok "bake_default_packages.py is reachable from this app"
else
    bad "app/src/main/cpp/bake_default_packages.py is missing or dangling — bakeBootstrap would fail as soon as default_packages is declared"
fi

# B4 — bakeBootstrap actually wires the tooling bake step in after the id rewrite.
if grep -q 'bake_default_packages.py' "$GRADLE" && grep -q 'tooling.nixpkgs_pin' "$GRADLE"; then
    ok "app/build.gradle chains bake_default_packages.py after patch_bootstrap_ids.py"
else
    bad "app/build.gradle does not invoke bake_default_packages.py with the build.json declaration"
fi

# B5 — the CI workflow that runs bakeBootstrap installs Nix to run it with.
WORKFLOW="$(cd "$DIR/.." && pwd)/.github/workflows/ship-cloud-nix-on-droid.yml"
if [ -r "$WORKFLOW" ] && grep -qi 'nix-installer-action\|install-nix-action' "$WORKFLOW"; then
    ok "ship-cloud-nix-on-droid.yml installs Nix before the gradle build that bakes it in"
else
    bad "ship-cloud-nix-on-droid.yml ($WORKFLOW) does not install Nix — bake_default_packages.py would fail with 'nix: command not found'"
fi

# ── #612 — shared storage and the cloud-drive shared store auto-mount into $HOME ──
DRIVE_BUILD_JSON="$(cd "$DIR/.." && pwd)/ac_cloud-drive/build.json"
SHARED_ROOT="$(python3 -c "
import json
print(json.load(open('$DRIVE_BUILD_JSON'))['storage']['shared_root'])
" 2>/dev/null)"
if [ -n "$SHARED_ROOT" ]; then
    ok "ac_cloud-drive/build.json declares storage.shared_root ($SHARED_ROOT), the value #612 mounts"
else
    bad "ac_cloud-drive/build.json has no storage.shared_root — #612's shared-store mount has nothing to read"
fi

if grep -q 'sharedRootName()' "$GRADLE" && grep -q 'bake_default_packages.py failed' "$GRADLE"; then
    ok "app/build.gradle reads storage.shared_root from ac_cloud-drive/build.json and passes it to bake_default_packages.py"
else
    bad "app/build.gradle does not wire sharedRootName() into the bake_default_packages.py call"
fi

BAKE_PY="$DIR/app/src/main/cpp/bake_default_packages.py"
if grep -q 'BIND_HOME_EMULATED' "$BAKE_PY" && grep -q 'BIND_HOME_SHARED_STORE' "$BAKE_PY" \
   && grep -q '\$HOME/emulated' "$BAKE_PY" && grep -q '\$HOME/cloud-drive-shared-store' "$BAKE_PY"; then
    ok "bake_default_packages.py patches bin/login with both #612 auto-mount binds"
else
    bad "bake_default_packages.py is missing the #612 bin/login mount patch (\$HOME/emulated, \$HOME/cloud-drive-shared-store)"
fi

# ── #612 — the storage grant is asked for, and its absence is legible ──────
# Which grant opens /storage/emulated/0 depends on the TARGET sdk, not the phone's:
# below 30 the app stays on legacy storage and only READ/WRITE_EXTERNAL_STORAGE
# count, while All-Files-Access is ignored. The activity used to gate on
# isExternalStorageManager() and open the All-Files-Access toggle -- at target 28
# a grant that never mounted anything. It must route through PermissionUtils'
# target-sdk-aware chooser instead.
ACTIVITY="$DIR/app/src/main/java/com/termux/app/TermuxActivity.java"
TARGET_SDK="$(sed -n 's/^targetSdkVersion=//p' "$DIR/gradle.properties")"
storage_request_fits_target() {  # $1 = activity source
    body="$(awk '/private void requestManageStorageIfNeeded\(\)/,/^    }/' "$1")"
    [ -n "$body" ] || return 1
    echo "$body" | grep -q 'checkAndRequestLegacyOrManageExternalStoragePermission' || return 1
    if [ "${TARGET_SDK:-0}" -lt 30 ]; then
        ! echo "$body" | grep -q 'isExternalStorageManager\|requestManageStorageExternalPermission'
    fi
}
if storage_request_fits_target "$ACTIVITY"; then
    ok "TermuxActivity requests storage through the target-sdk-aware chooser (targetSdk $TARGET_SDK -> legacy READ/WRITE_EXTERNAL_STORAGE)"
else
    bad "TermuxActivity's storage request does not fit targetSdk $TARGET_SDK — at < 30 All-Files-Access never mounts the shared store (#612)"
fi
# MUTATION PROOF: the shipped pre-fix body must fail the pin.
MUT_ACT="$(mktemp)"
awk '/private void requestManageStorageIfNeeded\(\)/{print; print "        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())"; print "            return;"; print "        PermissionUtils.requestManageStorageExternalPermission(this,"; print "            PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION);"; skip=1; next} skip && /^    }/{skip=0} !skip' "$ACTIVITY" > "$MUT_ACT"
if cmp -s "$ACTIVITY" "$MUT_ACT"; then
    bad "MUTATION DID NOT APPLY: the pre-fix storage request could not be planted"
elif storage_request_fits_target "$MUT_ACT"; then
    bad "MUTATION SURVIVED: the All-Files-Access-only request still passes the target-sdk pin"
else
    ok "mutation proved: the All-Files-Access-only request fails the target-sdk pin"
fi
rm -f "$MUT_ACT"
# #676 the pin targets the ECHOED stderr line itself, not the phrase: the file's
# own comments also say All-Files-Access, so a grep for the phrase alone stays
# green after the echo is deleted (a hollow green). The probe may degrade to no
# bind — that mechanism stands — but its one loud line must exist.
notice_echoed() { grep -q 'echo .*shared store not mounted.*storage access.*>&2' "$1"; }
if notice_echoed "$BAKE_PY"; then
    ok "bin/login echoes the shared-store-not-mounted notice to stderr"
else
    bad "bin/login binds an unreadable shared store silently — no storage-access notice reaches stderr (#612)"
fi
# MUTATION PROOF: strip the echo line from a copy; the assertion must go red there.
MUT612="$(mktemp)"
grep -v 'shared store not mounted' "$BAKE_PY" > "$MUT612"
if notice_echoed "$MUT612"; then
    bad "MUTATION SURVIVED: deleting the not-mounted echo left the notice pin green — it proves nothing (#676)"
else
    ok "mutation proved: deleting the not-mounted echo turns the notice pin red"
fi
rm -f "$MUT612"

# ── #715 — the legacy request covers EVERY grant the legacy check demands ──
# checkStoragePermission(legacy) requires READ and WRITE_EXTERNAL_STORAGE, but
# requestLegacyStorageExternalPermission asked for WRITE alone. READ was never
# requested, so the check could never pass and /storage/emulated/0 never
# mounted, however often the user said yes. Derived from both bodies, so the
# pin follows the check if the check ever changes.
PERM_UTILS="$DIR/termux-shared/src/main/java/com/termux/shared/android/PermissionUtils.java"
perms_in() {  # $1 = source, $2 = method name -> the Manifest permissions its body names
    awk -v m="$2" '$0 ~ "public static boolean " m "\\(" {on=1} on {print} on && /^    }/ {exit}' "$1" \
        | grep -o 'Manifest\.permission\.[A-Z_]*' | sort -u
}
legacy_request_covers_check() {  # $1 = PermissionUtils source
    need="$(perms_in "$1" checkStoragePermission)"
    have="$(perms_in "$1" requestLegacyStorageExternalPermission)"
    [ -n "$need" ] && [ -z "$(printf '%s\n' "$need" | grep -vxF "$have")" ]
}
if legacy_request_covers_check "$PERM_UTILS"; then
    ok "the legacy storage request asks for every permission the legacy check demands ($(perms_in "$PERM_UTILS" checkStoragePermission | sed 's/.*\.//' | tr '\n' ' '))"
else
    bad "the legacy storage request omits a permission checkStoragePermission demands — the grant can never pass the check, storage never mounts"
fi
MUT_PU="$(mktemp)"
awk '/public static boolean requestLegacyStorageExternalPermission\(/ {on=1} on && /return requestPermissions\(context,/ {print "        return requestPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, requestCode);"; skip=1; next} skip && /requestCode\);/ {skip=0; on=0; next} !skip' "$PERM_UTILS" > "$MUT_PU"
if cmp -s "$PERM_UTILS" "$MUT_PU"; then
    bad "MUTATION DID NOT APPLY: the WRITE-only request could not be planted"
elif legacy_request_covers_check "$MUT_PU"; then
    bad "MUTATION SURVIVED: the shipped WRITE-only request still passes the coverage pin"
else
    ok "mutation proved: the shipped WRITE-only request fails the coverage pin"
fi
rm -f "$MUT_PU"

# ── #715 — bin/login's half of the trace runs, and names the storage error ──
# Executed, not grepped: the Android-side prelude (POSIX sh; on the phone it is
# mksh) must start the log fresh for an interactive login, write its job-control
# line, and stay off the screen for a caller's command.
T715="$(mktemp -d)"
python3 - "$BAKE_PY" "$T715" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("bake", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
body = m.BIN_LOGIN_TRACE + 'cloud_trace "after"\n'
open(sys.argv[2] + "/login.sh", "w").write("set -eu\n" + body)
open(sys.argv[2] + "/login-nogate.sh", "w").write(
    "set -eu\n" + body.replace('if [ -n "$CLOUD_LOGIN_SCREEN" ]; then echo "$m" >&2; fi', 'echo "$m" >&2'))
PY
mkdir -p "$T715/home"
echo stale > "$T715/home/.cloud-login.log"
HOME="$T715/home" sh "$T715/login.sh" 2>"$T715/i.err"
I_LOG="$(cat "$T715/home/.cloud-login.log")"
HOME="$T715/home" sh "$T715/login.sh" some-command 2>"$T715/a.err"
if ! grep -q stale "$T715/home/.cloud-login.log" \
   && echo "$I_LOG" | grep -q 'bin/login: start, pid [0-9]* state=. pgrp=[0-9]* sid=[0-9]* tpgid=-\{0,1\}[0-9]*' \
   && grep -q 'after' "$T715/i.err" && [ ! -s "$T715/a.err" ]; then
    ok "bin/login's trace runs under POSIX sh: a fresh log per interactive login, its pgrp/sid/tpgid line, screen lines only when interactive"
else
    bad "bin/login's trace prelude misbehaves: log=$(echo "$I_LOG" | head -2 | tr '\n' '|') screen=$(head -c 200 "$T715/i.err") argv-stderr=$(head -c 200 "$T715/a.err")"
fi
HOME="$T715/home" sh "$T715/login-nogate.sh" some-command 2>"$T715/m.err"
if [ -s "$T715/m.err" ]; then
    ok "mutation proved: without the interactive gate a caller's command gets trace lines on its stderr"
else
    bad "MUTATION SURVIVED: the ungated trace still kept a caller's stderr clean"
fi
rm -rf "$T715"
if grep -q 'cloud_trace "storage: ls /storage/emulated/0 failed: \$CLOUD_LS_ERR"' "$BAKE_PY"; then
    ok "a failed storage probe traces ls's own error, not only a verdict"
else
    bad "bin/login's storage probe discards ls's error — the device log could not say WHY storage is unreadable"
fi

# ── #605 — bin/login's proot-static exec must resolve to THIS app's own
#           prefix, never the different (and not-installed) com.termux.nix
#           app whose path was baked into the upstream bootstrap zip. Runs the
#           REAL patch_bootstrap_ids.py against a synthetic fixture shaped
#           exactly like the real bin/login (same "exec .../proot-static \"
#           literal), entirely offline -- no network, no real bootstrap zip.
C1_WORKDIR="$(mktemp -d)"
trap 'rm -rf "$C1_WORKDIR"' EXIT
C1_RESULT="$(python3 -c "
import sys, zipfile
sys.path.insert(0, '$DIR/app/src/main/cpp')

frm, to = '$FROM', '$TO'
work = '$C1_WORKDIR'
src = work + '/fixture.zip'
out = work + '/patched.zip'

login = (
    'export HOME=\"/data/data/' + frm + '/files/home\"\n'
    'exec /data/data/' + frm + '/files/usr/bin/proot-static \\\\\n'
    '  -b /data/data/' + frm + '/files/usr/nix:/nix \\\\\n'
    '  /data/data/' + frm + '/files/usr/bin/sh /data/data/' + frm + '/files/usr/usr/lib/login-inner \"\$@\"\n'
)
with zipfile.ZipFile(src, 'w') as z:
    z.writestr('bin/login', login)
    z.writestr('usr/lib/login-inner', 'placeholder, unused by this fixture\n')
    z.writestr('SYMLINKS.txt', '')
    z.writestr('EXECUTABLES.txt', 'bin/login\n')

import patch_bootstrap_ids
sys.argv = ['patch_bootstrap_ids.py', src, out, frm + '=' + to]
try:
    rc = patch_bootstrap_ids.main()
except SystemExit as e:
    rc = e.code

with zipfile.ZipFile(out) as z:
    patched_login = z.read('bin/login').decode()

expected_exec = 'exec /data/data/' + to + '/files/usr/bin/proot-static \\\\'
ok = (rc == 0
      and frm not in patched_login
      and expected_exec in patched_login)
print('RESULT:' + ('OK' if ok else 'FAIL rc=' + str(rc) + ' exec_present=' + str(expected_exec in patched_login) + ' old_id_gone=' + str(frm not in patched_login)))
" 2>/dev/null | sed -n 's/^RESULT://p')"
case "$C1_RESULT" in
    OK) ok "patch_bootstrap_ids.py rewrites bin/login's proot-static exec to $TO (never leaves $FROM behind)" ;;
    *)  bad "patch_bootstrap_ids.py did not produce a clean $TO proot-static exec: $C1_RESULT" ;;
esac

# ── #605/#628 — an updated lib does not fix an already-extracted rootfs
#           unless something re-extracts it: TermuxInstaller must gate its
#           "prefix already exists, do nothing" shortcut on comparing the
#           declared version against what was actually installed, not just
#           non-emptiness. #628 moved the version's SOURCE from a baked APK
#           asset to the companion lib's own manifest; the gate itself (#605)
#           is unchanged and still has to exist.
if grep -q 'readLibManifest' "$INSTALLER" \
   && grep -q 'INSTALLED_BOOTSTRAP_VERSION_FILE' "$INSTALLER" \
   && grep -q 'readInstalledBootstrapVersion' "$INSTALLER" \
   && grep -q 'bootstrapUpToDate' "$INSTALLER" \
   && grep -q 'libManifest.version.equals(readInstalledBootstrapVersion())' "$INSTALLER"; then
    ok "TermuxInstaller gates its 'prefix already exists' shortcut on the lib's declared version, not just non-emptiness"
else
    bad "TermuxInstaller has no bootstrap-version gate — a lib update that fixes the rootfs would never reach a phone with an already-extracted, stale \$PREFIX"
fi

if grep -q 'class LibBootstrapManifest' "$INSTALLER" && grep -q 'LIB_MANIFEST_ENTRY = "assets/rootfs-lib.json"' "$INSTALLER"; then
    ok "TermuxInstaller reads its version/payload/sha256 from the companion's own assets/rootfs-lib.json manifest"
else
    bad "TermuxInstaller does not read a rootfs-lib.json manifest — the installer's version gate has nothing to read (#628)"
fi


# ── #628 — the 400 MB runtime ships as the cloud-lib-rootfs-nixdroid
#           companion APK, and extraction out of it is sha256-gated ────────
# #618 got the bytes OUT of the app's own APK by publishing them to a rolling
# GH release and fetching them at runtime over HTTP. That fixed the "every
# one-line fix is a 400 MB update" defect and reintroduced a network dependency
# in the boot path — exactly what #348 had removed. #628 removes the network
# too: the bytes now ship as a second, signed, INSTALLED sibling APK
# (release.companions[], rootfs-lib/) that the Store installs/updates like any
# other fleet library, and TermuxInstaller only ever reads it locally. Two
# things have to be true together, same as #618: the bytes must be OUT of the
# app's own APK, and reading them out of the companion must be REFUSED unless
# they hash to the digest the companion's own manifest declares.
echo "── #628 the runtime ships as the cloud-lib-rootfs-nixdroid companion APK, and extraction is sha256-gated ──"

COMPANION_CHECK="$(python3 -c "
import json
d = json.load(open('$BUILD_JSON'))
companions = d.get('release', {}).get('companions', [])
c = next((x for x in companions if x.get('id') == 'rootfs-nixdroid'), None)
if c is None:
    print('MISSING')
else:
    need = ['gradle_task', 'apk_glob', 'asset', 'assets', 'paths_from', 'package']
    missing = [k for k in need if not c.get(k)]
    print('OK' if not missing else 'INCOMPLETE:' + ','.join(missing))
" 2>/dev/null)"
case "$COMPANION_CHECK" in
    OK)      ok "release.companions[] declares rootfs-nixdroid with gradle_task/apk_glob/asset/assets/paths_from/package" ;;
    MISSING) bad "build.json has no release.companions[] entry with id rootfs-nixdroid — #628's whole vehicle is missing" ;;
    *)       bad "release.companions[id=rootfs-nixdroid] is incomplete: ${COMPANION_CHECK#INCOMPLETE:}" ;;
esac

PUBLISHER_REL="bootstrap/publish-artifact.sh"
if [ -f "$DIR/$PUBLISHER_REL" ]; then
    bad "$PUBLISHER_REL is still here — #628 leaves exactly ONE way the rootfs arrives, and this was the #618 network path"
else
    ok "publish-artifact.sh is gone — no publish-then-fetch path is left to drift out of sync"
fi
if grep -q "\"publisher\"" "$BUILD_JSON"; then
    bad "build.json still declares artifact.publisher — #628 leaves nothing to publish"
else
    ok "build.json no longer declares artifact.publisher"
fi

LIBGRADLE="$DIR/rootfs-lib/build.gradle"
LIBMANIFEST="$DIR/rootfs-lib/src/main/AndroidManifest.xml"
[ -f "$LIBGRADLE" ] && ok "the companion lib module exists: rootfs-lib/build.gradle" \
                     || bad "rootfs-lib/build.gradle is missing — nothing builds the cloud-lib-rootfs-nixdroid APK"
[ -f "$LIBMANIFEST" ] && ok "the companion lib manifest exists: rootfs-lib/src/main/AndroidManifest.xml" \
                       || bad "rootfs-lib/src/main/AndroidManifest.xml is missing"
grep -q "':rootfs-lib'" "$DIR/settings.gradle" \
    && ok "settings.gradle includes :rootfs-lib" \
    || bad "settings.gradle does not include :rootfs-lib"
if [ -f "$LIBGRADLE" ] && grep -q 'task verifyRootfsPayload' "$LIBGRADLE" \
   && grep -q 'dependsOn verifyRootfsPayload' "$LIBGRADLE"; then
    ok "rootfs-lib's verifyRootfsPayload is wired ahead of asset merging"
else
    bad "rootfs-lib/build.gradle does not define verifyRootfsPayload and wire it before merge*Assets — an empty companion APK could install and provide nothing"
fi

# The staging step: bakeBootstrap must write BOTH the payload and its sha256
# manifest into rootfs-payload/, or the companion lib has nothing to package.
if grep -q 'rootfs-payload' "$GRADLE" && grep -q 'stagedPayload' "$GRADLE" \
   && grep -q 'stagedManifest' "$GRADLE" && grep -q 'sha256Of(baked)' "$GRADLE"; then
    ok "bakeBootstrap stages bootstrap.zip + rootfs-lib.json into rootfs-payload/ and writes the sha256 there"
else
    bad "app/build.gradle does not stage both the payload and its sha256 manifest into rootfs-payload/ (#628)"
fi

# THE point of #628: no bootstrap bytes, no digest sidecar and no url sidecar
# left in the app's OWN APK any more — all three moved into the companion.
if grep -q 'android.sourceSets.main.assets.srcDir bootstrapAssetsDir' "$GRADLE"; then
    bad "app/build.gradle still wires bootstrapAssetsDir as an assets sourceSet — the app APK would carry the bootstrap again (#628)"
else
    ok "app/build.gradle no longer wires bootstrapAssetsDir into the app APK's own assets/ (no bootstrap.zip, no .url, no .sha256 in the app APK)"
fi
if grep -q 'art\.digest_asset\|art\.url_asset' "$GRADLE"; then
    bad "app/build.gradle still bakes art.digest_asset/art.url_asset into the app APK — that is the #618 sidecar path #628 removes"
else
    ok "app/build.gradle no longer bakes the #618 digest/url sidecars into the app APK"
fi

# The installer side: resolves the companion package, trusts only a
# same-signature APK, reads its manifest, and gates extraction on the sha256
# that manifest declares — never a network fetch.
if grep -q 'getApplicationInfo' "$INSTALLER" && grep -q 'BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE' "$INSTALLER"; then
    ok "TermuxInstaller resolves the companion lib via PackageManager/getApplicationInfo(...).sourceDir"
else
    bad "TermuxInstaller does not resolve BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE via PackageManager (#628)"
fi
grep -q 'checkSignatures' "$INSTALLER" && grep -q 'SIGNATURE_MATCH' "$INSTALLER" \
    && ok "TermuxInstaller refuses a companion signed with a different key" \
    || bad "TermuxInstaller does not check the companion's signature — untrusted bytes could be extracted and executed (#628)"
grep -q 'class LibMissing' "$INSTALLER" \
    && ok "a missing companion is a distinguishable LibMissing, not a generic IOException" \
    || bad "TermuxInstaller has no LibMissing — the caller cannot offer the Store deep link"

# MUTATION PROOF. The assertion below is the one that matters, so it is run
# twice: once against the real file, and once against a copy with the digest
# comparison deleted. If the copy still passes, the assertion checks nothing
# and an unverified payload could be extracted without this tester noticing.
sha_gated() {
    grep -q 'DigestInputStream' "$1" && grep -q 'want.equals(got)' "$1" \
        && grep -q 'not the " + want' "$1"
}
if sha_gated "$INSTALLER"; then
    ok "the extraction is gated on the sha256 the companion's own manifest declares"
else
    bad "the extracted bootstrap is not compared to the declared digest — unread bytes would be extracted and marked executable (#628)"
fi
MUT="$(mktemp)"
grep -v 'want.equals(got)' "$INSTALLER" > "$MUT"
if sha_gated "$MUT"; then
    bad "MUTATION SURVIVED: deleting the digest comparison left the sha256 assertion green — it proves nothing"
else
    ok "mutation proved: deleting the digest comparison makes the assertion above go red"
fi
rm -f "$MUT"

grep -q 'part.delete()' "$INSTALLER" \
    && ok "a digest mismatch leaves nothing behind" \
    || bad "a rejected extraction is kept on disk"

# #628's whole point: nothing here ever opens a socket any more.
if grep -qE 'HttpURLConnection|java\.net\.URL' "$INSTALLER"; then
    bad "TermuxInstaller still touches the network — #628 removes the #618 runtime fetch entirely"
else
    ok "TermuxInstaller has no HTTP left in it — the rootfs is read out of the sibling APK, never fetched"
fi

# A missing companion must be legible, not a silent crash: a dialog naming the
# lib and offering the Store's Cloud tab deep link.
if grep -q 'showLibMissingDialog' "$INSTALLER" \
   && grep -q 'com.diegonmarcos.superapp' "$INSTALLER" \
   && grep -q 'page:config/store-cloud' "$INSTALLER" \
   && grep -q 'ActivityNotFoundException' "$INSTALLER"; then
    ok "a missing companion surfaces a dialog with the Store's Cloud tab deep link"
else
    bad "LibMissing does not surface a dialog with the Store deep link — a missing companion would fail silently or crash (#628)"
fi

# ── #638/#641 — the baked login-inner must BOOT, and boot into FISH ────────
# Every #595/#612 assertion above is a grep over the SOURCE of the patcher. Not
# one of them ever ran the patcher, and the #605 fixture's usr/lib/login-inner is
# the literal string "placeholder, unused by this fixture" — so the login-inner
# patch was never executed by any tester, and the script it produces was never
# run at all. That is how #638 shipped: dropping etc/UNINTIALISED skips upstream's
# first-login wizard, the wizard is the ONLY thing that ever creates /usr/bin/env
# (the bootstrap zip ships usr/bin/ EMPTY, with no SYMLINKS.txt line for env), and
# the statement immediately after the patched session-init line is an
# unconditional `exec /usr/bin/env bash`. Fresh install → exit 127 → no shell.
#
# The fixture below is no longer invented. It is the MEASURED text of
# usr/lib/login-inner in the pinned bootstrap (aarch64, sha256 64afc02d…, fetched
# and hash-checked against build.json): three session-init source lines with the
# live one LAST, the unconditional env exec, and a usershell assignment naming the
# zip's own bash TWICE — in the assignment and in the fallback message. #638's
# fixture said `usershell="/usr/lib/fake-usershell"`, which exists nowhere in that
# zip; a fixture that disagrees with the file it stands for can only prove things
# about itself.
#
# This section runs the REAL patcher over that text, then EXECUTES the result with
# /usr and the baked profile sandboxed under a temp dir — the fresh-install
# condition — and asserts which shell is reached. Mutation-proved twice: against
# the pre-#638 unconditional env exec, and against the pre-#641 bash usershell.
echo "── #638/#641 the baked usr/lib/login-inner boots, with no /usr/bin/env, into the baked fish ──"
LOGIN_SHELL="$(q forks.nixdroid.bootstrap.default_packages.login_shell_attr)"
# The store's shipped install dir, so D1 patches WITH the store line and runs the
# REAL engine: the shipped prompt-arrives property used to be proven on a flow
# that omitted the store step entirely and stubbed the shell -- exactly the two
# steps the phone hung in.
D1_STORE_DIR="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['store']['install_dir'])" "$DIR/../ab_cloud-terminal-store/store.json")"
D1_WORKDIR="$(mktemp -d)"
D1_RESULT="$(python3 - "$DIR/app/src/main/cpp" "$D1_WORKDIR" "$TO" "$FALLBACK" "$PROFILE_LINK" "$LOGIN_SHELL" "$DIR/../ab_cloud-terminal-store" "$D1_STORE_DIR" <<'PYEOF' 2>&1 | sed -n 's/^RESULT://p'
import os, shutil, subprocess, sys

cpp_dir, work, app_id, fallback, profile_link, login_shell, store_src, store_dir = sys.argv[1:9]
sys.path.insert(0, cpp_dir)
import bake_default_packages as bake

# MEASURED from the pinned zip, not invented. Trimmed only inside the wizard
# block (whose body the patch never touches); every line the patch matches on is
# verbatim, including the doubled bash literal.
OLD_SHELL = "/nix/store/q2j9ncs2b0zd7gbla21ck3jj4knpj23y-bash-5.2-p15/bin/bash"
SESSION_INIT = bake.SESSION_INIT_TEMPLATE.format(app_id=app_id)
fixture = f'''# This file is generated by Nix-on-Droid. DO NOT EDIT.

set -eo pipefail

if [ "$#" -eq 0 ]; then  # if script is called from within Nix-on-Droid app
  echo "Welcome to Nix-on-Droid!
If nothing works, open an issue at https://github.com/nix-community/nix-on-droid/issues or try the rescue shell."
fi


if [ -e /etc/UNINTIALISED ]; then
  echo "Setting default user profile..."
  {SESSION_INIT}
  echo "Congratulations! Now you have Nix installed with some default packages"
fi


{SESSION_INIT}

{bake.ENV_EXEC}


usershell="{OLD_SHELL}"
if [ "$#" -gt 0 ]; then  # if script is not called from within Nix-on-Droid app
  {bake.ENV_EXEC_ARGV}
elif [ -x "$usershell" ]; then
  exec -a "-${{usershell##*/}}" "$usershell"
else
  echo "Cannot execute shell '{OLD_SHELL}', falling back to bash"
  exec -l bash
fi
'''

patched = bake.patch_login_inner(fixture, app_id, fallback, profile_link,
                                 login_shell, store_dir)

# Two independent un-applications of the two fixes, each the shape that shipped.
# A no-op replace means the fix is absent, the mutant equals the patched file, and
# the runs below cannot tell them apart -- reported as a dead assertion.
pre638 = patched.replace(
    "# #641: the `exec /usr/bin/env bash` upstream puts here is dropped, so the\n"
    "# usershell block below chooses the login shell (and still falls back to bash).",
    bake.ENV_EXEC)
pre638 = pre638.replace('exec "$@"', bake.ENV_EXEC_ARGV)
pre641 = patched.replace(f"/{profile_link}/bin/{login_shell}", OLD_SHELL)
# The hang fix, un-applied: the probed exec back to upstream's bare one. Both
# literals are the patcher's own constants, so a drifted probe makes this a
# no-op replace -- reported below as a dead assertion, never silently green.
noprobe = patched.replace(bake.USERSHELL_PROBE, bake.USERSHELL_EXEC)
# The prompt deadline, un-applied: the probe stays, but a shell that passes it
# is exec'd blind again -- the shape that left the phone on a banner forever.
nodeadline = patched.replace(bake.USERSHELL_PROMPT_BOUNDED, bake.USERSHELL_EXEC)

# Sandbox: every absolute /usr/ path AND the baked profile move under $work, so
# /usr/bin/env is genuinely absent (as on a fresh install) and the login shell is
# the one this bake step really points at -- without touching the runner's fs.
os.makedirs(f"{work}/usr/bin", exist_ok=True)

# The REAL store, under the sandbox's /usr: the shipped engine, the shipped
# login-init.sh and the declaration render-store.py really renders for the nix
# terminal -- so the ensure fork D1 boots through is the one the phone runs, not
# an omitted step. Two sandbox-only rewrites on the DECLARATION COPY: its
# install-dir literal moves under $work (render-store asserts the shipped value,
# the sandbox relocates it), and its fixed links are dropped -- materialise_fixed
# would otherwise aim the RUNNER'S /usr/bin/env at a /nix path (S4-S10 and the
# #640 section own that behaviour; D1 owns the boot).
store_sb = f"{work}/{store_dir}"
os.makedirs(store_sb, exist_ok=True)
shutil.copy(f"{store_src}/login-init.sh", f"{store_sb}/login-init.sh")
shutil.copy(f"{store_src}/cloud-store", f"{store_sb}/cloud-store")
os.chmod(f"{store_sb}/cloud-store", 0o755)
subprocess.run([sys.executable, f"{store_src}/render-store.py", "nix",
                f"{store_sb}/declaration.sh"], check=True, capture_output=True)
decl = open(f"{store_sb}/declaration.sh").read().replace("/usr/", f"{work}/usr/")
decl = "\n".join("CLOUD_STORE_FIXED_LINKS=''" if l.startswith("CLOUD_STORE_FIXED_LINKS=")
                 else l for l in decl.splitlines()) + "\n"
open(f"{store_sb}/declaration.sh", "w").write(decl)
os.makedirs(f"{work}/home", exist_ok=True)
RUN_ENV = dict(os.environ, HOME=f"{work}/home", CLOUD_STORE_HOME=f"{work}/home",
               CLOUD_STORE_INSTALL_DIR=store_sb)
os.makedirs(os.path.dirname(f"{work}/{fallback}"), exist_ok=True)
open(f"{work}/{fallback}", "w").write('export PATH="/nonexistent/bin:$PATH"\n')
SHELL_BIN = f"{work}/{profile_link}/bin/{login_shell}"
os.makedirs(os.path.dirname(SHELL_BIN), exist_ok=True)


def sandbox(text):
    return text.replace("/usr/", f"{work}/usr/").replace(f"/{profile_link}/", f"{work}/{profile_link}/")


# The healthy stand-in proves its prompt the way the injected fish handler
# does: by writing $CLOUD_LOGIN_PROMPT_SEEN. The prompt-wedged one passes the
# `-c exit` probe and then never prompts (the device's shape); it execs sleep so
# the pid the watchdog kills is the one holding the capture pipes. The
# prompt-dying one passes the probe and exits with no prompt.
def flat(out):
    """One line, no backslashes: the RESULT lines are re-read through sh's echo,
    which expands escapes and would split a message across the next verdicts."""
    return " | ".join(l.strip() for l in out.replace("\\", "/").splitlines() if l.strip())


HEALTHY = '#!/bin/sh\n[ -n "$CLOUD_LOGIN_PROMPT_SEEN" ] && : > "$CLOUD_LOGIN_PROMPT_SEEN"\necho FISH_REACHED\n'
PROMPT_WEDGED = '#!/bin/sh\n[ "$1" = -c ] && exit 0\nexec sleep 30\n'
PROMPT_DYING = '#!/bin/sh\n[ "$1" = -c ] && exit 0\nexit 7\n'


def run(text, name, args, env_present, shell_present=True, shell_wedged=False,
        shell_body=None, deadline=None, limit=60):
    path = f"{work}/{name}"
    open(path, "w").write(sandbox(text))
    envbin = f"{work}/usr/bin/env"
    if env_present:
        open(envbin, "w").write('#!/bin/sh\necho ENV_REACHED\n')
        os.chmod(envbin, 0o755)
    elif os.path.exists(envbin):
        os.remove(envbin)
    if shell_present:
        # The wedged stand-in fails the liveness probe (any use, incl. `-c
        # exit`, fails) without costing the tester the probe's 5s deadline.
        body = shell_body or ('#!/bin/sh\nexit 1\n' if shell_wedged else HEALTHY)
        open(SHELL_BIN, "w").write(body)
        os.chmod(SHELL_BIN, 0o755)
    elif os.path.exists(SHELL_BIN):
        os.remove(SHELL_BIN)
    env = dict(RUN_ENV, TMPDIR=work)
    if deadline is not None:
        env["CLOUD_LOGIN_PROMPT_DEADLINE"] = str(deadline)
    try:
        p = subprocess.run(["bash", path, *args], capture_output=True, text=True,
                           env=env, stdin=subprocess.DEVNULL, timeout=limit)
    except subprocess.TimeoutExpired as e:
        # TimeoutExpired carries bytes even under text=True
        return "TIMEOUT", b"".join(x for x in (e.stdout, e.stderr) if x).decode(errors="replace")
    return p.returncode, p.stdout + p.stderr


why = []
rc, out = run(patched, "patched", [], False)
if rc != 0 or "FISH_REACHED" not in out or "⚠ login shell" in out:
    why.append(f"fresh-install boot rc={rc} out={out.strip()!r}")
# The REAL engine ran, it built a real generation, and the login still reached
# the shell: `current` is the ONE symlink an apply flips, so its existence after
# the boot above is proof step 3 executed instead of being omitted.
if not os.path.islink(f"{work}/home/.cloud-store/current"):
    why.append("the real cloud-store engine never built a generation during the boot -- D1 is once again proving a flow that skips the store step")
# A WEDGED login shell (executable, cannot run) must cost the user the shell of
# choice, never the terminal: one named stderr line, then upstream's bash.
rc, out = run(patched, "patched-wedged", [], False, shell_wedged=True)
if "FISH_REACHED" in out or "liveness probe" not in out:
    why.append(f"wedged-shell boot rc={rc} out={out.strip()!r} -- the blank-screen hang shape")
# A shell that passes the probe and then never draws a prompt -- what the phone
# did -- must cost one named line and bash once the deadline runs out.
rc, out = run(patched, "patched-promptwedged", [], False, shell_body=PROMPT_WEDGED,
              deadline=2, limit=20)
if rc == "TIMEOUT" or "drew no prompt within 2s" not in out or out.count("⚠ login shell") != 1:
    why.append(f"prompt-wedged boot rc={rc} out={flat(out)} -- the post-banner blank screen")
# And one that dies before its prompt is named as such, also landing in bash.
rc, out = run(patched, "patched-promptdying", [], False, shell_body=PROMPT_DYING, deadline=2)
if "ended (status 7) before drawing a prompt" not in out:
    why.append(f"prompt-dying boot rc={rc} out={flat(out)}")
if any(n.startswith(".cloud-login-prompt.") for n in os.listdir(work)):
    why.append(f"the prompt handshake left files behind in TMPDIR: {sorted(os.listdir(work))}")
# The #640 interaction: once /usr/bin/env EXISTS, a surviving `exec /usr/bin/env
# bash` would fire and bash would be the login shell forever. It must still be fish.
rc, out = run(patched, "patched-envok", [], True)
if rc != 0 or "FISH_REACHED" not in out or "ENV_REACHED" in out:
    why.append(f"env-present boot rc={rc} out={out.strip()!r}")
rc, out = run(patched, "patched-argv", ["echo", "ARGV_REACHED"], False)
if rc != 0 or "ARGV_REACHED" not in out:
    why.append(f"argv path rc={rc} out={out.strip()!r}")
# And upstream's own safety net still works when the shell is not there.
rc, out = run(patched, "patched-noshell", [], False, shell_present=False)
# The MESSAGE is the evidence that upstream's fallback branch was taken; the exit
# status afterwards belongs to whatever bash the runner has and is not ours to assert.
if "falling back to bash" not in out:
    why.append(f"missing-shell fallback rc={rc} out={out.strip()!r}")
if OLD_SHELL in patched:
    why.append("the old bash literal survives in the patched file")
print("RESULT:" + ("OK" if not why else "FAIL " + "; ".join(why)))

mwhy = []
rc, out = run(pre638, "pre638", [], False)
if rc != 127 or "No such file or directory" not in out:
    mwhy.append(f"no-arg rc={rc} out={out.strip()!r}")
rc, out = run(pre638, "pre638-argv", ["echo", "ARGV_REACHED"], False)
if rc != 127 or "No such file or directory" not in out:
    mwhy.append(f"argv rc={rc} out={out.strip()!r}")
rc, out = run(pre638, "pre638-envok", [], True)
if "ENV_REACHED" not in out or "FISH_REACHED" in out:
    mwhy.append(f"env-present rc={rc} out={out.strip()!r}")
print("RESULT:MUT " + ("OK" if not mwhy else "SURVIVED " + "; ".join(mwhy)))

fwhy = []
rc, out = run(pre641, "pre641", [], False)
if "FISH_REACHED" in out:
    fwhy.append(f"still reached fish rc={rc} out={out.strip()!r}")
if OLD_SHELL not in pre641:
    fwhy.append("the mutation was a no-op: no bash literal to restore")
print("RESULT:FISH " + ("OK" if not fwhy else "SURVIVED " + "; ".join(fwhy)))

pwhy = []
if noprobe == patched:
    pwhy.append("un-applying the probe was a no-op: the patched file carries no USERSHELL_PROBE text")
rc, out = run(noprobe, "noprobe-wedged", [], False, shell_wedged=True)
if "liveness probe" in out or "FISH_REACHED" in out:
    pwhy.append(f"unprobed exec still produced a verdict on a wedged shell rc={rc} out={out.strip()!r}")
print("RESULT:PROBE " + ("OK" if not pwhy else "SURVIVED " + "; ".join(pwhy)))

dwhy = []
if nodeadline == patched:
    dwhy.append("un-applying the prompt deadline was a no-op: the patched file carries no USERSHELL_PROMPT_BOUNDED text")
rc, out = run(nodeadline, "nodeadline-promptwedged", [], False, shell_body=PROMPT_WEDGED,
              deadline=2, limit=8)
if rc != "TIMEOUT" or "drew no prompt" in out:
    dwhy.append(f"the blind exec still ended on a prompt-wedged shell rc={rc} out={flat(out)}")
print("RESULT:DEADLINE " + ("OK" if not dwhy else "SURVIVED " + "; ".join(dwhy)))

# ── #715: the trace names the step, on a REAL pty ──────────────────────────
# The phone hung and nothing said where. Every login step now writes one
# timestamped line to the screen and to $HOME/.cloud-login.log, and the line
# that matters most is the job-control fact: is the login (and then the shell)
# in the tty's FOREGROUND process group? A shell outside it stops itself with
# SIGTTIN before its prompt -- the phone's exact screen when measured with the
# shipped proot-static. Only a real pty has a foreground pgrp, so these runs use
# one, as the session leader, the way Termux's create_subprocess does.
import pty, select, shutil, signal, time
REAL_BASH = shutil.which("bash")
os.makedirs(f"{work}/wrap", exist_ok=True)
# A bash that never prompts when started as an interactive shell (no script
# operand, no -c), but still runs scripts: so tier 2 wedges, rescue does not.
open(f"{work}/wrap/bash", "w").write(
    f'#!{REAL_BASH}\nfor a in "$@"; do case "$a" in -c|/dev/stdin) exec {REAL_BASH} "$@";; esac; '
    f'[ -f "$a" ] && exec {REAL_BASH} "$@"; done\nexec sleep 30\n')
os.chmod(f"{work}/wrap/bash", 0o755)
LOG = f"{work}/home/.cloud-login.log"


def pty_run(text, name, background=False, shell_body=HEALTHY, typed=b"", bash_wedged=False, settle=4):
    path = f"{work}/{name}"
    open(path, "w").write(sandbox(text))
    open(SHELL_BIN, "w").write(shell_body)
    os.chmod(SHELL_BIN, 0o755)
    if os.path.exists(LOG):
        os.remove(LOG)
    env = dict(RUN_ENV, TMPDIR=work, TERM="dumb", CLOUD_LOGIN_PROMPT_DEADLINE="2", CLOUD_LOGIN_BASH_DEADLINE="2")
    if bash_wedged:
        env["PATH"] = f"{work}/wrap:" + env.get("PATH", "/usr/bin:/bin")
    pid, fd = pty.fork()
    if pid == 0:
        try:
            if background:  # the login outside the tty's foreground pgrp
                if os.fork():
                    os.wait()
                    os._exit(0)
                os.setpgid(0, 0)
            os.execve(REAL_BASH, [REAL_BASH, path], env)
        finally:
            os._exit(127)
    out = b""
    end = time.time() + settle
    sent = False
    while time.time() < end:
        if typed and not sent and time.time() > end - 2:
            os.write(fd, typed)
            sent = True
        r, _, _ = select.select([fd], [], [], 0.2)
        if r:
            try:
                out += os.read(fd, 65536)
            except OSError:
                break
    for p in os.listdir("/proc"):  # the whole session, stopped members included
        try:
            f = open(f"/proc/{p}/stat").read()
            if int(f[f.rindex(")") + 2:].split()[3]) == pid:
                os.kill(int(p), signal.SIGKILL)
        except (OSError, ValueError):
            pass
    os.close(fd)
    try:
        os.waitpid(pid, 0)
    except ChildProcessError:
        pass
    return out.decode(errors="replace"), (open(LOG).read() if os.path.exists(LOG) else "")


def line(log, needle):
    return next((l for l in log.splitlines() if needle in l), "")


twhy = []
scr, log = pty_run(patched, "t-fg")
if "FOREGROUND" not in line(log, "login-inner: start,"):
    twhy.append(f"a foreground login was not traced FOREGROUND: {line(log, 'login-inner: start,')!r}")
if "FOREGROUND" not in line(log, f"/{login_shell}: starting,"):
    twhy.append(f"the shell's own pgrp was not traced as the tty's foreground: {flat(log)}")
# Every step, in order -- the step a phone stops after is the step it hung in.
steps = ["login-inner: start,", "session init: sourcing", "session init: done", "store init: start",
         "store init: done", "probe: ", "probe: passed", f"/{login_shell}: starting,"]
at = [log.find(s) for s in steps]
if -1 in at or at != sorted(at):
    twhy.append(f"the trace does not carry every step in order {steps}: {flat(log)}")
if "[login " not in scr:
    twhy.append("an interactive login put no trace line on the screen")
scr, log = pty_run(patched, "t-bg", background=True)
if "BACKGROUND(tty foreground pgrp" not in line(log, "login-inner: start,"):
    twhy.append(f"a login outside the tty's foreground pgrp was not traced BACKGROUND: {line(log, 'login-inner: start,')!r}")
# A caller's command (`login <cmd>`) gets its own output and nothing else.
if os.path.exists(LOG):
    os.remove(LOG)
rc, out = run(patched, "t-argv", ["echo", "ARGV_REACHED"], False)
if "[login " in out or "login-inner: start," not in (open(LOG).read() if os.path.exists(LOG) else ""):
    twhy.append(f"the argv path printed trace lines or wrote none to the file: {flat(out)}")
print("RESULT:TRACE " + ("OK" if not twhy else "FAIL " + "; ".join(twhy)))

# Planted: the foreground test inverted. The verdicts must flip, i.e. the
# FOREGROUND/BACKGROUND assertions above read the real comparison.
inv = patched.replace('elif [ "$4" = "$7" ]', 'elif [ "$4" != "$7" ]')
vwhy = []
if inv == patched:
    vwhy.append("the inverted-verdict mutation did not apply")
scr, log = pty_run(inv, "t-inv")
if "FOREGROUND" in line(log, "login-inner: start,"):
    vwhy.append("an inverted pgrp/tpgid comparison still traced FOREGROUND")
print("RESULT:VERDICT " + ("OK" if not vwhy else "SURVIVED " + "; ".join(vwhy)))

# Tiers: fish never prompts AND an interactive bash never prompts. Rescue mode
# must still execute a typed line, and both deadlines leave a snapshot naming
# the hung shell's state and the tty modes.
TYPED = b"echo RESCUE_$((6*7))\n"
rwhy = []
scr, log = pty_run(patched, "t-tiers", shell_body=PROMPT_WEDGED, typed=TYPED, bash_wedged=True, settle=9)
if "RESCUE_42" not in scr:
    rwhy.append(f"rescue mode did not run a typed line: {flat(scr)}")
if log.count("no prompt after 2s: pid") != 2 or log.count("State=") < 2 or "tty modes:" not in log:
    rwhy.append(f"the two deadlines did not each leave a state snapshot: {flat(log)}")
if "falling back to rescue mode" not in scr:
    rwhy.append(f"the screen did not name the bash tier's failure: {flat(scr)}")
print("RESULT:TIERS " + ("OK" if not rwhy else "FAIL " + "; ".join(rwhy)))

# Planted: v0.3.11's fallback (a blind `exec -l bash`) instead of the tiers.
v311 = patched.replace(bake.USERSHELL_FALLBACK, "exec -l bash")
mwhy = []
if v311 == patched:
    mwhy.append("the v0.3.11-fallback mutation did not apply")
scr, log = pty_run(v311, "t-v311", shell_body=PROMPT_WEDGED, typed=TYPED, bash_wedged=True, settle=9)
if "RESCUE_42" in scr:
    mwhy.append("the blind bash fallback still ran the typed line")
print("RESULT:TIERMUT " + ("OK" if not mwhy else "SURVIVED " + "; ".join(mwhy)))
PYEOF
)"
case "$(echo "$D1_RESULT" | sed -n 1p)" in
    OK) ok "the patched usr/lib/login-inner reaches the baked $LOGIN_SHELL with no nix profile — through the REAL cloud-store ensure (a generation was built) — still execs a caller's argv, falls back to bash if the shell is missing, and a shell that is WEDGED, never draws a prompt, or dies before one costs one named stderr line + bash, never a blank screen" ;;
    *)  bad "the baked usr/lib/login-inner does not boot into $LOGIN_SHELL on a fresh install: $(echo "$D1_RESULT" | sed -n 1p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 2p)" in
    "MUT OK") ok "mutation proved: restoring upstream's unconditional env exec dies with exit 127 / 'No such file or directory' when env is absent — exactly what shipped — and steals the shell back to bash when it is present, which is why #641 drops that line instead of guarding it" ;;
    *)        bad "MUTATION SURVIVED: the pre-#638 login-inner still passed the boot assertion, so it proves nothing: $(echo "$D1_RESULT" | sed -n 2p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 3p)" in
    "FISH OK") ok "mutation proved: leaving usershell at the zip's own bash means $LOGIN_SHELL is never reached" ;;
    *)         bad "MUTATION SURVIVED: un-retargeting usershell still reached $LOGIN_SHELL, so that assertion proves nothing: $(echo "$D1_RESULT" | sed -n 3p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 4p)" in
    "PROBE OK") ok "mutation proved: un-applying the liveness probe turns a wedged shell back into a silent dead end — the wedged-shell assertion discriminates" ;;
    *)          bad "MUTATION SURVIVED: the unprobed exec still passed the wedged-shell assertion, so it proves nothing: $(echo "$D1_RESULT" | sed -n 4p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 5p)" in
    "DEADLINE OK") ok "mutation proved: exec'ing a probe-passing shell blind again hangs on one that never prompts — the prompt-deadline assertion discriminates" ;;
    *)             bad "MUTATION SURVIVED: without the prompt deadline the prompt-wedged boot still ended, so it proves nothing: $(echo "$D1_RESULT" | sed -n 5p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 6p)" in
    "TRACE OK") ok "#715: every login step is traced in order to screen and \$HOME/.cloud-login.log, the login and the shell are each named FOREGROUND/BACKGROUND against the tty's foreground pgrp on a real pty, and a caller's command gets no trace on its output" ;;
    *)          bad "#715: the login trace does not name the step a login hangs in: $(echo "$D1_RESULT" | sed -n 6p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 7p)" in
    "VERDICT OK") ok "mutation proved: an inverted pgrp/tpgid comparison flips the traced verdict" ;;
    *)            bad "MUTATION SURVIVED: $(echo "$D1_RESULT" | sed -n 7p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 8p)" in
    "TIERS OK") ok "#715: with fish AND interactive bash both never prompting, rescue mode still runs a typed line, and each deadline leaves a state + tty-mode snapshot" ;;
    *)          bad "#715: a login whose shells all hang is still a dead screen: $(echo "$D1_RESULT" | sed -n 8p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 9p)" in
    "TIERMUT OK") ok "mutation proved: v0.3.11's blind exec -l bash fallback never runs the typed line on the same wedge" ;;
    *)            bad "MUTATION SURVIVED: $(echo "$D1_RESULT" | sed -n 9p)" ;;
esac
rm -rf "$D1_WORKDIR"

# ── the hang class itself: every injected blocking-capable login step is BOUNDED ──
# The two steps between the banner and the prompt that CAN block forever are the
# store's ensure fork and the login-shell exec. Each must carry a coreutils
# `timeout N` and a LOUD stderr skip; each pin is mutation-proved by stripping
# the timeout on a copy. (The #612 stderr-notice pin above is untouched.)
STORE_INIT="$DIR/../ab_cloud-terminal-store/login-init.sh"
if grep -Eq 'timeout [0-9]+ "\$CLOUD_STORE_INSTALL_DIR/\$CLOUD_STORE_ENGINE" ensure' "$STORE_INIT"; then
    ok "login-init.sh bounds the cloud-store ensure fork with a coreutils timeout"
else
    bad "login-init.sh forks the cloud-store engine UNBOUNDED — under single-threaded proot that is the post-banner hang"
fi
if grep -q 'echo .*cloud-store init skipped.*>&2' "$STORE_INIT"; then
    ok "a skipped store init says so on stderr — never a silent || true"
else
    bad "login-init.sh discards the ensure failure silently — the store can rot with no line ever naming it"
fi
if grep -q 'timeout 5 "\$usershell" -c exit' "$BAKE_PY" \
   && grep -q 'liveness probe.*>&2' "$BAKE_PY"; then
    ok "the baked login-shell exec is preceded by a bounded liveness probe with a named stderr skip"
else
    bad "the login-shell exec is unbounded (or its skip is silent) — a wedged fish is a blank screen forever"
fi
MUT_TB="$(mktemp)"; MUT_TS="$(mktemp)"
awk '{gsub(/timeout [0-9]+ /,"")}1' "$BAKE_PY" > "$MUT_TB"
awk '{gsub(/timeout [0-9]+ /,"")}1' "$STORE_INIT" > "$MUT_TS"
if grep -Eq 'timeout [0-9]+ "\$CLOUD_STORE_INSTALL_DIR/\$CLOUD_STORE_ENGINE" ensure' "$MUT_TS" \
   || grep -q 'timeout 5 "\$usershell" -c exit' "$MUT_TB"; then
    bad "MUTATION SURVIVED: stripping every timeout still passes the boundedness pins, so they pin nothing"
else
    ok "mutation proved: stripping any timeout fails its boundedness pin"
fi
rm -f "$MUT_TB" "$MUT_TS"

# ── #641 — /etc/profile is upstream's, it is broken on a fresh install, and it
#           is the line Diego's phone actually printed ─────────────────────
# etc/static/profile (symlinked as etc/profile, read by the login bash) is ONE
# line sourcing a /nix/store path that the zip does not contain — the session-init
# derivation only exists after `nix-on-droid switch`, which this app deliberately
# never runs. So every single login printed:
#   -bash: /nix/store/…-nix-on-droid-session-init.sh/etc/profile.d/…: No such file
# Guarded, not deleted, with the same fallback as login-inner's own copy. Executed
# here, both branches, and mutation-proved against the unpatched line.
echo "── #641 /etc/profile no longer sources a store path the rootfs never had ──"
D2_WORKDIR="$(mktemp -d)"
D2_RESULT="$(python3 - "$DIR/app/src/main/cpp" "$D2_WORKDIR" "$FALLBACK" <<'PYEOF' 2>&1 | sed -n 's/^RESULT://p'
import os, subprocess, sys

cpp_dir, work, fallback = sys.argv[1:4]
sys.path.insert(0, cpp_dir)
import bake_default_packages as bake

# VERBATIM the whole content of etc/static/profile in the pinned zip, hash and
# all. The hash is the one that appeared on the phone; it is a fixture, and the
# patcher parses the path out of it rather than knowing it.
ORIGINAL = ('. "/nix/store/5pmf0nlk348101av1wzcp18v089cably-nix-on-droid-session-init.sh'
            '/etc/profile.d/nix-on-droid-session-init.sh"\n')

patched = bake.patch_etc_profile(ORIGINAL, fallback)
os.makedirs(os.path.dirname(f"{work}/{fallback}"), exist_ok=True)
open(f"{work}/{fallback}", "w").write('echo FALLBACK_SOURCED\n')


def run(text, name, store_present):
    # One sandbox root PER RUN. Sharing one is how this very tester first passed
    # its own mutation: the "generation exists" case created the store path, and
    # the unpatched mutant then found it and looked fine.
    root = f"{work}/{name}"
    os.makedirs(root, exist_ok=True)
    # The store path is absolute; the sandbox root stands in for proot's binds,
    # exactly as in the #640 section.
    body = text.replace('"/nix/', '"%s/nix/' % root).replace(f". /{fallback}", f". {work}/{fallback}")
    path = f"{root}/profile"
    open(path, "w").write(body)
    if store_present:
        target = (root + "/nix/store/5pmf0nlk348101av1wzcp18v089cably-"
                  "nix-on-droid-session-init.sh/etc/profile.d")
        os.makedirs(target, exist_ok=True)
        open(f"{target}/nix-on-droid-session-init.sh", "w").write("echo REAL_SESSION_INIT\n")
    p = subprocess.run(["bash", path], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


why = []
rc, out = run(patched, "profile-fresh", False)
if rc != 0 or "FALLBACK_SOURCED" not in out or "No such file" in out:
    why.append(f"fresh install rc={rc} out={out.strip()!r}")
rc, out = run(patched, "profile-real", True)
if rc != 0 or "REAL_SESSION_INIT" not in out:
    why.append(f"with a real generation rc={rc} out={out.strip()!r}")
print("RESULT:" + ("OK" if not why else "FAIL " + "; ".join(why)))

mwhy = []
rc, out = run(ORIGINAL, "profile-unpatched", False)
if rc == 0 or "No such file or directory" not in out:
    mwhy.append(f"rc={rc} out={out.strip()!r}")
print("RESULT:MUT " + ("OK" if not mwhy else "SURVIVED " + "; ".join(mwhy)))
PYEOF
)"
case "$(echo "$D2_RESULT" | sed -n 1p)" in
    OK) ok "the patched /etc/profile sources the baked fallback on a fresh install and the real session-init once a generation exists" ;;
    *)  bad "/etc/profile still misbehaves: $(echo "$D2_RESULT" | sed -n 1p)" ;;
esac
case "$(echo "$D2_RESULT" | sed -n 2p)" in
    "MUT OK") ok "mutation proved: upstream's unpatched /etc/profile reproduces the exact 'No such file or directory' line from Diego's phone" ;;
    *)        bad "MUTATION SURVIVED: the unpatched /etc/profile passed too, so the assertion above proves nothing: $(echo "$D2_RESULT" | sed -n 2p)" ;;
esac
rm -rf "$D2_WORKDIR"

# The login shell must be a baked attr, not a wish: the emitter refuses the build
# otherwise, and this is the declaration it refuses against.
case " $ATTRS " in
    *" $LOGIN_SHELL "*) ok "login_shell_attr ($LOGIN_SHELL) is one of the baked attrs" ;;
    *)                  bad "login_shell_attr ($LOGIN_SHELL) is not in default_packages.attrs — login-inner would name a shell nothing bakes" ;;
esac

# And the prose #639 removed must not come back: the shell notice states a fact,
# it does not walk the user through a Settings tree the app already deep-links to.
if grep -qE 'Settings *(▸|→|>)' "$BAKE_PY"; then
    bad "bake_default_packages.py prints a 'Settings ▸ …' breadcrumb again — TermuxActivity deep-links to the toggle, so describing the route is the #639 defect"
else
    ok "no 'Settings ▸ …' breadcrumb in the baked shell notice (#639)"
fi

# ── #640 — /usr/bin/env must EXIST, not merely be survivable ──────────────
# #638 guarded the two unconditional `exec /usr/bin/env` lines so the terminal
# boots without that path. It boots — and every `#!/usr/bin/env <interp>` script
# in it still dies, which is the commonest shebang in existence. The file has to
# be there, and the only thing that ever created it (upstream's first-login
# wizard) is exactly what default_packages exists to skip.
#
# This section EXECUTES. It takes the REAL SYMLINKS.txt line the emitter
# produces, extracts it into a sandbox the way TermuxInstaller does, resolves it
# the way proot's /nix and /usr binds do, writes a script whose first line is
# literally `#!/usr/bin/env sh`, and runs it. When the runner can give us a mount
# namespace, the LITERAL /usr/bin/env is bind-replaced inside it so the KERNEL
# does the shebang resolution; when it cannot, the script's own shebang line is
# parsed and the resolved interpreter really exec'd. Either way a shell runs a
# file, rather than a grep reading the emitter and believing it.
echo "── #640 a #!/usr/bin/env sh script actually runs in the baked rootfs ──"
E1_WORKDIR="$(mktemp -d)"
E1_RESULT="$(python3 - "$DIR/app/src/main/cpp" "$E1_WORKDIR" "$PROFILE_LINK" <<'PYEOF' 2>&1 | sed -n 's/^RESULT://p'
import os, shutil, subprocess, sys

cpp_dir, work, profile_link = sys.argv[1:4]
sys.path.insert(0, cpp_dir)
import bake_default_packages as bake

# Stand-ins for the two store paths the real bake step copies in: the profile
# generation (whose bin/env is a symlink into coreutils, as `nix profile
# install` builds it) and coreutils itself (a real, executable file). Fake
# hashes are deliberate -- the emitter must never write a hash of its own.
GEN = "/nix/store/zzz-profile-fake"
CORE = "/nix/store/zzz-coreutils-fake"
SCRIPT = "#!/usr/bin/env sh\necho SHEBANG_RAN\n"


def build(root, lines, env_in_profile=True):
    """Extract SYMLINKS.txt into `root` the way TermuxInstaller's loop does."""
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root + CORE + "/bin")
    envbin = root + CORE + "/bin/env"
    open(envbin, "w").write('#!/bin/sh\nexec "$@"\n')
    os.chmod(envbin, 0o755)          # what EXECUTABLES.txt does on the device
    os.makedirs(root + GEN + "/bin")
    # The generation's own bin/env is a store symlink, and add_tree() copies it
    # into SYMLINKS.txt exactly like any other -- so it goes through the same
    # extraction loop below, not around it.
    if env_in_profile:
        lines = [CORE + "/bin/env←" + GEN.lstrip("/") + "/bin/env"] + list(lines)
    for line in lines:
        target, _, link = line.partition("←")
        full = os.path.join(root, link)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        # An absolute target resolves through proot's binds on the phone
        # (files/usr/nix:/nix, files/usr/usr:/usr); here the sandbox root plays
        # that part. That prefix is the ONLY substitution made anywhere below.
        os.symlink(root + target if target.startswith("/") else target, full)
    path = os.path.join(root, "shebang-probe.sh")
    open(path, "w").write(SCRIPT)
    os.chmod(path, 0o755)
    return path


def resolved_env(root):
    p = os.path.join(root, bake.ENV_SYMLINK)
    if not os.path.lexists(p):
        return None, "no " + bake.ENV_SYMLINK + " was extracted at all"
    real = os.path.realpath(p)
    if not os.path.exists(real):
        return None, "dangling symlink: " + p + " -> " + os.readlink(p)
    if not os.access(real, os.X_OK):
        return None, "target is not executable: " + real
    return real, None


def ns_usable():
    """Can we get a mount namespace and bind a single file inside it?

    Probed on /etc/hostname, nothing to do with env, so an infrastructure
    failure here can never be mistaken for this ticket's defect.
    """
    probe = work + "/ns-probe"
    open(probe, "w").write("NS_BIND_OK\n")
    try:
        p = subprocess.run(["unshare", "-rm", "sh", "-c",
                            'mount --bind "$1" /etc/hostname && cat /etc/hostname',
                            "_", probe], capture_output=True, text=True)
    except OSError:
        return False
    return p.returncode == 0 and "NS_BIND_OK" in p.stdout


def run_kernel(root, script, env_absent):
    """Real kernel shebang resolution of the LITERAL /usr/bin/env."""
    if env_absent:
        empty = work + "/empty"
        os.makedirs(empty, exist_ok=True)
        cmd, args = 'mount --bind "$1" /usr/bin && exec "$2"', [empty, script]
    else:
        cmd, args = ('mount --bind "$1" /usr/bin/env && exec "$2"',
                     [os.path.join(root, bake.ENV_SYMLINK), script])
    p = subprocess.run(["unshare", "-rm", "sh", "-c", cmd, "_", *args],
                       capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def run_resolved(root, script):
    """No namespace: parse the script's OWN shebang and exec what it names."""
    first = open(script).readline().rstrip("\n")
    if not first.startswith("#!"):
        return 126, "no shebang line in " + script
    parts = first[2:].split()
    interp, rest = parts[0], parts[1:]
    mapped = root + interp if interp.startswith("/") else interp
    try:
        p = subprocess.run([mapped, *rest, script], capture_output=True, text=True)
    except OSError as e:
        return 127, mapped + ": " + (e.strerror or str(e))
    return p.returncode, p.stdout + p.stderr


KERNEL = ns_usable()
mode = "kernel mount-namespace bind of the literal /usr/bin/env" if KERNEL \
       else "shebang parsed from the script and the resolved interpreter exec'd"


def run(root, script, env_absent=False):
    return run_kernel(root, script, env_absent) if KERNEL else run_resolved(root, script)


gen_line = GEN + "←" + profile_link
env_line = bake.env_symlink_line(profile_link)

# ── the real thing ────────────────────────────────────────────────────────
root = work + "/good"
script = build(root, [gen_line, env_line])
why = []
real, err = resolved_env(root)
if err:
    why.append("chain: " + err)
rc, out = run(root, script)
if rc != 0 or "SHEBANG_RAN" not in out:
    why.append("run rc=%d out=%r" % (rc, out.strip()))
print("RESULT:GOOD " + ("OK via " + mode if not why else "FAIL " + "; ".join(why)))

# ── mutation A: drop the emitted SYMLINKS.txt line ────────────────────────
root = work + "/no-line"
script = build(root, [gen_line])
why = []
real, err = resolved_env(root)
if not err:
    why.append("chain still resolved to " + str(real))
rc, out = run(root, script, env_absent=True)
if rc == 0 or "No such file or directory" not in out:
    why.append("still ran: rc=%d out=%r" % (rc, out.strip()))
print("RESULT:MUTA " + ("OK " + repr(err) if not why else "SURVIVED " + "; ".join(why)))

# ── mutation B: coreutils out of attrs -> the profile has no bin/env, so the
#     line is emitted but points at nothing. A tester that only checked the
#     link EXISTS would pass here; this one must call it dangling and the
#     script must still not run.
root = work + "/no-coreutils"
script = build(root, [gen_line, env_line], env_in_profile=False)
why = []
real, err = resolved_env(root)
if not err or "dangling" not in err:
    why.append("chain verdict was %r, expected a dangling-symlink verdict" % (err,))
rc, out = run(root, script)
if rc == 0 or "SHEBANG_RAN" in out:
    why.append("still ran: rc=%d out=%r" % (rc, out.strip()))
print("RESULT:MUTB " + ("OK " + repr(err) if not why else "SURVIVED " + "; ".join(why)))

# ── the emitter's own build-time reachability gate, executed ───────────────
rel = "nix/store/zzz-coreutils-fake/bin/env"
verdicts = [
    ("reachable", bake.env_target_unreachable(rel, set(), {rel: b""}, [rel]), None),
    ("not chmodded", bake.env_target_unreachable(rel, set(), {rel: b""}, []), "EXECUTABLES.txt"),
    ("not in zip", bake.env_target_unreachable(rel, set(), {}, []), "not a file entry"),
    ("already shipped", bake.env_target_unreachable(rel, {rel}, {}, []), None),
]
why = [
    "%s -> %r" % (name, got)
    for name, got, want in verdicts
    if (want is None and got is not None) or (want is not None and (got is None or want not in got))
]
print("RESULT:GATE " + ("OK" if not why else "FAIL " + "; ".join(why)))
PYEOF
)"
E1_GOOD="$(echo "$E1_RESULT" | sed -n 1p)"
case "$E1_GOOD" in
    GOOD\ OK*) ok "a script whose shebang is literally '#!/usr/bin/env sh' RUNS in the baked rootfs — ${E1_GOOD#GOOD OK via }" ;;
    *)         bad "a '#!/usr/bin/env sh' script does not run in the baked rootfs: $E1_GOOD" ;;
esac
case "$(echo "$E1_RESULT" | sed -n 2p)" in
    MUTA\ OK*) ok "mutation proved: without the emitted SYMLINKS.txt line the same script dies 'No such file or directory' — exactly what ships today" ;;
    *)         bad "MUTATION SURVIVED: removing the usr/bin/env SYMLINKS.txt line changed nothing, so the assertion above proves nothing: $(echo "$E1_RESULT" | sed -n 2p)" ;;
esac
case "$(echo "$E1_RESULT" | sed -n 3p)" in
    MUTB\ OK*) ok "mutation proved: with coreutils out of attrs the link is called DANGLING and the script still does not run (a link to nothing is worse than no link)" ;;
    *)         bad "MUTATION SURVIVED: a usr/bin/env pointing at nothing passed as if it worked: $(echo "$E1_RESULT" | sed -n 3p)" ;;
esac
case "$(echo "$E1_RESULT" | sed -n 4p)" in
    GATE\ OK) ok "bake_default_packages.py refuses to emit the link unless the resolved env is a real zip entry listed in EXECUTABLES.txt" ;;
    *)        bad "the emitter's build-time reachability gate does not hold: $(echo "$E1_RESULT" | sed -n 4p)" ;;
esac
rm -rf "$E1_WORKDIR"

# The declaration the emitted target is derived FROM, and no store literal in it.
case " $ATTRS " in
    *" coreutils "*) ok "default_packages.attrs declares coreutils, so a coreutils bin/env is really in the baked profile (#640)" ;;
    *)               bad "default_packages.attrs has no coreutils — usr/bin/env would point into a store path that is not shipped (#640)" ;;
esac
if grep -qE '/nix/store/[0-9a-z]{32}' "$BAKE_PY"; then
    bad "bake_default_packages.py hardcodes a /nix/store hash — it moves with the nixpkgs pin, so the usr/bin/env link would rot silently (#640)"
else
    ok "no /nix/store hash literal in bake_default_packages.py — the env link target is derived from profile_link"
fi

## ───────────────────────────────────────────────────────────────────────────
## #644 — the declarative link store
##
## Every assertion below is MUTATION-PROVED and asserts on the MESSAGE, not on
## exit status: a verify that returns 1 while naming nothing actionable is the
## same defect as the three it exists to replace (#638/#640/#641), which were all
## discovered by a user rather than by a check. So each check first breaks
## something and requires the engine to SAY which link, then fixes it.
## Offline: no nix, no zip, no Android SDK. The sandbox is a fake bin directory
## and a rendered declaration, which is all the engine ever reads.
## ───────────────────────────────────────────────────────────────────────────
echo "── #644 declarative link store [ab_cloud-terminal-store] ──"

STORE_SRC="$DIR/../ab_cloud-terminal-store"
ENGINE="$STORE_SRC/cloud-store"
RENDERER="$STORE_SRC/render-store.py"

if [ ! -f "$ENGINE" ] || [ ! -f "$RENDERER" ]; then
    bad "the shared link store is missing from $STORE_SRC — build.json::...identity_files names it, so the bake and the content address both depend on it"
else

SB="$(mktemp -d)"
trap 'rm -rf "$SB"' EXIT

# ── S1 — the renderer projects THE APP'S OWN list, it does not restate it ──
# A fake repo, so the data-only mutation below never touches the real build.json:
# render-store.py resolves app_dir against the directory holding store.json, so a
# copy of the store beside a fake app dir renders entirely inside the sandbox.
mkdir -p "$SB/repo/ac_cloud-nix-on-droid" "$SB/repo/ac_cloud-termux/rootfs"
cp "$STORE_SRC"/store.json "$STORE_SRC"/cloud-store "$STORE_SRC"/render-store.py \
   "$STORE_SRC"/login-init.sh "$STORE_SRC"/login-exec "$SB/repo/" 2>/dev/null
mkdir -p "$SB/repo/ab_cloud-terminal-store"
mv "$SB/repo"/store.json "$SB/repo"/cloud-store "$SB/repo"/render-store.py \
   "$SB/repo"/login-init.sh "$SB/repo"/login-exec "$SB/repo/ab_cloud-terminal-store/"
FAKE_RENDER="$SB/repo/ab_cloud-terminal-store/render-store.py"

fake_build_json() {
    python3 -c "
import json, sys
json.dump({'forks': {'nixdroid': {'bootstrap': {'default_packages': {
    'binaries': sys.argv[2:],
    'profile_link': 'nix/var/nix/profiles/per-user/nix-on-droid/profile',
}}}}}, open(sys.argv[1], 'w'))
" "$SB/repo/ac_cloud-nix-on-droid/build.json" "$@"
}
decl_value() { sed -n "s/^$1='\(.*\)'$/\1/p" "$2"; }

fake_build_json alpha beta env
if python3 "$FAKE_RENDER" nix "$SB/one.sh" 2>"$SB/one.err"; then
    GOT="$(decl_value CLOUD_STORE_TOOLS "$SB/one.sh")"
    if [ "$GOT" = "alpha beta env" ]; then
        ok "the rendered declaration IS the app's own binaries list, in order ($GOT)"
    else
        bad "render-store.py rendered CLOUD_STORE_TOOLS='$GOT' from a binaries list of 'alpha beta env' — the projection does not follow the declaration"
    fi
    GOT="$(decl_value CLOUD_STORE_PREFIX "$SB/one.sh")"
    if [ "$GOT" = "/nix/var/nix/profiles/per-user/nix-on-droid/profile/bin" ]; then
        ok "targets are the DECLARED profile prefix, never a /nix/store hash (which moves with the pin)"
    else
        bad "CLOUD_STORE_PREFIX rendered as '$GOT', not the declared profile_link + /bin"
    fi
else
    bad "render-store.py could not render the nix terminal: $(cat "$SB/one.err")"
fi

# ── S2 — MUTATION: adding a tool is a DATA-ONLY edit ───────────────────────
# The whole point of #644. A name is appended to the binaries list — nothing
# else changes — and it must reach the declaration the engine loops over, with
# the engine and store.json byte-identical before and after.
ENGINE_BEFORE="$(cksum < "$SB/repo/ab_cloud-terminal-store/cloud-store")"
DECL_BEFORE="$(cksum < "$SB/repo/ab_cloud-terminal-store/store.json")"
fake_build_json alpha beta env gamma
if python3 "$FAKE_RENDER" nix "$SB/two.sh" 2>"$SB/two.err"; then
    GOT="$(decl_value CLOUD_STORE_TOOLS "$SB/two.sh")"
    ENGINE_AFTER="$(cksum < "$SB/repo/ab_cloud-terminal-store/cloud-store")"
    DECL_AFTER="$(cksum < "$SB/repo/ab_cloud-terminal-store/store.json")"
    if [ "$GOT" = "alpha beta env gamma" ] && [ "$ENGINE_BEFORE" = "$ENGINE_AFTER" ] && [ "$DECL_BEFORE" = "$DECL_AFTER" ]; then
        ok "a new tool reaches the store with ZERO code change — only the app's binaries list moved"
    else
        bad "adding 'gamma' to the binaries list rendered '$GOT' (engine changed: $([ "$ENGINE_BEFORE" = "$ENGINE_AFTER" ] && echo no || echo YES), store.json changed: $([ "$DECL_BEFORE" = "$DECL_AFTER" ] && echo no || echo YES)) — #644's data-only property does not hold"
    fi
else
    bad "render-store.py failed after a tool was appended to the binaries list: $(cat "$SB/two.err")"
fi

# ── S3 — MUTATION: a fixed link naming an undeclared tool must be refused ──
fake_build_json alpha beta
if python3 "$FAKE_RENDER" nix "$SB/three.sh" 2>"$SB/three.err"; then
    bad "render-store.py accepted a declaration whose fixed link /usr/bin/env wants 'env', which the tool list no longer declares — that renders a store that cannot ever satisfy #640"
else
    if grep -q "env" "$SB/three.err" && grep -q "/usr/bin/env" "$SB/three.err"; then
        ok "a fixed link naming an undeclared tool is refused, and the message names both ($(head -c 90 "$SB/three.err" | tr '\n' ' '))"
    else
        bad "the renderer refused the mismatch but its message names neither the link nor the tool: $(cat "$SB/three.err")"
    fi
fi

# ── the engine sandbox: search mode, fake binaries, no nix ─────────────────
mkdir -p "$SB/bin" "$SB/home"
for t in alpha beta; do
    printf '#!/bin/sh\necho %s\n' "$t" > "$SB/bin/$t"
    chmod 0755 "$SB/bin/$t"
done
cat > "$SB/declaration.sh" <<DECL
CLOUD_STORE_TERMINAL='sandbox'
CLOUD_STORE_ROOT='.cloud-store'
CLOUD_STORE_CURRENT='current'
CLOUD_STORE_GENERATIONS='generations'
CLOUD_STORE_BIN='bin'
CLOUD_STORE_ENGINE='cloud-store'
CLOUD_STORE_KEEP='5'
CLOUD_STORE_INSTALL_DIR='$SB'
CLOUD_STORE_MODE='search'
CLOUD_STORE_PREFIX=''
CLOUD_STORE_SEARCH='$SB/bin'
CLOUD_STORE_PACKAGE_MANAGER='none'
CLOUD_STORE_NIX_PROFILE=''
CLOUD_STORE_TOOLS='alpha beta'
CLOUD_STORE_FIXED_LINKS=''
DECL
CUR="$SB/home/.cloud-store/current"
store() { CLOUD_STORE_HOME="$SB/home" CLOUD_STORE_DECLARATION="$SB/declaration.sh" sh "$ENGINE" "$@"; }

# ── S4 — apply builds a generation and puts the engine itself in $HOME ─────
if store apply >"$SB/apply.out" 2>&1; then
    if [ -x "$CUR/bin/alpha" ] && [ -x "$CUR/bin/beta" ] && [ -x "$SB/home/.cloud-store/cloud-store" ]; then
        ok "apply materialised every declared link, and the engine itself lives in \$HOME (Diego's ask) and is linked into its own bin/"
    else
        bad "apply left an incomplete store: $(ls -l "$CUR/bin" 2>&1 | tr '\n' ' ')"
    fi
else
    bad "apply failed in a sandbox where every declared target exists: $(cat "$SB/apply.out")"
fi

# ── S5 — MUTATION: a broken TARGET must be named by verify ────────────────
rm -f "$SB/bin/alpha"
if store verify >"$SB/v1.out" 2>&1; then
    bad "verify passed with the target of 'alpha' deleted — this is exactly #640 going unnoticed, one layer up"
else
    if grep -q "alpha" "$SB/v1.out"; then
        ok "a vanished target FAILS verify and the message names the tool ($(grep -m1 alpha "$SB/v1.out" | cut -c1-96))"
    else
        bad "verify failed but never named 'alpha', so nobody can act on it: $(cat "$SB/v1.out")"
    fi
fi

# ── S6 — MUTATION: a MIS-AIMED link must be named, and repair must fix it ──
printf '#!/bin/sh\necho alpha\n' > "$SB/bin/alpha"; chmod 0755 "$SB/bin/alpha"
ln -sfn /nowhere/at/all "$CUR/bin/beta"
if store verify >"$SB/v2.out" 2>&1; then
    bad "verify passed with beta's link re-aimed at /nowhere/at/all"
else
    if grep -q "beta" "$SB/v2.out" && grep -q "/nowhere/at/all" "$SB/v2.out"; then
        ok "a mis-aimed link FAILS verify, named, with both where it points and what the declaration says"
    else
        bad "verify failed but its message does not name beta and where it wrongly points: $(cat "$SB/v2.out")"
    fi
fi
if store repair >"$SB/r1.out" 2>&1 && store verify >"$SB/v3.out" 2>&1; then
    ok "repair rebuilt from the declaration and verify is clean again — 'manage the links update and fix'"
else
    bad "repair did not fix a store it had just reported broken: $(cat "$SB/r1.out" "$SB/v3.out")"
fi

# ── S7 — rollback returns to the previous generation ──────────────────────
store apply >/dev/null 2>&1
BEFORE="$(readlink "$CUR")"
if store rollback >"$SB/rb.out" 2>&1; then
    AFTER="$(readlink "$CUR")"
    if [ "$AFTER" != "$BEFORE" ] && [ -d "$AFTER" ]; then
        ok "rollback switched ${BEFORE##*/} -> ${AFTER##*/} without rebuilding anything"
    else
        bad "rollback left the live generation at ${AFTER##*/} (was ${BEFORE##*/})"
    fi
else
    bad "rollback failed with more than one generation present: $(cat "$SB/rb.out")"
fi

# ── S8 — MUTATION: an interrupted apply must never half-switch ────────────
# The atomicity claim, tested rather than asserted: CLOUD_STORE_FAULT stops the
# engine at the named stage. Whatever it was doing, `current` must still be the
# generation that was live before, and that generation must still verify.
store apply >/dev/null 2>&1
LIVE_BEFORE="$(readlink "$CUR")"
for stage in populate rename switch; do
    CLOUD_STORE_HOME="$SB/home" CLOUD_STORE_DECLARATION="$SB/declaration.sh" \
        CLOUD_STORE_FAULT="$stage" sh "$ENGINE" apply >"$SB/f.$stage.out" 2>&1
    LIVE_NOW="$(readlink "$CUR")"
    if [ "$LIVE_NOW" != "$LIVE_BEFORE" ]; then
        bad "an apply interrupted at stage '$stage' moved the live generation from ${LIVE_BEFORE##*/} to ${LIVE_NOW##*/} — the switch is not atomic"
    elif store verify >/dev/null 2>&1; then
        ok "an apply interrupted at '$stage' left generation ${LIVE_BEFORE##*/} live and intact"
    else
        bad "an apply interrupted at '$stage' kept ${LIVE_BEFORE##*/} live but it no longer verifies"
    fi
done

# ── S9 — MUTATION: the store PATH+ensure line comes from the DECLARATION ──
# login-inner is patched with the line only when an install dir is declared; a
# line that appeared unconditionally would be a hardcoded path pretending to be
# data, which is the habit #644 replaces.
mkdir -p "$SB/probe/bin"
printf '#!/bin/sh\nexit 0\n' > "$SB/probe/bin/present"; chmod 0755 "$SB/probe/bin/present"
python3 - "$DIR/app/src/main/cpp/bake_default_packages.py" "$SB/probe" <<'PY' > "$SB/p.out" 2>&1
import importlib.util, sys
spec = importlib.util.spec_from_file_location("bake", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
li = ('#!/bin/sh\nset -eo pipefail\nusershell="/nix/store/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bash-5.2-p15/bin/bash"\n'
      '. "/data/data/cld.termux.nix/files/home/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh"\n'
      "exec /usr/bin/env bash  # otherwise it'll be a limited bash that came with Nix\n"
      'exec /usr/bin/env "$@"\n'
      'exec -a "-${usershell##*/}" "$usershell"\n')
args = ("cld.termux.nix", "usr/lib/cloud-agent-tools-init.sh",
        "nix/var/nix/profiles/per-user/nix-on-droid/profile", "fish")
withstore = m.patch_login_inner(li, *args, "usr/lib/cloud-store")
without = m.patch_login_inner(li, *args, "")
print("WITH_LINE=%s" % ('/usr/lib/cloud-store/login-init.sh' in withstore))
print("WITHOUT_LINE=%s" % ('cloud-store' in without))
print("GUARDED=%s" % ('if [ -r "/usr/lib/cloud-store/login-init.sh" ]' in withstore))
# Both verdicts against a generation built in the sandbox: a host-dependent
# probe would make this check mean different things on different runners.
print("MISSING_TOOL=%s" % m.profile_missing(sys.argv[2], ["git"]))
print("PRESENT_TOOL=%s" % m.profile_missing(sys.argv[2], ["present"]))
PY
if grep -q "WITH_LINE=True" "$SB/p.out" && grep -q "WITHOUT_LINE=False" "$SB/p.out" \
   && grep -q "GUARDED=True" "$SB/p.out"; then
    ok "login-inner gains a GUARDED store-init line only when an install dir is declared (a login never depends on the store existing)"
else
    bad "the login-inner store wiring is not declaration-driven: $(cat "$SB/p.out")"
fi
if grep -q "MISSING_TOOL=\['git'\]" "$SB/p.out" && grep -q "PRESENT_TOOL=\[\]" "$SB/p.out"; then
    ok "the bake gate names a declared tool the realized profile does not provide, and stays silent when it does"
else
    bad "profile_missing() does not discriminate a missing tool from a present one: $(cat "$SB/p.out")"
fi

# ── S10 — the real declarations both render, and the login literal agrees ──
for terminal in nix termux; do
    if python3 "$RENDERER" "$terminal" >"$SB/real.$terminal" 2>"$SB/real.$terminal.err"; then
        N="$(decl_value CLOUD_STORE_TOOLS "$SB/real.$terminal" | wc -w | tr -d ' ')"
        ok "the real $terminal declaration renders ($N tools) and login-init.sh's install-dir literal matches store.json"
    else
        bad "the real $terminal declaration does not render: $(cat "$SB/real.$terminal.err")"
    fi
done

fi

echo "── $fails failed ──"
[ "$fails" -eq 0 ]
