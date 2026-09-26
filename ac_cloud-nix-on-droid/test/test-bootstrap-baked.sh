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

# A5 — the first-run download is GONE from the installer, not merely unused.
if grep -q 'openStream()' "$INSTALLER"; then
    bad "TermuxInstaller still calls openStream() — the bootstrap is still fetched at runtime"
else
    ok "TermuxInstaller makes no runtime network call for the bootstrap"
fi
# grep -q writes nothing, so piping it into a filter tests the filter's view of
# an empty stream and is green whatever the file says. Filter FIRST, then match.
if grep -vE '^[[:space:]]*(\*|//|/\*)' "$INSTALLER" | grep -qE 'https?://[^"]*bootstrap'; then
    bad "TermuxInstaller still carries a live bootstrap url outside a comment"
else
    ok "no live bootstrap url left in TermuxInstaller"
fi

# A6 — it reads the baked asset, under exactly the declared name.
JAVA_ASSET="$(sed -n 's/.*BOOTSTRAP_ASSET_NAME = "\([^"]*\)".*/\1/p' "$INSTALLER" | head -1)"
if grep -q 'getAssets().open(BOOTSTRAP_ASSET_NAME)' "$INSTALLER" && [ "$JAVA_ASSET" = "$ASSET" ]; then
    ok "installer reads assets/$ASSET, the name build.json declares"
else
    bad "installer asset '$JAVA_ASSET' does not match build.json asset_name '$ASSET', or it does not read from assets at all"
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

echo "── $fails failed ──"
[ "$fails" -eq 0 ]
