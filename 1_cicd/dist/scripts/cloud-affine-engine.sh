# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-affine-engine.sh ───
#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-affine-engine — Cloud AFFiNE from the vendored source      ║
# ║                                                                  ║
# ║ AFFiNE is vendored at the app root (ac_cloud-affine/, no .git).  ║
# ║ This engine builds the Android APK from that committed source:   ║
# ║   web bundle (rspack via the AFFiNE CLI) → cap sync → Gradle     ║
# ║   (which cross-compiles the Rust store crate through the         ║
# ║   mozilla-rust-android-gradle plugin) → shared-constellation     ║
# ║   re-sign → dist/Cloud-Notes.apk.                                ║
# ║                                                                  ║
# ║ Vendored as ac_cloud-affine/build.sh (build.json::vendored_engine)║
# ║ and CI runs THAT copy (parity is enforced by the generator).     ║
# ║                                                                  ║
# ║ DE-CLOUDED (#469): the tree has no packages/backend, no          ║
# ║ packages/common/native, no :service Apollo module, no Firebase.  ║
# ║ The app is local-first: it never contacts an AFFiNE server.      ║
# ║                                                                  ║
# ║ Commands:                                                        ║
# ║   materialize-fork <key>  no-op — the tree is VENDORED (no .git) ║
# ║   build-fork <key>        yarn bundle → cap sync → gradle → sign ║
# ║   publish-fork <key>      oras push dist APK → ghcr image        ║
# ║   gh-release-fork <key>   attach dist APK + sha256 to rolling    ║
# ║                           GitHub Release                          ║
# ║   asset-path              print dist APK path                    ║
# ║   clean                   rm -rf dist/                           ║
# ║   help                    this message                            ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_ROOT="$SCRIPT_DIR"
BUILD_JSON="$SCRIPT_DIR/build.json"
DIST_DIR="$SCRIPT_DIR/dist"

log()    { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf '\033[0;31m[%s] ERROR: %s\033[0m\n' "$(date '+%H:%M:%S')" "$1" >&2; }
die()    { errlog "$1"; exit 1; }
_json()  { jq -r "$1 // empty" "$BUILD_JSON" 2>/dev/null || printf ''; }

command -v jq >/dev/null 2>&1 || die "jq is not on PATH"
[ -f "$BUILD_JSON" ] || die "no build.json at $BUILD_JSON"

APP_NAME="$(_json '.name')"
[ -n "$APP_NAME" ] || APP_NAME="cloud-affine"
APP_ID="$(_json '.android.application_id')"
[ -n "$APP_ID" ] || APP_ID="com.diegonmarcos.affine"
ASSET="$(_json '.release.artifact.release')"
[ -n "$ASSET" ] || ASSET="Cloud-Notes.apk"
IMG="$(_json '.release.ghcr.image')"
[ -n "$IMG" ] || IMG="cloud-affine"
FLAVOR="$(_json '.android.flavor')"
[ -n "$FLAVOR" ] || FLAVOR="stable"

# ── signing-key resolver (build.json::signing → vault → env) ──────────
# The ONE shared Cloud-constellation key. No fallback: every APK this fleet
# signs must be installable over every other (the updater keys off signature).
_resolve_signing() {
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  ks_rel="$(_json '.signing.vault_keystore')"
  sec_rel="$(_json '.signing.vault_secrets')"
  [ -n "$ks_rel" ] && [ -n "$sec_rel" ] \
    || die "FATAL signing: .signing.vault_keystore/.vault_secrets empty in build.json — ALL constellation apps sign with the ONE shared key"
  vault="${VAULT_DIR:-$HOME/git/cloud-vault}"
  ks="$vault/$ks_rel"
  [ -f "$ks" ] || die "FATAL signing: the ONE shared constellation keystore is missing at $ks (set VAULT_DIR). NO fallback key allowed."
  command -v sops >/dev/null 2>&1 || die "FATAL signing: sops not on PATH; cannot decrypt the shared key."
  store_pw="$(sops --config /dev/null -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops --config /dev/null -d --extract '["key_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops --config /dev/null -d --extract '["key_alias"]' "$vault/$sec_rel" 2>/dev/null || true)"
  [ -n "$store_pw" ] && [ -n "$alias_" ] \
    || die "FATAL signing: cannot decrypt $sec_rel (need SOPS_AGE_KEY). Refusing any other key."
  export ANDROID_KEYSTORE_FILE="$ks" ANDROID_KEYSTORE_PASSWORD="$store_pw" \
         ANDROID_KEY_PASSWORD="$key_pw" ANDROID_KEY_ALIAS="$alias_"
  log "signing: ONE shared constellation key (alias $alias_)"
}

# Re-sign whatever gradle produced (debug/unsigned) with the shared key and
# prove the result. Mirrors cloud-chat-fork-engine.sh::_enforce_signature.
_enforce_signature() {
  local apk="$1" bt zipalign apksigner
  [ -f "$apk" ] || die "sign-enforce: missing APK $apk"
  _resolve_signing
  [ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME is not set"
  bt="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  zipalign="$bt/zipalign"; apksigner="$bt/apksigner"
  [ -x "$apksigner" ] || die "sign-enforce: apksigner missing (bt=$bt)"
  "$zipalign" -f -p 4 "$apk" "${apk}.aln" 2>/dev/null && mv -f "${apk}.aln" "$apk" || rm -f "${apk}.aln"
  "$apksigner" sign --ks "$ANDROID_KEYSTORE_FILE" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
    "$apk" || die "sign-enforce: re-sign with shared key failed for $apk"
  rm -f "${apk}.idsig"
  "$apksigner" verify "$apk" >/dev/null 2>&1 \
    || die "sign-enforce: FATAL $(basename "$apk") not validly signed after shared-key re-sign"
  log "sign-enforce: OK $(basename "$apk") signed by the ONE shared constellation key"
}

# Prove the built APK really is OUR application id (a de-clouding regression
# guard: upstream appId must never leak back in).
_assert_apk_identity() {
  local apk="$1"
  local bt dump
  [ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME is not set"
  bt="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  dump="$("$bt/aapt" dump badging "$apk" 2>/dev/null | grep -E "^package: name=" || true)"
  echo "$dump" | grep -q "name='$APP_ID'" \
    || die "identity: FATAL $(basename "$apk") has package $(echo "$dump" | head -1) — expected $APP_ID"
  log "identity: OK $(basename "$apk") is $APP_ID"
}

# ── materialize-fork: the source is VENDORED, nothing to clone ────────
step_materialize_fork() {
  log "materialize-fork: no-op — AFFiNE is vendored at the app root (no .git); revision pinned in build.json"
}

# ── build-fork: the full three-stage build ────────────────────────────
step_build_fork() {
  cd "$APP_ROOT"

  [ -f yarn.lock ] || die "no yarn.lock at the app root — this is not the AFFiNE tree"
  [ -f packages/frontend/apps/android/package.json ] || die "android workspace missing — incomplete tree"

  # Node engines require >=22.12.0 <23.0.0; fail loudly rather than build
  # with the wrong interpreter.
  if command -v node >/dev/null 2>&1; then
    node -e 'const v=process.versions.node; if (!(v>="22.12.0" && v<"23.0.0")) { console.error("node "+v+" not in AFFiNE engines range"); process.exit(1); }' \
      || die "node version outside AFFiNE's engines range (>=22.12.0 <23.0.0)"
  fi

  log "build-fork: yarn install (AFFiNE monorepo, Yarn 4.18.0)"
  # --immutable and nothing else: on CI, Yarn 4 treats CI=true as immutable
  # anyway, so a fallback would fail equally — and silently regenerating the
  # lock on CI is drift. The committed yarn.lock already reflects the PRUNED
  # workspaces (packages/backend, packages/common/native); if it ever stops
  # matching the tree, regenerate it locally and commit it.
  yarn install --immutable

  log "build-fork: web bundle (rspack) → packages/frontend/apps/android/dist"
  yarn affine @affine/android build

  log "build-fork: cap sync → App android assets"
  yarn workspace @affine/android sync

  log "build-fork: rust cross toolchain (aarch64-linux-android)"
  # Gradle's mozilla-rust-android-gradle plugin shells out to cargo-ndk and
  # needs the target std present, so make both true here (idempotently) —
  # upstream CI relies on the same pair.
  rustup target list --installed 2>/dev/null | grep -q '^aarch64-linux-android$' \
    || rustup target add aarch64-linux-android
  command -v cargo-ndk >/dev/null 2>&1 || cargo install cargo-ndk --locked

  log "build-fork: gradle assemble (${FLAVOR}Release)"
  cd "$APP_ROOT/packages/frontend/apps/android/App"
  ./gradlew --no-daemon ":app:assemble${FLAVOR^}Release"

  APK="$(find "$APP_ROOT/packages/frontend/apps/android/App/app/build/outputs/apk/${FLAVOR}/release" -name '*.apk' | head -1)"
  [ -n "$APK" ] || die "build-fork: no APK under outputs/apk/${FLAVOR}/release"

  mkdir -p "$DIST_DIR"
  _enforce_signature "$APK"
  _assert_apk_identity "$APK"

  cp "$APK" "$DIST_DIR/$ASSET"
  log "build-fork: OK $DIST_DIR/$ASSET"
}

# ── publish-fork: oras push to GHCR ───────────────────────────────────
step_publish_fork() {
  local ref media
  ref="$(_json '.release.ghcr.registry')/$(_json '.release.ghcr.namespace')/$IMG"
  media="$(_json '.release.ghcr.media_type')"
  [ -n "$ref" ] || die "publish-fork: .release.ghcr.registry/namespace empty"
  [ -f "$DIST_DIR/$ASSET" ] || die "publish-fork: $DIST_DIR/$ASSET missing — run build-fork first"
  command -v oras >/dev/null 2>&1 || die "publish-fork: oras not on PATH"
  local creds=()
  [ -n "${GH_TOKEN:-}" ] && creds=(--username diegonmarcos --password "$GH_TOKEN")
  ( cd "$DIST_DIR" && oras push "${creds[@]}" "$ref" "$ASSET:$media" --artifact-type "$media" ) \
    || die "publish-fork: oras push failed"
  log "publish-fork: OK $ref ← $ASSET"
}

# ── gh-release-fork: rolling GitHub Release ───────────────────────────
step_gh_release_fork() {
  local tag aname
  tag="$(_json '.release.gh_release.rolling_tag')"
  [ -n "$tag" ] || tag="latest"
  [ -f "$DIST_DIR/$ASSET" ] || die "gh-release-fork: $DIST_DIR/$ASSET missing — run build-fork first"
  command -v gh >/dev/null 2>&1 || die "gh-release-fork: gh not on PATH"
  ( cd "$DIST_DIR" && sha256sum "$ASSET" | awk '{print $1}' > "$ASSET.sha256" )
  if ! gh release view "$tag" >/dev/null 2>&1; then
    gh release create "$tag" --title "$tag" --generate-notes >/dev/null 2>&1 || true
  fi
  gh release upload "$tag" "$DIST_DIR/$ASSET" "$DIST_DIR/$ASSET.sha256" --clobber \
    || die "gh-release-fork: upload failed"
  log "gh-release-fork: OK $ASSET + .sha256 on release $tag"
}

step_asset_path() { printf '%s\n' "$DIST_DIR/$ASSET"; }

step_clean() { rm -rf "$DIST_DIR"; }

usage() { sed -n '5,28p' "$0" | sed 's/^# \?//'; }

CMD="${1:-help}"
case "$CMD" in
  materialize-fork) step_materialize_fork "$@" ;;
  build-fork)       step_build_fork "$@" ;;
  publish-fork)     step_publish_fork "$@" ;;
  gh-release-fork)  step_gh_release_fork "$@" ;;
  asset-path)       step_asset_path ;;
  clean)            step_clean ;;
  help|--help|-h)   usage ;;
  *) die "unknown command: $CMD (see ./build.sh help)" ;;
esac