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

# ── #612 — All-Files-Access is asked for, and its absence is legible ──────
# MANAGE_EXTERNAL_STORAGE is declared in the manifest but is a special access that
# is NOT granted at install and cannot be self-granted, so TermuxActivity must send
# the user to the toggle on launch and the baked bin/login must not mount an empty
# tree in silence. Both are greps over source: delete either and this goes red.
ACTIVITY="$DIR/app/src/main/java/com/termux/app/TermuxActivity.java"
if grep -q 'Environment.isExternalStorageManager()' "$ACTIVITY"; then
    ok "TermuxActivity checks isExternalStorageManager() on launch"
else
    bad "TermuxActivity has no isExternalStorageManager() first-run check — the terminal never asks for All-Files-Access (#612)"
fi
if grep -q 'requestManageStorageExternalPermission' "$ACTIVITY"; then
    ok "TermuxActivity opens this app's All-Files-Access settings screen"
else
    bad "TermuxActivity never requests MANAGE_EXTERNAL_STORAGE — the check leads nowhere (#612)"
fi
if grep -q 'All-Files-Access' "$BAKE_PY"; then
    ok "bin/login prints a legible notice when the shared store is not readable"
else
    bad "bin/login binds an unreadable shared store silently — no All-Files-Access notice (#612)"
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

# ── #638 — the baked login-inner must BOOT on a fresh install ─────────────
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
# This section runs the REAL patcher over a fixture shaped like the real
# generated file, then EXECUTES the result with /usr/bin/env sandboxed out of
# existence — which is the fresh-install condition — and asserts a shell is
# reached. It is mutation-proved against the pre-#638 shape.
echo "── #638 the baked usr/lib/login-inner boots with no nix profile and no /usr/bin/env ──"
D1_WORKDIR="$(mktemp -d)"
D1_RESULT="$(python3 - "$DIR/app/src/main/cpp" "$D1_WORKDIR" "$TO" "$FALLBACK" <<'PYEOF' 2>&1 | sed -n 's/^RESULT://p'
import os, subprocess, sys

cpp_dir, work, app_id, fallback = sys.argv[1:5]
sys.path.insert(0, cpp_dir)
import bake_default_packages as bake

# Shaped like the real generated usr/lib/login-inner: the welcome, the
# UNINTIALISED-gated wizard, the trailing session-init source, the
# unconditional env exec, and the usershell block that exec never reaches.
fixture = f'''# This file is generated by Nix-on-Droid. DO NOT EDIT.

set -eo pipefail

if [ "$#" -eq 0 ]; then  # if script is called from within Nix-on-Droid app
  echo "Welcome to Nix-on-Droid!"
fi

if [ -e /etc/UNINTIALISED ]; then
  echo "Setting default user profile..."
  . "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh"
fi

. "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh"

{bake.ENV_EXEC}


usershell="/usr/lib/fake-usershell"
if [ "$#" -gt 0 ]; then  # if script is not called from within Nix-on-Droid app
  {bake.ENV_EXEC_ARGV}
elif [ -x "$usershell" ]; then
  exec -a "-${{usershell##*/}}" "$usershell"
else
  echo "Cannot execute shell '/usr/lib/fake-usershell', falling back to bash"
  exec -l bash
fi
'''

patched = bake.patch_login_inner(fixture, app_id, fallback)
# Un-apply exactly this fix to get the shape that shipped. A no-op replace here
# means the guard is absent, the mutant equals the patched file, and the runs
# below cannot tell them apart — which the caller reports as a dead assertion.
mutant = patched.replace(f"if [ -x /usr/bin/env ]; then\n  {bake.ENV_EXEC}\nfi", bake.ENV_EXEC)
mutant = mutant.replace('exec "$@"', bake.ENV_EXEC_ARGV)

# Sandbox: every absolute /usr/ path in the script moves under $work, so
# /usr/bin/env is genuinely absent (as on a fresh install) without touching the
# runner's real filesystem.
os.makedirs(f"{work}/usr/bin", exist_ok=True)
os.makedirs(os.path.dirname(f"{work}/{fallback}"), exist_ok=True)
open(f"{work}/{fallback}", "w").write('export PATH="/nonexistent/bin:$PATH"\n')
shell = f"{work}/usr/lib/fake-usershell"
open(shell, "w").write('#!/bin/sh\necho SHELL_REACHED\n')
os.chmod(shell, 0o755)

def run(text, name, args, env_present):
    path = f"{work}/{name}"
    open(path, "w").write(text.replace("/usr/", f"{work}/usr/"))
    envbin = f"{work}/usr/bin/env"
    if env_present:
        open(envbin, "w").write('#!/bin/sh\necho ENV_REACHED\n')
        os.chmod(envbin, 0o755)
    elif os.path.exists(envbin):
        os.remove(envbin)
    p = subprocess.run(["bash", path, *args], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr

why = []
rc, out = run(patched, "patched", [], False)
if rc != 0 or "SHELL_REACHED" not in out:
    why.append(f"fresh-install boot rc={rc} out={out.strip()!r}")
rc, out = run(patched, "patched-argv", ["echo", "ARGV_REACHED"], False)
if rc != 0 or "ARGV_REACHED" not in out:
    why.append(f"argv path rc={rc} out={out.strip()!r}")
rc, out = run(patched, "patched-envok", [], True)
if rc != 0 or "ENV_REACHED" not in out:
    why.append(f"env-present path rc={rc} out={out.strip()!r}")
print("RESULT:" + ("OK" if not why else "FAIL " + "; ".join(why)))

mwhy = []
rc, out = run(mutant, "mutant", [], False)
if rc != 127 or "No such file or directory" not in out:
    mwhy.append(f"no-arg rc={rc} out={out.strip()!r}")
rc, out = run(mutant, "mutant-argv", ["echo", "ARGV_REACHED"], False)
if rc != 127 or "No such file or directory" not in out:
    mwhy.append(f"argv rc={rc} out={out.strip()!r}")
print("RESULT:MUT " + ("OK" if not mwhy else "SURVIVED " + "; ".join(mwhy)))
PYEOF
)"
case "$(echo "$D1_RESULT" | sed -n 1p)" in
    OK) ok "the patched usr/lib/login-inner reaches a shell with no nix profile and no /usr/bin/env, still honours /usr/bin/env when it exists, and still execs a caller's argv" ;;
    *)  bad "the baked usr/lib/login-inner does not boot on a fresh install: $(echo "$D1_RESULT" | sed -n 1p)" ;;
esac
case "$(echo "$D1_RESULT" | sed -n 2p)" in
    "MUT OK") ok "mutation proved: un-applying #638's env guard makes that assertion die with exit 127 / 'No such file or directory' — exactly what shipped" ;;
    *)        bad "MUTATION SURVIVED: the pre-#638 login-inner still passed the boot assertion, so it proves nothing: ${D1_RESULT#*MUT }" ;;
esac
rm -rf "$D1_WORKDIR"

# And the prose #639 removed must not come back: the shell notice states a fact,
# it does not walk the user through a Settings tree the app already deep-links to.
if grep -qE 'Settings *(▸|→|>)' "$BAKE_PY"; then
    bad "bake_default_packages.py prints a 'Settings ▸ …' breadcrumb again — TermuxActivity deep-links to the toggle, so describing the route is the #639 defect"
else
    ok "no 'Settings ▸ …' breadcrumb in the baked shell notice (#639)"
fi

echo "── $fails failed ──"
[ "$fails" -eq 0 ]
