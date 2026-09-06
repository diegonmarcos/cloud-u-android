#!/usr/bin/env bash
# Tester: the Configs → Profile credential fields, and the guarantee that
# neither credential can reach the profile sync payload.
#
# WHY THIS FILE EXISTS. ProfileSync.document() POSTs the profile to
# /c3-infra-api/fleet/profile, where it is stored as a plain JSON file on
# oci-apps. The screen that builds that document now also edits an Authelia
# bearer token and the WireGuard interface private key. If either ever lands in
# the payload, a live session credential and the credential for mesh access are
# on the wire and at rest on a host — the precise failure that holding them
# on-device is meant to prevent, and one that would be completely silent.
#
# Today the document enumerates its keys, so the leak is impossible by
# construction. This tester exists for the refactor that has not happened yet:
# the day someone replaces the enumeration with a sweep of the preference map,
# T3 fails here rather than in production.
#
# Static wiring tester (no device / no gradle run): asserts the exact markers.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"        # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF "$2" "$ROOT/$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }

# Same as hasnt(), but over CODE only — whole-line comments are dropped first.
# These files document what they must not do ("never reads the bearer store",
# "city_from may still arrive from an older client"), and a plain grep cannot
# tell that prose from a call. Stripping comment lines keeps the assertions
# about behaviour instead of forbidding the words that explain it.
codeof()     { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$ROOT/$1"; }
hasnt_code() { codeof "$1" | grep -qF "$2" && bad "$3 ($1)" || ok "$3"; }

PROFILE_DIR="app/src/main/java/com/diegonmarcos/superapp/profile"
FRAGMENT="$PROFILE_DIR/ProfileFragment.kt"
SYNC="$PROFILE_DIR/ProfileSync.kt"
PREFS="$PROFILE_DIR/ProfilePrefs.kt"
IMPORT="$PROFILE_DIR/ConfigAutoImport.kt"
CONFIGS_PREFS="app/src/main/java/com/diegonmarcos/superapp/settings/ConfigsPrefs.kt"
WG_PREFS="app/src/main/java/com/diegonmarcos/superapp/network/WireGuardPrefs.kt"
CONTRACT="docs/profile-sync-contract.md"

echo "== T1: the two credential fields exist on the Profile screen =="
has "$FRAGMENT" 'label(ctx, "Authelia bearer token")'  "Authelia bearer token field"
has "$FRAGMENT" 'label(ctx, "WireGuard private key")'  "WireGuard private key field"
has "$FRAGMENT" "TYPE_TEXT_VARIATION_PASSWORD"         "credential boxes are password-masked"
has "$FRAGMENT" "IME_FLAG_NO_PERSONALIZED_LEARNING"    "kept out of the keyboard's learned words"
has "$FRAGMENT" "IMPORTANT_FOR_AUTOFILL_NO"            "kept out of autofill"

echo "== T2: each credential reuses its EXISTING store, no parallel copy =="
# The bearer goes to the encrypted blob the config importers already read at
# auth.authelia_token; the WG key goes to the tunnel's own field. A second
# store would mean two values that can disagree, and a private key duplicated
# into a second place is strictly worse than one held in one place.
has "$FRAGMENT" "ConfigsPrefs(ctx).autheliaToken"            "bearer writes ConfigsPrefs"
has "$FRAGMENT" "WireGuardPrefs(ctx).interfacePrivateKey"    "WG key writes WireGuardPrefs"
has "$CONFIGS_PREFS" "EncryptedSharedPreferences.create"     "ConfigsPrefs is encrypted at rest"
has "$CONFIGS_PREFS" 'K_AUTHELIA_TOKEN = "authelia_token"'   "bearer at the existing auth.authelia_token path"
has "$WG_PREFS" 'K_IF_PRIVKEY     = "if_privkey"'            "WG key is the tunnel's own stored field"
# No copy of either credential in the synced profile store.
hasnt_code "$PREFS" "authelia"   "ProfilePrefs holds no Authelia token"
hasnt "$PREFS" "privateKey"      "ProfilePrefs holds no private key"
hasnt "$PREFS" "if_privkey"      "ProfilePrefs holds no WireGuard key"

echo "== T3: credentials CANNOT enter the sync payload =="
# 3a — proof by construction: the document names every key it puts.
has "$SYNC" 'put("profile", enforceAllowlist(' "profile object passes the allowlist filter"
has "$SYNC" "private val ALLOWED_PROFILE_KEYS" "an explicit allowlist exists"
# 3b — belt and braces: the allowlist itself must not name a credential.
for forbidden in authelia_token bearer token private_key privkey if_privkey secret wireguard; do
    if awk '/private val ALLOWED_PROFILE_KEYS/,/\)/' "$ROOT/$SYNC" | grep -qF "$forbidden"; then
        bad "ALLOWED_PROFILE_KEYS must not contain '$forbidden'"
    else
        ok "ALLOWED_PROFILE_KEYS does not contain '$forbidden'"
    fi
done
# 3c — the sync files must not so much as reference the credential stores, so
# there is no route by which a value could be read and then put().
hasnt_code "$SYNC" "ConfigsPrefs"   "ProfileSync never reads the bearer store"
hasnt_code "$SYNC" "WireGuardPrefs" "ProfileSync never reads the WireGuard store"
# 3d — a sweep of the preference map is exactly the refactor this guards
# against; the document must keep enumerating.
hasnt "$SYNC" "sp.all"           "document() does not sweep the preference map"
hasnt "$SYNC" "getAll()"         "document() does not sweep the preference map (getAll)"

echo "== T4: the removed fields are gone everywhere, not just from the form =="
# A field dropped from the UI but still uploaded is personal data the user can
# no longer see or edit — worse than either keeping it or removing it.
hasnt "$FRAGMENT" "City of origin"      "no City of origin label"
hasnt "$FRAGMENT" "Social profiles"     "no Social profiles section"
hasnt "$FRAGMENT" "socialEditor"        "social editor removed"
hasnt "$PREFS"    "cityFrom"            "cityFrom removed from ProfilePrefs"
hasnt "$PREFS"    "socialLinks"         "socialLinks removed from ProfilePrefs"
hasnt_code "$SYNC" "city_from"          "city_from not in the sync document"
hasnt_code "$SYNC" "social_media_links" "social_media_links not in the sync document"
hasnt "$IMPORT"   "prefs.cityFrom"      "auto-import no longer writes cityFrom"
hasnt "$IMPORT"   "prefs.socialLinks"   "auto-import no longer writes socialLinks"
has   "$PREFS"    "SCHEMA_VERSION = 2"  "schema bumped for the removal"
has   "$PREFS"    'remove("city_from")' "stored city_from is migrated away"
has   "$PREFS"    'remove("social_links")' "stored social_links is migrated away"
hasnt "build.json" '"city_from"'        "build.json profile_default drops city_from"
hasnt "app/build.gradle" "UI_PROFILE_CITY_FROM" "no orphan BuildConfig field"

echo "== T5: 'Titles …' is renamed to 'About' (label only, wire key unchanged) =="
hasnt "$FRAGMENT" "Titles"              "no Titles label"
has   "$FRAGMENT" 'label(ctx, "About")' "About label"
# The stored key and the wire key stay `titles` — renaming those would break
# the server contract and every previously synced record for no user benefit.
has "$SYNC"  'put("titles"' "wire key is still titles"
has "$PREFS" 'K_TITLES   = "titles_v2"' "stored key is still titles_v2"

echo "== T6: the contract doc matches the client =="
hasnt "$CONTRACT" '"city_from"'          "contract no longer lists city_from"
hasnt "$CONTRACT" '"social_media_links"' "contract no longer lists social_media_links"
has   "$CONTRACT" "Retired fields"       "contract explains the removal"
has   "$CONTRACT" "No credential of any kind" "contract states credentials are never sent"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
