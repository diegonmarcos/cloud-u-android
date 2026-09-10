#!/usr/bin/env bash
# Cloud Libs — Build Dispatcher
#
# One APK per constellation library module. Mirrors ab_cloud-libs-shared/keyboard-engines, but
# that repo produces a single bundle APK and this one produces N, so every step
# here loops over the SAME scan that settings.gradle uses (build.json::lib_apks).
# Nothing in this file names a module.
set -euo pipefail

# Repo identity for GHCR package linkage. GHCR binds a package to whichever repo
# first pushed it, and a workflow's GITHUB_TOKEN only grants packages bound to
# its OWN repo — after the 2026-08 android split every push was denied
# write_package because the packages were still linked to cloud-infra-desktop. This is
# the annotation GHCR reads to (re)link a package, so the link follows whichever
# repo actually ships it. Never hardcoded: CI supplies GITHUB_REPOSITORY, local
# runs fall back to the origin remote.
# A brand-new GHCR package is created PRIVATE by GitHub regardless of the repo,
# so the first push of any new artifact 401s for every unauthenticated consumer
# (the constellation store included). image.source LINKS the package to the repo
# but does NOT make it inherit visibility, and there is no REST endpoint to flip
# it for a USER-owned package (PATCH /user/packages/... returns 404) - it is a
# one-time click in the package settings UI. So this cannot self-heal; it warns
# LOUDLY instead, because the failure mode otherwise is a silent 401 in the store.
_ghcr_publish() {
  # THE PACKAGE MUST MATCH THE REPO. A public repo whose APK is private is not
  # a warning, it is a broken release: the constellation store pulls
  # unauthenticated and gets 401, which reads to a user as "the app is gone".
  #
  # GitHub creates every new USER-owned package private regardless of the repo,
  # links it via image.source without inheriting anything, and exposes no REST
  # endpoint to flip it (PATCH /user/packages/... is 404 even with
  # write:packages). So this cannot self-heal. What it CAN do is refuse to
  # report success: the mismatch fails the build, with the one URL that fixes
  # it, instead of leaving a 401 to be discovered by whoever tries to install.
  local image="$1"
  command -v gh >/dev/null 2>&1 || return 0
  local repo_vis pkg_vis want
  # An artifact may be DELIBERATELY private in a public repo — a fork whose
  # distribution is not ours to make, something not ready to be seen. That is a
  # decision this check must respect, not override: release.ghcr.visibility
  # states it, and where it is stated it wins over the repo. Without this the
  # check would push every exception toward being published, which is a worse
  # failure than the 401 it exists to prevent.
  # _bj, not _release_var: that helper is defined in every ac_cloud-*/build.sh
  # and this repo has no copy, so the call died with "command not found" —
  # after pushing exactly one lib, leaving the other 33 unpublished and their
  # Constellation entries answering 401. _bj is this file's own reader.
  want="$(_bj ".get('release',{}).get('ghcr',{}).get('visibility','')")"
  if [ -n "$want" ] && [ "$want" != "null" ]; then
    repo_vis="$want"
  else
    repo_vis="$(gh repo view "${GITHUB_REPOSITORY:-$(_ghcr_source | sed 's|.*github.com/||')}" \
                  --json visibility --jq .visibility 2>/dev/null | tr 'A-Z' 'a-z')"
  fi
  [ -z "$repo_vis" ] && return 0
  pkg_vis="$(gh api "/user/packages/container/${image}" --jq .visibility 2>/dev/null)" || return 0
  [ "$pkg_vis" = "$repo_vis" ] && return 0
  errlog "GHCR visibility does not follow the repo."
  errlog "  repo    ${GITHUB_REPOSITORY:-$(_ghcr_source)} is ${repo_vis}"
  errlog "  package ${image} is ${pkg_vis} -> unauthenticated pulls 401"
  errlog "  GitHub creates user-owned packages private and offers no API to change it."
  errlog "  Fix once: https://github.com/users/diegonmarcos/packages/container/${image}/settings"
  errlog "  The GH Release asset is unaffected - it IS the repo, so it already follows."
  # Record and KEEP GOING. Returning non-zero here aborted the push loop under
  # `bash -e`, so the first private package left the other 33 libs unpushed —
  # and an unpushed package answers 401, which is the exact failure this check
  # exists to prevent. Every APK still publishes; the step fails at the end
  # with the full list, so one visibility flip is one fix, not thirty-three.
  GHCR_PRIVATE="${GHCR_PRIVATE}${GHCR_PRIVATE:+ }${image}"
  return 0
}

_ghcr_source() {
  if [ -n "${GITHUB_REPOSITORY:-}" ]; then
    printf '%s/%s\n' "${GITHUB_SERVER_URL:-https://github.com}" "$GITHUB_REPOSITORY"
  else
    git remote get-url origin 2>/dev/null \
      | sed -e 's|^git@\([^:]*\):|https://\1/|' -e 's|\.git$||'
  fi
}


SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CMD="${1:-help}"

log()    { printf "[%s] %s\n" "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf "\033[0;31m[%s] ERROR: %s\033[0m\n" "$(date '+%H:%M:%S')" "$1" >&2; }

in_nix() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then "$@"
  else
    command -v nix >/dev/null 2>&1 || { errlog "nix not on PATH; set BYPASS_NIX=1"; exit 1; }
    nix develop "$SCRIPT_DIR" --command "$@"
  fi
}

# libs:firewall consumes the firestack netstack aar. This repo ships it as
# Cloud-Lib-Firewall.apk, so it needs the aar just as much as the superapp does.
#
# FETCHED, NOT BUILT, for the same reason the superapp fetches it: an aar built
# inline is an aar whose failure takes its consumer down with it, and on
# 2026-09-09 that is precisely what happened to the SuperApp publish. Both
# consumers now pull the same pinned artifact
# (ab_cloud-libs-shared/build.json::firestack.artifact), so neither can be
# stopped by the state of the Go source. Wrapped in OUR in_nix because CI runs
# this repo with BYPASS_NIX=1 and the SDK from setup-android.
_ensure_firestack() { in_nix bash "$SCRIPT_DIR/../libs/firewall/fetch-firestack.sh"; }

_gradle() { _ensure_firestack; in_nix gradle --no-daemon -p "$SCRIPT_DIR" "$@"; }

_bj() { python3 -c "import json,sys;print(json.load(open('$SCRIPT_DIR/build.json'))$1)" 2>/dev/null; }

# ── the publish gate, per library APK ──────────────────────────────
# This workflow is the one that publishes MANY assets from one job, and that is
# why it went ungated for so long: cloud-android-publish-gate.sh resolves a
# single asset name out of build.json, this build.json declares none, and the
# gate fails open when it cannot name an asset. So every push touching ANY
# module under the scan root republished all 24 library APKs.
#
# On 2026-09-09 a fix to libs/updater did exactly that: 23 of the 24 APKs it
# put on the release were rebuilds of source that had not moved, and the
# constellation store offered every one of them to the phone as an update.
#
# The gate is per ASSET here, and each asset's inputs are its own module plus
# the modules that module compiles against - so a touch to libs/updater
# republishes Cloud-Lib-Updater.apk and Cloud-Lib-Appstore.apk, and leaves the
# other 22 alone.
ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
GATE="$ROOT/1_cicd/dist/scripts/cloud-android-publish-gate.sh"
IDENTITY_ENGINE="$ROOT/1_cicd/dist/scripts/cloud-android-source-identity.sh"
# The app directory as the gate names it - derived from where this file sits,
# never spelled out, so moving the harness cannot leave a stale literal behind.
GATE_APP="${SCRIPT_DIR#"$ROOT"/}"

# The workflow's whole trigger list, resolved once. Every module's input set is
# carved out of THIS list rather than derived independently, which is what
# keeps the union of the 24 sets equal to the list: a path the workflow watches
# is either module-scoped below or shared by all of them, never dropped.
_trigger_paths() { sh "$IDENTITY_ENGINE" paths "$GATE_APP"; }

# Input paths for ONE library APK, repo-root-relative, one per line.
#   $1 module name    $2 file holding _trigger_paths output
_module_paths() {
  python3 - "$SCRIPT_DIR" "$ROOT" "$1" "$2" <<'MODULEPATHS'
import json, os, re, sys

script_dir, root, target, trigger_file = sys.argv[1:5]
config = json.load(open(os.path.join(script_dir, 'build.json')))
lib_config = config['lib_apks']
scan = lib_config['scan']
scan_roots = [os.path.normpath(os.path.join(script_dir, r))
              for r in (scan if isinstance(scan, list) else [scan])]

# Every directory holding a build.gradle is a module. The lib_apks exclude list
# is deliberately NOT applied: an excluded module ships no APK of its own but
# can still be compiled into one that does, and dropping it here would hide a
# real change from the APK that contains it.
modules = {}
for scan_root in scan_roots:
    for entry in sorted(os.listdir(scan_root)):
        if os.path.isfile(os.path.join(scan_root, entry, 'build.gradle')):
            modules[entry] = os.path.join(scan_root, entry)

# The module graph exactly as each build.gradle declares it. Parsed from the
# declaration rather than restated as data, so adding a dependency widens that
# APK's identity with no edit here and no list to keep in step.
dependencies = {}
for name, path in modules.items():
    text = open(os.path.join(path, 'build.gradle')).read()
    dependencies[name] = {d for d in
                          re.findall(r"""project\(['"]:libs:([A-Za-z0-9_.-]+)""", text)
                          if d in modules}

closure, pending = set(), [target]
while pending:
    current = pending.pop()
    if current in closure or current not in modules:
        continue
    closure.add(current)
    pending.extend(dependencies[current])

if not closure:
    sys.exit("FATAL: '%s' is not a module under %s" % (target, scan_roots))

paths = {os.path.relpath(modules[name], root) for name in closure}

# Anything under a scan root that is NOT a module directory belongs to no
# single APK and can change any of them: the prebuilt aar repos, the shared
# module test script, upstream trees compiled into another app. Hashed into
# EVERY module's identity, so a change there republishes the whole set.
# Deliberately over-broad - republishing too much costs bandwidth, while a
# missed publish leaves a phone on a stale APK and says nothing.
for scan_root in scan_roots:
    for entry in sorted(os.listdir(scan_root)):
        if entry not in modules:
            paths.add(os.path.relpath(os.path.join(scan_root, entry), root))

# Everything the workflow watches that is not inside a scan root is harness -
# the vendored build.sh, the module map, the workflow itself - and belongs to
# every APK this job builds.
for line in open(trigger_file):
    entry = line.strip()
    if not entry:
        continue
    absolute = os.path.normpath(os.path.join(root, entry))
    if any(absolute == r or absolute.startswith(r + os.sep) for r in scan_roots):
        continue
    paths.add(entry)

print('\n'.join(sorted(paths)))
MODULEPATHS
}

# True when this asset's inputs are unchanged since the bytes already on the
# release were built. Fail-open by construction: every error path returns
# false, so an unreadable release, a missing engine or an unparsable module
# graph publishes rather than skips.
#   $1 module   $2 asset file name   $3 dir holding the resolved path lists
_gate_skip() {
  local module="$1" asset="$2" paths_dir="$3" verdict
  [ -f "$GATE" ] || return 1
  _module_paths "$module" "$paths_dir/.triggers" > "$paths_dir/$module" 2>/dev/null || return 1
  # GITHUB_OUTPUT is cleared for the call: the gate appends `skip=` to it, and
  # 24 of those would collide into one meaningless step output for the job.
  verdict="$(GITHUB_OUTPUT= sh "$GATE" check "$GATE_APP" \
               --asset "$asset" --paths-from "$paths_dir/$module" 2>&1)" || return 1
  printf '%s\n' "$verdict" | grep -qx '\[publish-gate\] skip=true'
}

# The one scan, shared by settings.gradle / app/build.gradle / regen.sh.
# Emits: "<module>|<flavorName>|<Asset-Name.apk>|<ghcr-image>" per line.
_libs() {
  python3 - "$SCRIPT_DIR" <<'PY'
import json, os, sys
root = sys.argv[1]
cfg  = json.load(open(os.path.join(root, 'build.json')))
lc   = cfg['lib_apks']
# scan is a LIST of roots - library modules live beside the app that grew them.
roots = lc['scan'] if isinstance(lc['scan'], list) else [lc['scan']]
roots = [os.path.normpath(os.path.join(root, r)) for r in roots]
excl = set(lc.get('exclude', {}))
img  = cfg['release']['ghcr']['image_prefix']
seen = {}
for scan in roots:
    if not os.path.isdir(scan):
        sys.exit(f"FATAL: lib_apks.scan names a path that is not a directory: {scan}")
    for d in os.listdir(scan):
        if not os.path.isfile(os.path.join(scan, d, 'build.gradle')) or d in excl:
            continue
        # The module name is the gradle path AND the applicationId suffix, so a
        # collision across roots would give two libraries the same APK identity.
        if d in seen and seen[d] != scan:
            sys.exit(f"FATAL: module '{d}' provided by two scan roots: {seen[d]} and {scan}")
        seen[d] = scan
names = sorted(seen)
if not names:
    sys.exit(f"FATAL: no library modules under {roots}")
for n in names:
    parts  = n.split('-')
    flavor = parts[0] + ''.join(p.capitalize() for p in parts[1:])
    asset  = lc['asset_prefix'] + '-'.join(p.capitalize() for p in parts) + '.apk'
    print(f"{n}|{flavor}|{asset}|{img}{n}")
PY
}

# ONE shared Cloud-constellation signing key — identical to every other app in
# the constellation. NO fallback: a debug-signed APK is a fresh random key per CI
# run, which both breaks updates and silently drops the APK out of the signature-
# level CONSTELLATION_DATA grant.
_resolve_signing() {
  if [ -n "${GITHUB_ACTIONS:-}${CI:-}" ] \
     && [ -n "${ANDROID_KEYSTORE_FILE:-}" ] && [ -f "${ANDROID_KEYSTORE_FILE}" ] && [ -n "${ANDROID_KEY_ALIAS:-}" ]; then
    log "signing: using pre-set ANDROID_KEYSTORE_* (CI secret delivery)"; return 0
  fi
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  ks_rel="$(_bj "['signing']['vault_keystore']")"
  sec_rel="$(_bj "['signing']['vault_secrets']")"
  if [ -z "$ks_rel" ] || [ -z "$sec_rel" ]; then
    errlog "FATAL signing: build.json::signing.vault_keystore/.vault_secrets are empty."
    exit 1
  fi
  vault="${VAULT_DIR:-$HOME/git/cloud-vault}"
  ks="$vault/$ks_rel"
  [ -f "$ks" ] || { errlog "FATAL signing: shared keystore missing at $ks (check out vault / set VAULT_DIR). No fallback."; exit 1; }
  command -v sops >/dev/null 2>&1 || { errlog "FATAL signing: sops not on PATH; cannot decrypt the shared key. Refusing to build."; exit 1; }
  store_pw="$(sops --config /dev/null -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops --config /dev/null -d --extract '["key_password"]'      "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops  --config /dev/null -d --extract '["key_alias"]'        "$vault/$sec_rel" 2>/dev/null || true)"
  if [ -z "$store_pw" ] || [ -z "$alias_" ]; then
    errlog "FATAL signing: cannot decrypt $sec_rel (need SOPS_AGE_KEY). Refusing to fall back to any other key."
    exit 1
  fi
  export ANDROID_KEYSTORE_FILE="$ks"
  export ANDROID_KEYSTORE_PASSWORD="$store_pw"
  export ANDROID_KEY_PASSWORD="$key_pw"
  export ANDROID_KEY_ALIAS="$alias_"
  log "signing: ONE shared constellation key (alias $alias_) from vault/$ks_rel"
}

_enforce_signature() {
  local apk="$1" bt zipalign apksigner
  [ -f "$apk" ] || { errlog "sign-enforce: missing APK $apk"; exit 1; }
  bt="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  zipalign="$bt/zipalign"; apksigner="$bt/apksigner"
  [ -x "$apksigner" ] || { errlog "sign-enforce: apksigner missing (bt=$bt)"; exit 1; }
  "$zipalign" -f -p 4 "$apk" "${apk}.aln" 2>/dev/null && mv -f "${apk}.aln" "$apk" || rm -f "${apk}.aln"
  "$apksigner" sign --ks "$ANDROID_KEYSTORE_FILE" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
    "$apk" || { errlog "sign-enforce: re-sign with shared key failed for $apk"; exit 1; }
  rm -f "${apk}.idsig"
  "$apksigner" verify "$apk" >/dev/null 2>&1 \
    || { errlog "sign-enforce: FATAL $(basename "$apk") not validly signed after shared-key re-sign - refusing"; exit 1; }
  log "sign-enforce: OK $(basename "$apk")"
}

# $1 = debug|release. Assembles every flavor in ONE gradle invocation (the
# configuration phase dominates here, so 24 separate invocations would cost
# minutes for nothing) and collects each flavor's APK under its asset name.
_assemble() {
  local variant="$1" tasks=() n flavor asset image cap
  mkdir -p "$DIST_DIR"
  while IFS='|' read -r n flavor asset image; do
    cap="$(printf '%s' "${flavor:0:1}" | tr '[:lower:]' '[:upper:]')${flavor:1}"
    tasks+=(":app:assemble${cap}$( [ "$variant" = release ] && echo Release || echo Debug )")
  done < <(_libs)
  log "Assembling ${#tasks[@]} library APKs ($variant)…"
  _gradle "${tasks[@]}"

  local out count=0
  while IFS='|' read -r n flavor asset image; do
    out="$(find "$SCRIPT_DIR/app/build/outputs/apk/$flavor/$variant" -name '*.apk' -print -quit 2>/dev/null || true)"
    [ -n "$out" ] || { errlog "no APK produced for module $n (flavor $flavor, $variant)"; exit 1; }
    cp -f "$out" "$DIST_DIR/$asset"
    _enforce_signature "$DIST_DIR/$asset"
    count=$((count + 1))
  done < <(_libs)
  log "$count library APKs → $DIST_DIR/"
}

case "$CMD" in
  build)
    log "Building Cloud Libs APKs (debug)…"
    _resolve_signing
    _assemble debug
    ;;
  release)
    log "Building Cloud Libs APKs (release)…"
    _resolve_signing
    _assemble release
    ;;
  clean)
    _gradle clean
    rm -rf "$DIST_DIR"
    ;;
  oras-push)
    log "Pushing library APKs to GHCR via ORAS…"
    GHCR_PRIVATE=""
    SHA="${GITHUB_SHA:-$(git -C "$SCRIPT_DIR" rev-parse HEAD)}"
    SHORT="${SHA:0:8}"
    reg="$(_bj "['release']['ghcr']['registry']")/$(_bj "['release']['ghcr']['namespace']")"
    mt="$(_bj "['release']['ghcr']['media_type']")"
    PATHS_DIR="$(mktemp -d)"
    trap 'rm -rf "$PATHS_DIR"' EXIT
    _trigger_paths > "$PATHS_DIR/.triggers"
    while IFS='|' read -r n flavor asset image; do
      # The SAME verdict the release publish uses, against the same sidecar, so
      # GHCR and the release cannot drift into disagreeing about which build a
      # tag names. The store pulls from GHCR, so an ungated push here would put
      # the update prompt back on the phone even with the release gated.
      if _gate_skip "$n" "$asset" "$PATHS_DIR"; then
        log "gate: $image unchanged since the release — not repushing"
        continue
      fi
      # CREATE WITH GITHUB_TOKEN, UPDATE WITH THE PAT — each token for the one
      # thing it can do.
      #
      # A repo-scoped GITHUB_TOKEN creates a package carrying the repo's
      # visibility; the user-scoped PAT creates it PRIVATE. But GITHUB_TOKEN
      # cannot UPDATE a package in the user namespace, which is why af6767fdb
      # reverted the wholesale switch to it 43 minutes after fab52ec0c made it
      # — and that revert is what left every package added since 2026-08-28
      # born private in a public repo.
      #
      # So the choice is per package, not per repo: the FIRST push of a new
      # image authenticates as GITHUB_TOKEN so the package inherits the repo,
      # every push after that uses the ambient PAT login so it can update.
      # Measured, not assumed: cloud-lib-search was deleted and recreated by
      # this workflow at 12:43 on 2026-08-30 and came back private under the
      # PAT — the visibility is decided by the token that CREATES the package,
      # and only then.
      creds=()
      if [ -n "${GHCR_CREATE_TOKEN:-}" ] && command -v gh >/dev/null 2>&1 \
         && ! gh api "/user/packages/container/${image}" >/dev/null 2>&1; then
        log "ghcr: ${image} does not exist — creating it with GITHUB_TOKEN so it inherits the repo"
        creds=(--username "${GITHUB_ACTOR:-diegonmarcos}" --password "${GHCR_CREATE_TOKEN}")
      fi
      ( cd "$DIST_DIR" && oras push "${creds[@]}" "${reg}/${image}:latest"       "${asset}:${mt}" \
    --annotation "org.opencontainers.image.source=$(_ghcr_source)" )
      ( cd "$DIST_DIR" && oras push "${creds[@]}" "${reg}/${image}:sha-${SHORT}" "${asset}:${mt}" \
    --annotation "org.opencontainers.image.source=$(_ghcr_source)" )
      _ghcr_publish "$image"
      log "pushed ${image}:latest + :sha-${SHORT}"
    done < <(_libs)
    if [ -n "${GHCR_PRIVATE:-}" ]; then
      errlog "these packages are private and will 401 for unauthenticated pulls:"
      for p in $GHCR_PRIVATE; do errlog "  $p"; done
      errlog "every APK above was pushed; flip each package to public once and this passes."
      exit 1
    fi
    ;;
  gh-release)
    log "Publishing library APKs to GitHub Releases (rolling latest)…"
    PATHS_DIR="$(mktemp -d)"
    trap 'rm -rf "$PATHS_DIR"' EXIT
    _trigger_paths > "$PATHS_DIR/.triggers"
    # One upload call with every asset that MOVED: `gh release upload` takes N
    # files, and a per-file loop would re-resolve the release 24 times.
    files=(); gated_modules=(); gated_assets=(); held=0
    while IFS='|' read -r n flavor asset image; do
      if _gate_skip "$n" "$asset" "$PATHS_DIR"; then
        held=$((held + 1))
        continue
      fi
      gated_modules+=("$n"); gated_assets+=("$asset"); files+=("$DIST_DIR/$asset")
    done < <(_libs)
    [ "$held" -eq 0 ] || log "gate: $held library APKs unchanged since the release — not republished"
    if [ "${#files[@]}" -eq 0 ]; then
      log "gate: no library APK's source moved — nothing to publish"
      exit 0
    fi
    # Sidecar sha256 per asset — a same-size collision on 2026-08-30 hid a
    # real update from the in-app updater when it compared size only; it now
    # compares this digest instead, so every asset needs its own sidecar.
    sidecars=()
    for f in "${files[@]}"; do
      sha256sum "$f" | awk '{print $1}' > "$f.sha256"
      sidecars+=("$f.sha256")
    done
    gh release upload latest "${files[@]}" "${sidecars[@]}" --clobber 2>/dev/null \
      || gh release create latest \
           --title "Cloud Libs (rolling)" \
           --notes "Auto-updated from main." \
           "${files[@]}" "${sidecars[@]}"
    # Hard-verify: every asset AND its sidecar must be on the release, at the
    # right size — a same-size collision on 2026-08-30 is exactly what a
    # skipped/stale upload would look like, so presence alone isn't enough.
    asset_list="$(gh release view latest --json assets --jq '.assets[] | "\(.name) \(.size)"')"
    for f in "${files[@]}"; do
      b="$(basename "$f")"
      remote_size="$(awk -v n="$b" '$1==n{print $2}' <<<"$asset_list")"
      local_size="$(wc -c <"$f")"
      [ -n "$remote_size" ] && [ "$remote_size" = "$local_size" ] \
        && awk -v n="$b.sha256" '$1==n{f=1} END{exit !f}' <<<"$asset_list" \
        || { errlog "gh-release: $b or its .sha256 sidecar missing/size-mismatched on release latest after upload (remote=$remote_size local=$local_size)"; exit 1; }
    done
    # Stamped only AFTER the hard-verify above agrees the bytes are on the
    # release. A stamp written earlier would claim an identity the release does
    # not carry, and every later push would skip against that claim.
    i=0
    while [ "$i" -lt "${#gated_assets[@]}" ]; do
      sh "$GATE" stamp "$GATE_APP" \
         --asset "${gated_assets[$i]}" \
         --paths-from "$PATHS_DIR/${gated_modules[$i]}"
      i=$((i + 1))
    done
    log "published ${#files[@]} assets + sidecars"
    ;;
  list)
    # Same scan the build uses — handy for confirming what will ship, and the
    # list the blast-radius test iterates so it can never drift from the set
    # actually shipped. `column` only pretty-prints and is absent from some
    # runners; without the fallback this exits 127 and the caller reads an
    # empty module list as "nothing ships".
    if command -v column >/dev/null 2>&1; then
      _libs | column -t -s'|'
    else
      _libs | tr '|' '\t'
    fi
    ;;
  module-paths)
    # The input set gating ONE library APK, one path per line. Pure: no gh, no
    # network, no gradle. This is the surface
    # 1_cicd/src/scripts/test/publish-gate-blast-radius.test.sh drives to prove
    # a change to one module selects that module and not the other 23.
    MODULE="${2:-}"
    [ -n "$MODULE" ] || { errlog "usage: build.sh module-paths <module>"; exit 2; }
    PATHS_DIR="$(mktemp -d)"
    trap 'rm -rf "$PATHS_DIR"' EXIT
    _trigger_paths > "$PATHS_DIR/.triggers"
    _module_paths "$MODULE" "$PATHS_DIR/.triggers"
    ;;
  help|*)
    echo "Usage: build.sh <build|release|clean|oras-push|gh-release|list|module-paths>"
    ;;
esac
