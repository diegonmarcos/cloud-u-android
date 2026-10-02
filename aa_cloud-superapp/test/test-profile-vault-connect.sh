#!/usr/bin/env bash
# Tester (#566/#569/#570): Configs ▸ Profile ▸ Connect ▸ Vault configs, and the
# Fleet tab (the vault-backed fleet configurator).
#
# The behaviour (wire format, section grouping, failure hints, device
# selection, mesh ownership by address, the per-section comparisons and
# applies) is executed by app/src/test/.../profile/VaultConnectTest.kt and
# VaultCockpitTest.kt. This file pins what a JVM test cannot see:
#   T1  every build.json::ui.vault_connect key reaches BuildConfig, and the
#       Kotlin reads each BuildConfig field it bakes (data, not literals)
#   T2  every R.string.vault_* the Kotlin uses exists in EVERY locale file
#   T3  (#778) the cockpit's apps are Account ▸ Runtime / Drift's apps: the
#       runtime reader and pusher dispatch on each declared `apply` (and every
#       declared one has a reader), labels live only in build.json — the layout is data
#   T4  READING WRITES NOTHING: the fetch path and the runtime readers run no
#       apply and touch no store; every write is AccountRuntime.push, reached only
#       from the model's server → runtime, reached only from a tap (or its debug op)
#   T5  the one-time code and the browser session are never stored
#   T6  the device is CHOSEN, never typed: only its id is persisted, and no
#       address, key or hostname is a Kotlin literal on the mesh path
#   T7  the apps reuse the #565 inventory parser — no second parser, no installer
#   T8  (#570 reopened) the Fleet tab IS the cockpit: hero + one card per
#       section through FleetCockpitView, the shared StatusLight and no private
#       colour, badge icons declared as data and present as drawables, Fleet the
#       default tab, no animation (Power-Saving-safe), and the OLD page's
#       headline-per-section idiom gone from the render path; the layout-tree
#       JVM test exists and reads the declared ids
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
GR="$APP/app/build.gradle"
PF="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/ProfileFragment.kt"
# #587 the vault route and the sign-in live in the fleet's libs:auth; the endpoints in the ONE shared declaration.
LIB="$APP/../ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth"
SHARED="$APP/../ab_cloud-libs-shared/build.json"
VC="$LIB/VaultConnect.kt"
CP="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/VaultCockpit.kt"
RES="$APP/app/src/main/res"

# Code only — whole-line comments dropped, so prose about what must not
# happen does not read as it happening.
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$1"; }

echo "== T1: build.json::ui.vault_connect → BuildConfig → Kotlin =="
KEYS=$(jq -r '.ui.vault_connect | keys[] | select(startswith("_") | not)' "$BJ" 2>/dev/null)
[ -n "$KEYS" ] && ok "T1: ui.vault_connect declares $(echo $KEYS | wc -w) keys" \
               || bad "T1: build.json has no ui.vault_connect"
for k in $KEYS; do
    v=$(jq -c --arg k "$k" '.ui.vault_connect[$k]' "$BJ")
    [ -n "$v" ] && [ "$v" != null ] || bad "T1: ui.vault_connect.$k is empty"
    grep -qE "vaultConnect\.$k\b" "$GR" && ok "T1: gradle reads ui.vault_connect.$k" \
                                     || bad "T1: gradle never reads ui.vault_connect.$k"
done
FIELDS=$(grep -oE '"UI_VAULT_CONNECT_[A-Z_0-9]+"' "$GR" | tr -d '"' | sort -u)
[ "$(echo "$FIELDS" | grep -c .)" = "$(echo $KEYS | wc -w)" ] \
    && ok "T1: one BuildConfig field per declared key" \
    || bad "T1: BuildConfig fields ($(echo $FIELDS)) do not match the declared keys ($(echo $KEYS))"
for f in $FIELDS; do
    grep -q "BuildConfig.$f" "$PF" "$CP" && ok "T1: the profile package reads $f" \
                                         || bad "T1: $f is baked but never read"
done
# #587: the route's endpoints, timeouts and schema versions are the fleet's ONE
# declaration (ab_cloud-libs-shared/build.json::auth.vault_connect), read through
# libs:auth's AuthDeclaration — never a second copy in this app.
for k in base_url start_path fetch_path connect_timeout_ms read_timeout_ms known_schema_versions; do
    jq -e --arg k "$k" '.auth.vault_connect[$k] | select(. != null and . != "")' "$SHARED" >/dev/null \
        && ok "T1: shared auth.vault_connect.$k declared" || bad "T1: shared build.json lacks auth.vault_connect.$k"
    jq -e --arg k "$k" '.ui.vault_connect[$k]' "$BJ" >/dev/null 2>&1 \
        && bad "T1: ui.vault_connect.$k is ALSO declared in this app — two declarations" || ok "T1: $k lives only in the shared declaration"
done
grep -q 'private fun vaultEndpoints() = AuthDeclaration.vault' "$PF" && ok "T1: the fragment reads the endpoints off AuthDeclaration" \
                                                                    || bad "T1: the fragment does not read AuthDeclaration.vault"
grep -q 'AuthDeclaration.knownSchemaVersions' "$VC" && ok "T1: the schema gate reads the shared declaration" || bad "T1: VaultConnect does not read the shared schema versions"

echo "== T2: every vault string exists in every locale =="
USED=$(grep -ohE 'R\.string\.vault_[a-z_]+' "$PF" "$VC" "$CP" | sed 's/R\.string\.//' | sort -u)
[ -n "$USED" ] && ok "T2: $(echo "$USED" | wc -l) vault strings used" || bad "T2: no R.string.vault_* used — labels are literals"
for loc in "$RES"/values*/strings.xml; do
    for s in $USED; do
        grep -q "name=\"$s\"" "$loc" || bad "T2: $s missing from ${loc#$APP/}"
    done
done
[ "$FAIL" = 0 ] && ok "T2: all present in $(ls "$RES"/values*/strings.xml | wc -l) locale files"

echo "== T3: the cockpit's apps are Account ▸ Runtime / Drift's apps (#778), and they are data =="
AR="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/AccountRuntime.kt"
AT="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/AccountTabs.kt"
AM="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/AccountModel.kt"
# #783 Runtime draws Drift's apps (m.apps): the cockpit layout first, then every fleet app's settings.
grep -qF 'VaultCockpit.layout.sections.map { section ->' "$AR" && grep -qF 'for (section in m.apps) {' "$AT" \
    && ok "T3: Runtime reads, and draws, every app of the baked layout" || bad "T3: Runtime does not iterate ui.vault_connect.cockpit.sections"
grep -qF 'VaultCockpit.layout.sections.map { AccountDrift.App(it.id, it.label, it.vault) }' "$AM" \
    && ok "T3: Drift's apps — which vault sections each consumes — are the same layout" || bad "T3: Drift's app ownership is not the cockpit layout"
# The reader dispatches on each section's declared APPLIER (`apply`); a `reports: false` app has none.
DECLARED=$(jq -r '.ui.vault_connect.cockpit.sections[] | select(.runtime.reports != false) | .apply' "$BJ" | sort)
DISPATCHED=$(awk '/    private fun readOne\(/{f=1} f&&/return when \(section.apply\) \{/{g=1;next} g&&/^            else ->/{exit} g' "$AR" | grep -oE '^            "[a-z]+" ->' | grep -oE '[a-z]+' | sort)
[ -n "$DISPATCHED" ] && ok "T3: the runtime reader dispatches on $(echo $DISPATCHED | wc -w) declared appliers" || bad "T3: no when(section.apply) dispatch found"
for id in $DISPATCHED; do
    grep -qx "$id" <<<"$DECLARED" && ok "T3: read app '$id' is declared" || bad "T3: the reader dispatches on '$id', which build.json does not declare"
done
for id in $DECLARED; do
    grep -qx "$id" <<<"$DISPATCHED" && ok "T3: declared app '$id' has a reader" || bad "T3: build.json declares '$id' but nothing reads it"
done
LABELS=$(jq -r '.ui.vault_connect.cockpit.sections[].label' "$BJ")
while IFS= read -r l; do
    grep -qF "\"$l\"" <<<"$(codeof "$PF"; codeof "$AR"; codeof "$AT")" && bad "T3: section label '$l' is also a Kotlin literal" || ok "T3: label '$l' lives only in build.json"
done <<< "$LABELS"
VAULT_IDS=$(jq -r '.ui.vault_connect.cockpit.sections[].vault[]' "$BJ" | sort -u)
# #695 the vault re-lettered E0_configs/ → C_A1-configs/; the old path made this
# cross-check silently skip (a moved path disarms a tester).
SCHEMA="$APP/../../cloud-vault/C_A1-configs/schema.json"
[ -f "$SCHEMA" ] || SCHEMA="$(cd "$APP/../.." 2>/dev/null && pwd)/cloud-vault/C_A1-configs/schema.json"
if [ -f "$SCHEMA" ]; then
    for v in $VAULT_IDS; do
        if jq -e --arg v "$v" '.sections[] | select(.id == $v)' "$SCHEMA" >/dev/null; then ok "T3: vault section '$v' exists in cloud-vault schema.json"
        elif jq -e --arg v "$v" '.ui.profile.infos.schema.sections[] | select(.id == $v and .staged == true)' "$BJ" >/dev/null; then ok "T3: vault section '$v' is staged (declared staged in ui.profile.infos.schema, not yet in cloud-vault schema.json)"
        else bad "T3: cockpit names vault section '$v', which cloud-vault schema.json does not declare"; fi
    done
else
    ok "T3: (cloud-vault checkout not beside this repo — vault section ids not cross-checked here)"
fi

echo "== T4: reading writes nothing; every apply is a tap =="
# The fetch path (VaultConnect.kt, the fragment's vault* functions) and the runtime
# readers (AccountRuntime.read / readOne) may write no store and run no apply.
RENDER_FNS=$(awk '/private fun (vault[A-Za-z]*|showVaultFailure)\(/{f=1} f{print} /^    }$/{f=0}' "$PF")
READ_FNS=$(awk '/    fun read\(ctx: Context/{f=1} f{print} /^    \/\/ ── pushing/{f=0}' "$AR")
[ -n "$RENDER_FNS" ] && [ -n "$READ_FNS" ] && ok "T4: found the fetch functions and the runtime readers" || bad "T4: fetch or reader functions not found"
for pat in 'ConfigAutoImport' '.edit()' 'putString' 'putSecret' 'setAutheliaCredential' 'writeText' 'hydrateFromConfig' 'setAiRouting' 'applyMesh' 'applyMail' 'setProfileField'; do
    if grep -qF "$pat" "$VC" || grep -qF "$pat" <<<"$RENDER_FNS" || grep -qF "$pat" <<<"$READ_FNS"; then
        bad "T4: the fetch/read path touches $pat"
    else
        ok "T4: no $pat on the fetch/read path"
    fi
done
# Every write is AccountRuntime.push; push is called only by the model's server → runtime,
# which the Drift tab calls only from a click and the debug API only from its sync op.
[ "$(grep -c 'AccountRuntime.push(' "$AM")" = 1 ] && grep -qF 'fun pushServerToRuntime(' "$AM" \
    && ok "T4: AccountRuntime.push has one caller, the model's server → runtime" || bad "T4: AccountRuntime.push is called from more than the model's push"
grep -q . <<<"$(grep -rn 'AccountRuntime.push(' "$APP/app/src/main/java" | grep -v 'AccountModel.kt')" && bad "T4: something else pushes into an app" || ok "T4: nothing else pushes into an app"
PUSHES=$(grep -n 'm.pushServerToRuntime(' "$AT" | cut -d: -f1)
[ -n "$PUSHES" ] && ok "T4: $(echo "$PUSHES" | wc -l) push call sites on Drift" || bad "T4: Drift never pushes"
for ln in $PUSHES; do
    grep -qE 'onClick = \{|ActionButton\(' <<<"$(sed -n "$((ln-1)),${ln}p" "$AT")" && ok "T4: the push at AccountTabs.kt:$ln is behind a tap" || bad "T4: the push at AccountTabs.kt:$ln is not behind a tap"
done
grep -qF 'ConfigAutoImport' "$PF" && bad "T4: the per-peer config apply survives #781" || ok "T4: no per-peer config apply on the page (#781)"
grep -q 'fun applyMesh' "$CP" && grep -q 'Config.parse' "$CP" \
    && ok "T4: the mesh apply goes through the WireGuard parser" || bad "T4: mesh apply does not parse"

echo "== T5: the code and the browser session are never stored =="
grep -q 'box.setText("")' <<<"$RENDER_FNS" && ok "T5: the code box is emptied once sent" \
                                               || bad "T5: the code stays on screen after use"
grep -qE 'Prefs\(.*\)\.[a-z]+ *= *code' <<<"$RENDER_FNS" && bad "T5: the code is written to prefs" \
                                                          || ok "T5: the code is never assigned into prefs"
grep -q 'private var vaultSession: String? = null' "$PF" && ok "T5: the browser session is a fragment field" \
                                                          || bad "T5: no in-memory session field"
grep -qE 'Prefs|edit\(|putString' <<<"$(codeof "$PF" | grep -E 'vaultSession')" && bad "T5: the session reaches a store" \
                                                                          || ok "T5: the session never reaches a store"
grep -q 'class Cookie' "$VC" && grep -q '"Cookie" to cookie' "$VC" \
    && ok "T5: the session travels as a Cookie header (same gate, Remote-User)" || bad "T5: no cookie auth on the vault route"

echo "== T6: the device is chosen, never typed =="
grep -q 'fun selectDevice(ctx: Context, id: String)' "$CP" && ok "T6: only an id is stored for the device" \
                                                            || bad "T6: device selection API changed"
grep -q 'fun devices(bundle: JSONObject)' "$CP" && grep -q '"wg_peer"' "$CP" \
    && ok "T6: devices come from the bundle's electronics wg_peer entries" || bad "T6: devices are not derived from the vault"
grep -q 'addressesOf(conf).any { it in mine }' "$CP" \
    && ok "T6: a profile is the device's when its Address line carries the declared address" || bad "T6: mesh ownership is not by address"
if grep -qE '10\.0\.0\.[0-9]+|fd0c:1d0[01]::|termux|galaxy|surface' <<<"$(codeof "$CP")"; then
    bad "T6: an address, hostname or device name is a Kotlin literal in VaultCockpit"
else
    ok "T6: no address, hostname or device name literal in VaultCockpit"
fi
grep -qF 'private fun buildDeviceStep(' "$PF" && grep -qF 'VaultCockpit.selectDevice(ctx, p.vaultDevice)' "$PF" \
    && ok "T6: the device is picked from the vault's peers on Connect (journey step 3), never typed" || bad "T6: no device pick"

echo "== T7: Apps reuses #565 =="
grep -q 'AppInventory.parse(' "$CP" && grep -q 'AppInventory.KIND' "$CP" \
    && ok "T7: an inventory in the vault is read by the one parser" || bad "T7: a second inventory parser"
grep -qF 'VaultCockpit.appsDeclared(declared!!, picked.first, fleet)' "$AR" && ok "T7: Runtime's apps reading is the same declared set" || bad "T7: Runtime counts apps some other way"
grep -qE 'ACTION_INSTALL_PACKAGE|installPackage\(' <<<"$(codeof "$PF"; codeof "$AR"; codeof "$AT")" && bad "T7: the Account installs on its own" \
                                                                   || ok "T7: no installer in the Account (the apps topic links into the Store)"

echo "== T8: the cockpit chrome (#570 reopened; since #778 it draws the Connect journey — the Setup cockpit page is gone) =="
FV="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/FleetCockpitView.kt"
FT="$APP/app/src/test/java/com/diegonmarcos/superapp/profile/FleetCockpitViewTest.kt"
IDS="$RES/values/ids.xml"
[ -f "$FV" ] && ok "T8: FleetCockpitView.kt exists" || bad "T8: no FleetCockpitView.kt — the chrome was not split out"
# The render path builds the hero and one card per section through the chrome object.
# The OLD idiom — a bare headline per section — is gone from the render path.
grep -q 'sectionHeader(ctx, section.label)' <<<"$RENDER_FNS" \
    && bad "T8: the render path still draws the OLD headline-per-section page" \
    || ok "T8: no headline-per-section on the render path"
grep -q 'sectionHeader(ctx, getString(R.string.vault_cockpit_raw))' <<<"$RENDER_FNS" \
    && bad "T8: the raw remainder is still the OLD headline, not a card" \
    || ok "T8: the raw remainder is a card too"
# Lights: the shared component, painted from the model's summing, never a literal.
grep -q 'fun sectionLight(rows: List<Row>, observed: Boolean' "$CP" && grep -q 'fun overallLight(' "$CP" \
    && ok "T8: the card and hero lights are summed in the model" || bad "T8: no sectionLight/overallLight in VaultCockpit"
grep -q 'StatusLight.text(ctx, state)' "$FV" && grep -q 'StatusLight.colour(ctx, state)' "$FV" && grep -q 'StatusLight.description(ctx, rowLabel, state)' "$FV" \
    && ok "T8: the chrome paints glyph, colour and spoken description from StatusLight" || bad "T8: the chrome does not paint from StatusLight"
grep -qE '0x[0-9A-Fa-f]{6,8}|Color\.parseColor|#[0-9A-Fa-f]{6}' <<<"$(codeof "$FV")" \
    && bad "T8: FleetCockpitView carries a colour literal — a private copy of a palette or light colour" \
    || ok "T8: no colour literal in the chrome (palette + StatusLight only)"
grep -q 'private val NEUTRAL = 0x' <<<"$(codeof "$PF")" && bad "T8: the fragment still owns a private grey" || ok "T8: the fragment's grey is StatusLight's Unknown"
# Power-Saving-safe: drawn once, no animation, no ticker.
grep -qE 'animate\(\)|ObjectAnimator|ValueAnimator|postDelayed|Handler\(' <<<"$(codeof "$FV")" \
    && bad "T8: the chrome animates or ticks — not Power-Saving-safe" || ok "T8: the chrome draws once, no animation, no ticker"
# The circle-icon language: OVAL badges, an orb on the hero.
grep -q 'GradientDrawable.OVAL' "$FV" && ok "T8: badges are OVAL (the homescreen circle-icon language)" || bad "T8: no round badge in the chrome"
# Badge icons are DATA: every cockpit section declares one and it is a drawable of this app.
for id in $DECLARED; do
    icon=$(jq -r --arg id "$id" '.ui.vault_connect.cockpit.sections[] | select(.id == $id) | .icon // ""' "$BJ")
    [ -n "$icon" ] || { bad "T8: section '$id' declares no icon"; continue; }
    [ -f "$RES/drawable/$icon.xml" ] && ok "T8: '$id' badge icon $icon is a drawable" || bad "T8: '$id' declares icon '$icon' but res/drawable has no $icon.xml"
done
jq -e '.ui.vault_connect.cockpit.device_icons._default' "$BJ" >/dev/null && ok "T8: a default device icon is declared" || bad "T8: no device_icons._default"
for icon in $(jq -r '.ui.vault_connect.cockpit.device_icons[]' "$BJ"); do
    [ -f "$RES/drawable/$icon.xml" ] && ok "T8: device icon $icon is a drawable" || bad "T8: device icon '$icon' has no drawable"
done
grep -qE '"ic_[a-z_]+"' <<<"$(codeof "$FV" "$PF")" && bad "T8: an icon name is a Kotlin literal on the cockpit" || ok "T8: icon names live only in build.json"
# #781 the keyboard is read now; what cannot be read is an app that reports nothing (cloud-drive's token).
jq -e '[.ui.vault_connect.cockpit.sections[] | select(.runtime.reports == false) | select(.observed != false)] | length == 0' "$BJ" >/dev/null \
    && jq -e '[.ui.vault_connect.cockpit.sections[] | select(.observed == false)] | length > 0' "$BJ" >/dev/null \
    && ok "T8: every app that reports nothing is declared unobservable as data (its light is Not verifiable, not a guessed colour)" \
    || bad "T8: an app that reports nothing is not declared observed:false — its light would be a guess"
# Infos (the fetched configs) is the default tab once the journey has been walked
# (#573/#695: before that, the page opens on Connect — where the sign-in and the
# fetch live, and there is nothing to read or apply yet).
# The layout-tree test exists and reads the declared ids, which exist.
[ -f "$FT" ] && ok "T8: FleetCockpitViewTest.kt exists" || bad "T8: no layout-tree test"
for id in cockpit_hero cockpit_device_orb cockpit_hero_light cockpit_card cockpit_card_badge cockpit_card_light cockpit_card_body; do
    grep -q "name=\"$id\"" "$IDS" || bad "T8: ids.xml lacks $id"
    grep -q "R.id.$id" "$FV" || bad "T8: the chrome never sets R.id.$id"
    [ -f "$FT" ] && { grep -q "R.id.$id" "$FT" || bad "T8: the layout-tree test never reads R.id.$id"; }
done
ok "T8: the seven cockpit ids are declared, set by the chrome and read by the test"
[ -f "$FT" ] && grep -q 'GradientDrawable.OVAL' "$FT" && grep -q 'StatusLight.text(ctx, state)' "$FT" \
    && ok "T8: the test measures the orb shape and the shared light vocabulary" || bad "T8: the test does not measure shape and light"
grep -q 'FleetCockpitViewTest\|VaultCockpitTest' "$GR" && bad "T8: a test class is named in gradle (should be found by the unit task, not listed)" || ok "T8: tests are discovered, not listed"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
