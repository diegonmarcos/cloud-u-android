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
for forbidden in authelia_token authelia_email bearer token private_key privkey if_privkey secret wireguard otp 2fa mail_code; do
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

echo "== T7: the Cloud provider preset carries no private key =="
# The Provider dropdown fills the tunnel's PUBLIC half from the fleet preset.
# The private key is per-device by definition: two devices sharing one are a
# single peer to the hub and knock each other off the mesh. So the preset must
# fill everything EXCEPT that, and the seed data must not contain one either.
has "$FRAGMENT" 'label(ctx, "Provider")'        "Provider selector exists"
has "$WG_PREFS" "fun applyCloudPreset"          "the preset is applied from one place"
# The preset is derived from build.json via BuildConfig — not a literal here,
# so it follows the fleet when the hub moves.
has "$WG_PREFS" "BuildConfig.UI_WG_PEERS_JSON_B64" "preset peers come from the baked build.json data"
hasnt_code "$WG_PREFS" 'interfaceAddress    = "10.' "no hardcoded address literal in the preset"
# applyCloudPreset() must not touch the private key.
if awk '/fun applyCloudPreset/,/^    }/' "$ROOT/$WG_PREFS" | grep -qF "interfacePrivateKey"; then
    bad "applyCloudPreset() must not write interfacePrivateKey"
else
    ok "applyCloudPreset() leaves the private key alone"
fi
# Switching provider must not silently eat a config the user entered.
has "$WG_PREFS"  "fun matchesCloudPreset"  "drift from the preset is detectable"
has "$FRAGMENT"  "confirmCloudPreset"      "Cloud asks before overwriting a custom config"
# The seeded WireGuard data itself must carry no private key. Scoped to the
# wireguard_default block: ui.import_schema.wg documents the IMPORT format and
# legitimately names *_private_key fields, which a whole-file grep would hit.
if python3 -c "
import json,sys
wg = json.load(open('$ROOT/build.json'))['ui'].get('wireguard_default', {})
blob = json.dumps(wg)
sys.exit(1 if ('private_key' in blob or 'privkey' in blob) else 0)
" 2>/dev/null; then
    ok "build.json wireguard_default seeds no private key"
else
    bad "build.json wireguard_default must not seed a private key"
fi
hasnt "app/build.gradle" "UI_WG_INTERFACE_PRIVATE_KEY" "no private key is baked into BuildConfig"

echo "== T8: a bearer token cannot be stored without an email identity =="
# Authelia issues tokens per account and the fleet has several, so a lone token
# cannot say whose access it carries. The pairing is enforced at the STORE, not
# in the form, so the config-import path cannot become the hole the UI closed.
has "$CONFIGS_PREFS" "fun setAutheliaCredential" "one writer for the pair"
has "$CONFIGS_PREFS" "fun clearAutheliaCredential" "clearing removes both halves"
has "$CONFIGS_PREFS" "K_AUTHELIA_EMAIL = \"authelia_email\"" "the address has a declared path"
has "$CONFIGS_PREFS" "EMAIL_PATTERN"             "the address is shape-checked, not free text"
# The token property must be read-only: a public setter is a second writer, and
# a second writer is how the orphan state comes back.
has   "$CONFIGS_PREFS" "val autheliaToken"       "the token is read-only from outside"
hasnt_code "$CONFIGS_PREFS" "var autheliaToken"  "no public token setter"
# The reader — not the UI — is what makes an unpaired token unusable.
if awk '/private fun credential\(\)/,/^    }/' "$ROOT/$CONFIGS_PREFS" | grep -qF "EMAIL_PATTERN"; then
    ok "an unidentified token reads back as absent"
else
    bad "credential() must require a valid address before returning a token"
fi
# An existing token must not be silently adopted or destroyed by the new rule.
has "$CONFIGS_PREFS" "fun hasOrphanToken"        "a pre-pairing token is detected, not deleted"
has "$CONFIGS_PREFS" "fun adoptOrphanToken"      "linking it is explicit and user-driven"
has "$FRAGMENT"      'label(ctx, "Account email")' "the identity is on the Profile screen"
has "build.json"     '"authelia_email"'          "the import contract declares the pairing"
# ONE email box for the section — a second would only invite disagreement.
if [ "$(grep -c 'autheliaEmailEditor(ctx)' "$ROOT/$FRAGMENT")" = "1" ]; then
    ok "exactly one account-email editor"
else
    bad "there must be exactly one account-email editor"
fi

echo "== T9: pairing the token to a synced field did not widen the payload =="
# The email IS a synced profile field and the token must never be. Now that
# they are stored together, the document must still name every key it sends.
has "$SYNC" 'put("profile", enforceAllowlist(' "payload still passes the allowlist"
PUTS=$(awk '/put\("profile", enforceAllowlist\(/,/\}\)\)/' "$ROOT/$SYNC" | grep -c 'put("')
# 8 named contact fields + the put("profile", …) wrapper line itself.
if [ "$PUTS" = "9" ]; then
    ok "document() still enumerates exactly 8 profile fields"
else
    bad "document() should enumerate 8 profile fields (found $((PUTS-1)))"
fi
hasnt_code "$SYNC" "autheliaEmail" "ProfileSync never reads the paired address"
hasnt_code "$SYNC" "credential"    "ProfileSync never reads the credential pair"

echo "== T10: the Connect | Info split, and the mailed 2FA code is never stored =="
# The screen is two tabs now. What must survive the split is WHERE each field
# is stored, not where it is drawn — so these assert the placement AND that
# nothing gained a store on the way across.
has "$FRAGMENT" 'newTab().setText("Connect")' "Connect tab exists"
has "$FRAGMENT" 'newTab().setText("Info")'    "Info tab exists"
# The existing pill idiom, not a second tab mechanism.
has "$FRAGMENT" "AppTabsStyle.apply"          "reuses the launcher's pill chrome"
# ...but NOT the child-fragment machinery behind it. SectionTabsFragment swaps
# fragments into a fixed pool of pane host ids, and this screen rebuilds itself
# with detach/attach after every pick, link, clear, erase and import — the
# exact sequence that leaves such a pane blank. Both tabs are inline views.
hasnt_code "$FRAGMENT" "childFragmentManager"      "the tabs use no child fragments"
hasnt_code "$FRAGMENT" "SectionTabsFragment"       "no second tab mechanism"
hasnt_code "$FRAGMENT" "R.id.section_pane"         "no pane host ids are borrowed"
# A redraw must not throw the user back to tab 1.
has "$FRAGMENT" "private var selectedTab"          "the selected tab survives a redraw"

# Connect carries the paired credential; Mesh Data carries the tunnel key.
has "$FRAGMENT" 'connect.addView(label(ctx, "Account email"))'          "account email is on Connect"
has "$FRAGMENT" 'connect.addView(label(ctx, "Authelia bearer token"))'  "bearer is on Connect"
has "$FRAGMENT" 'col.addView(label(ctx, "WireGuard private key"))'      "WG key is on Info · Mesh Data"
has "$FRAGMENT" 'col.addView(label(ctx, "Provider"))'                   "Provider is on Info · Mesh Data"
has "$FRAGMENT" 'sectionHeader(ctx, "Personal Data")'                   "Info has a Personal Data section"
has "$FRAGMENT" 'sectionHeader(ctx, "Mesh Data'                         "Info has a Mesh Data section"
has "$FRAGMENT" 'sectionHeader(ctx, "Imports")'                         "Info has an Imports section"
# The orphan-token affordance must stay reachable after the move.
has "$FRAGMENT" 'connect.addView(pickButton(ctx, "Link the stored token to this address")' \
    "the orphan-token link affordance survived the split"

# WHAT THE NEW FIELD IS. This fleet's Authelia enables webauthn + totp and no
# duo_api, so there is no email second FACTOR; notifier.smtp exists only to
# deliver the identity-validation one-time code for enrolling a factor or
# resetting a password. The box therefore takes a TRANSIENT CODE, not a seed —
# and a transient code that gets persisted is a stored value that expired
# minutes ago, while a seed that gets persisted is a permanent second factor
# sitting next to the bearer. Neither may happen.
has "$FRAGMENT" 'label(ctx, "Mail 2FA confirmation code")' "the mail 2FA field exists"
# No seed anywhere: the code is not a secret to keep, and nothing may start
# keeping one.
hasnt_code "$CONFIGS_PREFS" "totp"        "ConfigsPrefs stores no TOTP seed"
hasnt_code "$CONFIGS_PREFS" "K_2FA"       "ConfigsPrefs has no 2FA key"
hasnt_code "$PREFS"         "2fa"         "ProfilePrefs holds no 2FA value"
hasnt "build.json" '"totp_secret"'        "build.json seeds no TOTP secret"
# The field has NO save lambda and NO watcher — that is the whole mechanism by
# which it is not persisted, so assert it directly rather than trusting prose.
if awk '/private fun mailConfirmationField/,/^        }$/' "$ROOT/$FRAGMENT" \
     | grep -qE 'addTextChangedListener|Prefs\('; then
    bad "mailConfirmationField() must not save what is typed into it"
else
    ok "the mailed code has no watcher and no store"
fi
# ...and the one place it IS read must only reach the clipboard.
if awk '/private fun confirmMailCode/,/^    }$/' "$ROOT/$FRAGMENT" | grep -qE 'ConfigsPrefs|ProfilePrefs|WireGuardPrefs'; then
    bad "confirmMailCode() must not write the code to any store"
else
    ok "the mailed code reaches no preference store"
fi
if awk '/private fun confirmMailCode/,/^    }$/' "$ROOT/$FRAGMENT" | grep -qF 'field.setText("")'; then
    ok "a used code is cleared from the box"
else
    bad "confirmMailCode() must clear the box after use"
fi
if awk '/fun onDestroyView/,/^    }$/' "$ROOT/$FRAGMENT" | grep -qF "mailCodeField = null"; then
    ok "the mailed code dies with the view"
else
    bad "onDestroyView() must drop the mailed code"
fi
# And it must not have opened a new route into the payload.
hasnt_code "$SYNC" "mailCode"     "ProfileSync never reads the mailed code"
hasnt_code "$SYNC" "confirmation" "ProfileSync carries no confirmation field"
hasnt_code "$IMPORT" "mail_code"  "the auto-import writes no 2FA code"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
