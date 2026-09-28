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

# A5 — the UNVERIFIED first-run download is GONE, not merely unused. #618 gives
#      the installer a fetch back, and the difference from defect (1) is the
#      whole point: no editable dialog, no third-party host, and the bytes are
#      refused unless they hash to the digest inside this signed APK. That gate
#      is asserted (and mutation-proved) in the #618 section below.
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

# A6 — it names the runtime under exactly the declared name, and #618: it
#      EXTRACTS from the fetched file rather than from assets/, because the
#      zip is not in the APK any more.
JAVA_ASSET="$(sed -n 's/.*BOOTSTRAP_ASSET_NAME = "\([^"]*\)".*/\1/p' "$INSTALLER" | head -1)"
if [ "$JAVA_ASSET" = "$ASSET" ]; then
    ok "installer names the runtime $ASSET, the name build.json declares"
else
    bad "installer asset '$JAVA_ASSET' does not match build.json asset_name '$ASSET'"
fi
if grep -q 'getAssets().open(BOOTSTRAP_ASSET_NAME)' "$INSTALLER"; then
    bad "installer still unpacks the zip out of assets/ — the APK would have to carry 400 MB (#618)"
else
    ok "installer does not read the zip from assets/ (#618: it is fetched)"
fi

# A7 — something actually PUTS it there. An asset nobody bakes is an APK that
#      installs, launches and then cannot find its own runtime.
if grep -q 'task bakeBootstrap' "$GRADLE" \
   && grep -q 'assets.srcDir bootstrapAssetsDir' "$GRADLE" \
   && grep -q 'merge.*Assets/.*dependsOn bakeBootstrap' "$GRADLE"; then
    ok "app/build.gradle bakes the rootfs and wires it ahead of asset merging"
else
    bad "app/build.gradle does not define bakeBootstrap, register its assets dir, and run it before merge*Assets"
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

# ── #605 — an APK update alone does not fix an already-extracted rootfs
#           unless something re-extracts it: TermuxInstaller must gate its
#           "prefix already exists, do nothing" shortcut on comparing the
#           baked bootstrap's version against what was actually installed.
BOOTSTRAP_VERSION_ASSET="$(sed -n 's/.*BOOTSTRAP_VERSION_ASSET_NAME = "\([^"]*\)".*/\1/p' "$INSTALLER" | head -1)"
if [ "$BOOTSTRAP_VERSION_ASSET" = "${ASSET}.sha256" ] \
   && grep -q 'readBakedBootstrapVersion' "$INSTALLER" \
   && grep -q 'readInstalledBootstrapVersion' "$INSTALLER" \
   && grep -q 'bootstrapUpToDate' "$INSTALLER"; then
    ok "TermuxInstaller gates its 'prefix already exists' shortcut on the baked bootstrap version, not just non-emptiness"
else
    bad "TermuxInstaller has no bootstrap-version gate — an APK update that fixes bootstrap.zip would never reach a phone with an already-extracted, stale \$PREFIX"
fi

DIGEST_ASSET="$(q forks.nixdroid.bootstrap.artifact.digest_asset)"
URL_ASSET="$(q forks.nixdroid.bootstrap.artifact.url_asset)"
if [ "$DIGEST_ASSET" = "${ASSET}.sha256" ] && grep -q 'art.digest_asset' "$GRADLE"; then
    ok "app/build.gradle bakes ${ASSET}.sha256 for the installer to compare against"
else
    bad "app/build.gradle does not write ${ASSET}.sha256 — the installer's version gate has nothing to read"
fi


# ── #618 — the 400 MB runtime is PUBLISHED beside the APK, not inside it ───
# Both terminals baked their root filesystem into the APK, so every one-line app
# fix was a 400 MB update that every phone re-downloaded in full. The zip now
# goes to this app's own rolling release and the APK keeps two small files: the
# digest the fetch is gated on and the url it comes from. Two things have to be
# true together, and half of either is worse than neither: the bytes must be OUT
# of the APK, and the fetch that replaces them must be REFUSED unless it hashes
# to the digest travelling inside the signed APK.
echo "── #618 the runtime is fetched, not bundled, and the fetch is sha256-gated ──"

ART_REPO="$(q forks.nixdroid.bootstrap.artifact.repo)"
ART_TAG="$(q forks.nixdroid.bootstrap.artifact.tag)"
ART_ASSET="$(q forks.nixdroid.bootstrap.artifact.asset)"
ART_URL="$(q forks.nixdroid.bootstrap.artifact.url)"
PUBLISHER_REL="$(q forks.nixdroid.bootstrap.artifact.publisher)"
if [ -n "$ART_REPO" ] && [ -n "$ART_TAG" ] && [ -n "$ART_ASSET" ] && [ -n "$ART_URL" ] \
   && [ -n "$DIGEST_ASSET" ] && [ -n "$URL_ASSET" ] && [ -n "$PUBLISHER_REL" ]; then
    ok "build.json declares the ONE artifact block (release $ART_TAG on $ART_REPO, asset $ART_ASSET)"
else
    bad "forks.nixdroid.bootstrap.artifact is incomplete — the zip could only ship inside the APK (#618)"
fi

# The asset name must address its content AND its ABI, or a pin bump would keep
# the old name and every phone would stay on the old runtime forever while CI
# reported a new build.
for token in '{id}' '{abi}'; do
    case "$ART_ASSET" in
        *"$token"*) ok "artifact.asset carries $token" ;;
        *) bad "artifact.asset '$ART_ASSET' has no $token — the name would not address its content (#618)" ;;
    esac
done
for token in '{repo}' '{tag}' '{asset}'; do
    case "$ART_URL" in
        *"$token"*) ok "artifact.url derives $token" ;;
        *) bad "artifact.url '$ART_URL' has no $token — the url must be derived, never written out (#618)" ;;
    esac
done
MISSING_IDENTITY="$(python3 -c "
import json, os
art = json.load(open('$BUILD_JSON'))['forks']['nixdroid']['bootstrap']['artifact']
print(','.join(p for p in art['identity_files'] if not os.path.isfile(os.path.join('$DIR', p))))
" 2>/dev/null)"
[ -z "$MISSING_IDENTITY" ] \
    && ok "every artifact.identity_files entry exists" \
    || bad "artifact.identity_files names files that do not exist: $MISSING_IDENTITY"
python3 -c "
import json, sys
art = json.load(open('$BUILD_JSON'))['forks']['nixdroid']['bootstrap']['artifact']
sys.exit(0 if any(p.endswith('build.json') for p in art['identity_files']) else 1)
" 2>/dev/null \
    && ok "build.json is part of the artifact identity — every pin in it moves the runtime" \
    || bad "artifact.identity_files does not include build.json: a pin bump would not rename the asset (#618)"

PUBLISHER="$DIR/$PUBLISHER_REL"
[ -f "$PUBLISHER" ] && ok "the publish step exists: $PUBLISHER_REL" \
                    || bad "$PUBLISHER_REL is missing — nothing publishes the zip, so it could only ship in the APK"
grep -q 'already published' "$PUBLISHER" 2>/dev/null \
    && ok "an already-published asset keeps its hosted bytes (a rebuild cannot move them under a shipped APK)" \
    || bad "$PUBLISHER_REL re-uploads the same asset name — phones would be told a digest the host no longer serves"

# THE line that shrinks the APK. Without it the fetch path could exist and the
# APK still carry the zip: the defect with a fix bolted next to it.
if grep -q 'the APK would carry the bootstrap it fetches' "$GRADLE"; then
    ok "bakeBootstrap removes the zip from assets/ and fails loudly if it cannot"
else
    bad "nothing removes the baked zip from assets/ — the APK would still be 400 MB (#618)"
fi
grep -q 'bootstrapArtifactAsset' "$GRADLE" \
    && ok "gradle names the published asset from the declaration" \
    || bad "app/build.gradle does not derive the asset name from artifact.asset"

# The installer side: url from an asset (never a literal), digest compared, and
# the cache reused so an interrupted extraction does not cost another 400 MB.
if grep -n 'https\?://' "$INSTALLER" | grep -vE '^[0-9]+:[[:space:]]*(\*|//|/\*)' | grep -q .; then
    bad "TermuxInstaller hardcodes a url — it must read $URL_ASSET, written from the declaration (#618)"
else
    ok "TermuxInstaller hardcodes no url (it reads $URL_ASSET)"
fi
grep -q 'CACHED_BOOTSTRAP_FILE' "$INSTALLER" \
    && ok "the fetched zip is cached, so a retry does not refetch 400 MB" \
    || bad "there is no fetch cache — an interrupted extraction would refetch the whole runtime"

# MUTATION PROOF. The assertion below is the one that matters, so it is run
# twice: once against the real file, and once against a copy with the digest
# comparison deleted. If the copy still passes, the assertion checks nothing and
# an unverified download could land without this tester noticing.
sha_gated() {
    grep -q 'DigestInputStream' "$1" && grep -q 'want.equals(got)' "$1" \
        && grep -q 'not the " + want' "$1"
}
if sha_gated "$INSTALLER"; then
    ok "the fetch is gated on the sha256 baked into this signed APK"
else
    bad "the fetched bootstrap is not compared to the baked digest — unread bytes would be extracted and marked executable (#618)"
fi
MUT="$(mktemp)"
grep -v 'want.equals(got)' "$INSTALLER" > "$MUT"
if sha_gated "$MUT"; then
    bad "MUTATION SURVIVED: deleting the digest comparison left the sha256 assertion green — it proves nothing"
else
    ok "mutation proved: deleting the digest comparison makes the assertion above go red"
fi
rm -f "$MUT"

echo "── $fails failed ──"
[ "$fails" -eq 0 ]
