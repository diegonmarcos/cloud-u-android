#!/usr/bin/env bash
# Tester for the Store's fleet tab, Store ▸ Cloud Constellation (superapp = fleet manager).
#
# Static wiring assertions + a live GHCR check that every app image resolves a
# remote digest (proving the AppStore can see every constellation app's build).
#
# Usage: ./test-constellation-appstore.sh   (live check needs network/wg0)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null && ok "$3" || bad "$3"; }

FLEET="$APP/data/constellation-fleet.json"

echo "== T1: fleet manifest auto-generated with every app =="
if [ -f "$FLEET" ]; then
  N=$(jq '.apps | length' "$FLEET" 2>/dev/null)
  [ "${N:-0}" -ge 8 ] && ok "constellation-fleet.json has $N apps (>=8)" || bad "expected >=8 apps, got ${N:-0}"
  # Every standalone ac_cloud-<id> self-registers: the top-level apps +
  # the 4 promoted ex-comms fork-apps (dialer/chat/mail/matrix), each now its
  # OWN dir + ship-cloud-<id>.yml CI. Fleet ids = the dir basenames.
  # The SuperApp's own fleet id is its directory, aa_cloud-superapp (regen.sh
  # strips only ac_cloud-/ac_c3-), and other declarations key it that way
  # (ac_cloud-drive/build.json "fleet", assets/fleet-config.json).
  for id in aa_cloud-superapp nav code browser vault wallet dialer chat mail matrix; do
    jq -e --arg i "$id" '.apps[] | select(.id==$i) | .package and .image and .registry' "$FLEET" >/dev/null 2>&1 \
      && ok "fleet entry $id has package+image" || bad "fleet entry $id missing/incomplete"
  done
  # cloud-comms HUB is decommissioned (archived to z_archive/) — it and the old
  # comms-* fork ids must be GONE (the forks are now independent apps above).
  jq -e '.apps[] | select(.package=="com.diegonmarcos.comms" or .image=="cloud-comms-hub")' "$FLEET" >/dev/null 2>&1 \
    && bad "cloud-comms hub still in fleet (should be decommissioned)" || ok "cloud-comms hub absent (decommissioned)"
  jq -e '.apps[] | select(.id | startswith("comms-"))' "$FLEET" >/dev/null 2>&1 \
    && bad "stale comms-* fork id present" || ok "no stale comms-* fork ids"
else bad "constellation-fleet.json missing (run data/regen.sh)"; fi
has "$APP/data/regen.sh" "regen_constellation" "regen.sh auto-scans siblings → fleet json"
# self-registering / DRY: the dialer package is scanned from ac_cloud-dialer/
# build.json (forks.dialer.app_id), not hand-typed — dialer is now an
# independent fleet app with its own ship CI.
grep -q '"com.diegonmarcos.comms.dialer"' "$FLEET" 2>/dev/null && ok "dialer package scanned from ac_cloud-dialer/build.json" || bad "dialer package not scanned"

echo "== T2: baked into BuildConfig (data-driven) =="
has "$APP/app/build.gradle" "constellation-fleet.json" "build.gradle reads the fleet snapshot"
has "$APP/app/build.gradle" 'buildConfigField "String", "CONSTELLATION_FLEET_B64"' "build.gradle bakes CONSTELLATION_FLEET_B64"

echo "== T3: engine reuses libs/updater primitives (no reinvention) =="
ENG="$(cd "$APP/.." && pwd)/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"
has "$ENG" "GhcrClient(app.registry, app.namespace, app.image)" "Fleet checks per-image via existing GhcrClient"
# The PackageInstaller fallback lives in install/InstallChannel.kt since the
# install channels were split out of Fleet.kt; Fleet.commit routes through it.
CHAN="$(dirname "$ENG")/install/InstallChannel.kt"
has "$CHAN" "UpdateInstaller(ctx).install(apk, app.pkg)" "Fleet installs foreign pkg via existing UpdateInstaller"
has "$ENG" "packageInstaller.uninstall(pkg" "Fleet uninstall via PackageInstaller"

echo "== T4: UI page + navigation + worker wired =="
has "$(cd "$APP/.." && pwd)/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt" "CONSTELLATION_FLEET_B64" "StoreCloudFragment reads the fleet"
# #563: the store is an ordinary tabbed page now (config/store, tabs
# store-cloud + store-phone), routed by SectionPages like every other page —
# there is no action branch left to assert. test-store-identity.sh owns the
# full identity check; this line only keeps the old wiring claim honest.
# #865 through the Cloud Store hand-off, which embeds the fragment until Cloud Store is installed.
has "$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt" 'pageId == "store-cloud"    -> com.diegonmarcos.superapp.apps.CloudStoreHandoff.page(pageId)' "SectionPages routes Store ▸ Cloud Constellation (via the Cloud Store hand-off)"
has "$APP/app/src/main/java/com/diegonmarcos/superapp/apps/CloudStoreHandoff.kt" '"store-cloud" to com.diegonmarcos.superapp.appstore.StoreCloudFragment::class.java' "the hand-off embeds StoreCloudFragment for store-cloud"
has "$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt" "ConstellationWorker.start(this)" "App.onCreate starts the fleet worker"
jq -e '.ui.sections[] | select(.id=="config") | .pages[] | select(.id=="store")' "$APP/build.json" >/dev/null 2>&1 \
  && ok "build.json config.pages has the Store entry" || bad "Store page not in build.json ui.sections"

echo "== T6: #894 every route to a Store page opens Cloud Store when installed (routing layer) =="
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
HO="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/CloudStoreHandoff.kt"
t6() {  # t6 <nav> <handoff>: prints one FAIL line per defect
  grep -qF 'CloudStoreHandoff.intercept(host.navContext(), sectionId, pageId)) return' "$1" \
    || echo "FAIL openSectionPage does not hand Store routes to Cloud Store first"
  awk '/fun openSectionPage\(sectionId: String, pageId: String, args/{f=1} f&&/intercept\(/{print "in"; exit} f&&/Sections.tabOwnerOf/{exit}' "$1" | grep -q in \
    || echo "FAIL the intercept runs after the page was already resolved"
  grep -qF '!installed -> Route.EMBEDDED' "$2" || echo "FAIL absent Cloud Store no longer falls back to the embedded Store"
  grep -qF 'open() -> Route.OPENED_CLOUD_STORE' "$2" || echo "FAIL an installed Cloud Store is not opened"
  grep -qF 'else -> Route.PLACEHOLDER' "$2" || echo "FAIL a failed open no longer falls back to the placeholder"
  grep -qF 'if (sectionId == "config") TAB_OF_PAGE[pageId] else null' "$2" || echo "FAIL tabFor does not map the three Store pages"
}
out="$(t6 "$NAV" "$HO")"
[ -z "$out" ] && ok "Store routes: installed -> Cloud Store on its tab; open fails -> placeholder; absent -> embedded" \
  || { printf '%s\n' "$out" | sed 's/^/    /'; bad "T6 Store routing"; }
MW="$(mktemp -d)"
t6mut() {  # t6mut <title> <nav|ho> <old> <new>
  cp "$NAV" "$MW/n.kt"; cp "$HO" "$MW/h.kt"
  local f; case "$2" in nav) f="$MW/n.kt";; ho) f="$MW/h.kt";; esac
  python3 - "$f" "$3" "$4" <<'EOF2' || { bad "T6 mutation '$1' did not apply"; return; }
import sys; p, old, new = sys.argv[1:4]; s = open(p).read()
assert old in s, old; open(p, "w").write(s.replace(old, new, 1))
EOF2
  [ -n "$(t6 "$MW/n.kt" "$MW/h.kt")" ] && ok "T6 mutant '$1' goes red" || bad "T6 mutant '$1' stayed GREEN"
}
t6mut "routing layer skips the intercept" nav 'CloudStoreHandoff.intercept(host.navContext(), sectionId, pageId)) return' 'CloudStoreHandoff.intercept(host.navContext(), sectionId, pageId) && false) return'
t6mut "absent still tries Cloud Store"    ho '!installed -> Route.EMBEDDED' 'installed -> Route.EMBEDDED'
t6mut "failed open shows nothing"         ho 'else -> Route.PLACEHOLDER' 'else -> Route.EMBEDDED'
t6mut "mesh tab lost"                     ho 'if (sectionId == "config") TAB_OF_PAGE[pageId] else null' 'null'
rm -rf "$MW"

echo "== T5: LIVE — every app image resolves a GHCR digest (AppStore can see them) =="
if command -v curl >/dev/null && [ -f "$FLEET" ]; then
  reg="ghcr.io"
  while read -r ns img; do
    tok=$(curl -s --max-time 12 "https://$reg/token?service=$reg&scope=repository:$ns/$img:pull" | jq -r .token 2>/dev/null)
    dig=$(curl -s --max-time 12 -H "Authorization: Bearer $tok" \
          -H "Accept: application/vnd.oci.image.manifest.v1+json" \
          "https://$reg/v2/$ns/$img/manifests/latest" | jq -r '.layers[0].digest // empty' 2>/dev/null)
    [ -n "$dig" ] && ok "$img → ${dig:0:19}…" || echo "  SKIP: $img (no digest — unpublished/blocked/offline)"
  done < <(jq -r '.apps[] | select(.blocked|not) | "\(.namespace) \(.image)"' "$FLEET")
else echo "  SKIP: curl/jq or fleet json unavailable"; fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
