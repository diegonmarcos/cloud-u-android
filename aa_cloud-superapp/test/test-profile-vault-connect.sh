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
#   T3  the Fleet tab is in the strip, its index is read off the list, and
#       every cockpit section id the fragment dispatches on is declared in
#       build.json (and vice versa) — the layout is data
#   T4  RENDERING WRITES NOTHING: the fetch and render path runs no apply and
#       touches no store; every apply* is reached only from a button
#   T5  the one-time code and the browser session are never stored
#   T6  the device is CHOSEN, never typed: only its id is persisted, and no
#       address, key or hostname is a Kotlin literal on the mesh path
#   T7  the Apps section reuses the #565 inventory, plan and summary — no
#       second exporter, no second installer
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
VC="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/VaultConnect.kt"
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
# #573: the sign_in blob is read by SignIn.kt, the journey's provider registry.
SI="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/SignIn.kt"
for f in $FIELDS; do
    grep -q "BuildConfig.$f" "$PF" "$VC" "$CP" "$SI" && ok "T1: the profile package reads $f" \
                                                   || bad "T1: $f is baked but never read"
done

echo "== T2: every vault string exists in every locale =="
USED=$(grep -ohE 'R\.string\.vault_[a-z_]+' "$PF" "$VC" "$CP" | sed 's/R\.string\.//' | sort -u)
[ -n "$USED" ] && ok "T2: $(echo "$USED" | wc -l) vault strings used" || bad "T2: no R.string.vault_* used — labels are literals"
for loc in "$RES"/values*/strings.xml; do
    for s in $USED; do
        grep -q "name=\"$s\"" "$loc" || bad "T2: $s missing from ${loc#$APP/}"
    done
done
[ "$FAIL" = 0 ] && ok "T2: all present in $(ls "$RES"/values*/strings.xml | wc -l) locale files"

echo "== T3: the Fleet tab, and its sections are data =="
grep -q 'Tab(getString(R.string.vault_tab_imported), imported)' "$PF" \
    && ok "T3: the Fleet tab has its own column" || bad "T3: no Fleet tab in the strip"
grep -q 'importedTab = tabs.indexOfFirst { it.column === imported }' "$PF" \
    && ok "T3: its index is read off the tab list" || bad "T3: the Fleet index is not derived from the list"
grep -q 'for (section in VaultCockpit.layout.sections)' "$PF" \
    && ok "T3: the tab iterates the baked layout" || bad "T3: the tab does not iterate ui.vault_connect.cockpit.sections"
DECLARED=$(jq -r '.ui.vault_connect.cockpit.sections[].id' "$BJ" | sort)
DISPATCHED=$(awk '/when \(section.id\) \{/{f=1;next} f&&/^ *\}/{f=0} f' "$PF" | grep -oE '^ *"[a-z]+"' | tr -d ' "' | sort)
[ -n "$DISPATCHED" ] && ok "T3: the fragment dispatches on $(echo $DISPATCHED | wc -w) section ids" || bad "T3: no when(section.id) dispatch found"
for id in $DISPATCHED; do
    echo "$DECLARED" | grep -qx "$id" && ok "T3: dispatched section '$id' is declared" \
                                      || bad "T3: the fragment dispatches on '$id', which build.json does not declare"
done
for id in $DECLARED; do
    echo "$DISPATCHED" | grep -qx "$id" && ok "T3: declared section '$id' has a renderer" \
                                        || bad "T3: build.json declares '$id' but nothing renders it (it would fall to raw)"
done
LABELS=$(jq -r '.ui.vault_connect.cockpit.sections[].label' "$BJ")
while IFS= read -r l; do
    codeof "$PF" | grep -qF "\"$l\"" && bad "T3: section label '$l' is also a Kotlin literal" || ok "T3: label '$l' lives only in build.json"
done <<< "$LABELS"
VAULT_IDS=$(jq -r '.ui.vault_connect.cockpit.sections[].vault[]' "$BJ" | sort -u)
SCHEMA="$APP/../../cloud-vault/E0_configs/schema.json"
[ -f "$SCHEMA" ] || SCHEMA="$(cd "$APP/../.." 2>/dev/null && pwd)/cloud-vault/E0_configs/schema.json"
if [ -f "$SCHEMA" ]; then
    for v in $VAULT_IDS; do
        if jq -e --arg v "$v" '.sections[] | select(.id == $v)' "$SCHEMA" >/dev/null; then ok "T3: vault section '$v' exists in cloud-vault schema.json"
        elif [ "$v" = apps ]; then ok "T3: vault section 'apps' is staged (cloud-vault E0_configs/apps, not yet in schema.json — #570 gap)"
        else bad "T3: cockpit names vault section '$v', which cloud-vault schema.json does not declare"; fi
    done
else
    ok "T3: (cloud-vault checkout not beside this repo — vault section ids not cross-checked here)"
fi

echo "== T4: rendering writes nothing; every apply is a tap =="
# The fetch + render path: VaultConnect.kt, the vault* functions and every
# render* function in the fragment. None may write a store or run an apply.
RENDER_FNS=$(awk '/private fun (vault[A-Za-z]*|showVaultFailure|render[A-Za-z]*|importedValue)\(/{f=1} f{print} /^    }$/{f=0}' "$PF")
[ -n "$RENDER_FNS" ] && ok "T4: found the fetch + render functions" || bad "T4: render functions not found"
for pat in 'ConfigAutoImport' '.edit()' 'putString' 'putSecret' 'setAutheliaCredential' 'writeText' 'hydrateFromConfig' 'setAiRouting'; do
    if grep -qF "$pat" "$VC" || echo "$RENDER_FNS" | grep -v 'VaultCockpit.apply' | grep -qF "$pat"; then
        bad "T4: the fetch/render path touches $pat"
    else
        ok "T4: no $pat on the fetch/render path"
    fi
done
# Every VaultCockpit.apply* call in the fragment sits inside a click lambda —
# an applyButton / pickButton / dialog button — never at render level.
APPLIES=$(grep -n 'VaultCockpit.apply' "$PF" | cut -d: -f1)
[ -n "$APPLIES" ] && ok "T4: $(echo "$APPLIES" | wc -l) apply call sites" || bad "T4: no VaultCockpit.apply* call in the fragment"
for ln in $APPLIES; do
    ctx=$(sed -n "$((ln-8)),${ln}p" "$PF")
    echo "$ctx" | grep -qE 'applyButton\(|setPositiveButton\(' && ok "T4: apply at line $ln is behind a button" \
                                                               || bad "T4: apply at line $ln is not behind a button"
done
grep -q 'fun applyMesh' "$CP" && grep -q 'Config.parse' "$CP" \
    && ok "T4: the mesh apply goes through the WireGuard parser" || bad "T4: mesh apply does not parse"

echo "== T5: the code and the browser session are never stored =="
echo "$RENDER_FNS" | grep -q 'box.setText("")' && ok "T5: the code box is emptied once sent" \
                                               || bad "T5: the code stays on screen after use"
echo "$RENDER_FNS" | grep -qE 'Prefs\(.*\)\.[a-z]+ *= *code' && bad "T5: the code is written to prefs" \
                                                          || ok "T5: the code is never assigned into prefs"
grep -q 'private var vaultSession: String? = null' "$PF" && ok "T5: the browser session is a fragment field" \
                                                          || bad "T5: no in-memory session field"
codeof "$PF" | grep -E 'vaultSession' | grep -qE 'Prefs|edit\(|putString' && bad "T5: the session reaches a store" \
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
if codeof "$CP" | grep -qE '10\.0\.0\.[0-9]+|fd0c:1d0[01]::|termux|galaxy|surface'; then
    bad "T6: an address, hostname or device name is a Kotlin literal in VaultCockpit"
else
    ok "T6: no address, hostname or device name literal in VaultCockpit"
fi
grep -q 'android.widget.Spinner' "$PF" && ok "T6: the selector is a pick, not a text field" || bad "T6: no device spinner"

echo "== T7: Apps reuses #565 =="
grep -q 'AppInventory.entriesFor' "$PF" && grep -q 'AppInventory.toJson' "$PF" \
    && ok "T7: the export is AppInventory's" || bad "T7: a second exporter"
grep -q 'AppInventory.plan(' "$PF" && grep -q 'StoreImport.show(this, plan)' "$PF" \
    && ok "T7: the compare is AppInventory.plan + StoreImport.show" || bad "T7: a second plan/summary"
grep -q 'AppInventory.parse(' "$CP" && grep -q 'AppInventory.KIND' "$CP" \
    && ok "T7: an inventory in the vault is read by the one parser" || bad "T7: a second inventory parser"
codeof "$PF" | grep -qE 'ACTION_INSTALL_PACKAGE|installPackage\(' && bad "T7: the profile installs on its own" \
                                                                   || ok "T7: no installer in the profile"
NAME_PF=$(grep -oE 'APPS_EXPORT_NAME = "[^"]+"' "$PF" | cut -d'"' -f2)
NAME_STORE=$(grep -oE 'EXPORT_NAME = "[^"]+"' "$APP/../ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StorePhoneFragment.kt" | cut -d'"' -f2)
[ -n "$NAME_PF" ] && [ "$NAME_PF" = "$NAME_STORE" ] && ok "T7: same export file name as the Store ($NAME_PF)" \
                                                     || bad "T7: export file name differs from the Store's ($NAME_PF vs $NAME_STORE)"

echo "== T8: the Fleet tab is the cockpit (#570 reopened) =="
FV="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/FleetCockpitView.kt"
FT="$APP/app/src/test/java/com/diegonmarcos/superapp/profile/FleetCockpitViewTest.kt"
IDS="$RES/values/ids.xml"
[ -f "$FV" ] && ok "T8: FleetCockpitView.kt exists" || bad "T8: no FleetCockpitView.kt — the chrome was not split out"
# The render path builds the hero and one card per section through the chrome object.
echo "$RENDER_FNS" | grep -q 'FleetCockpitView.hero(' && ok "T8: the Fleet tab draws a hero" || bad "T8: no hero on the Fleet tab"
echo "$RENDER_FNS" | grep -q 'FleetCockpitView.card(ctx, section.label, section.id' \
    && ok "T8: one card per declared section, labelled and tagged from the declaration" \
    || bad "T8: the sections are not drawn as FleetCockpitView cards"
# The OLD idiom — a bare headline per section — is gone from the render path.
echo "$RENDER_FNS" | grep -q 'sectionHeader(ctx, section.label)' \
    && bad "T8: the render path still draws the OLD headline-per-section page" \
    || ok "T8: no headline-per-section on the render path"
echo "$RENDER_FNS" | grep -q 'sectionHeader(ctx, getString(R.string.vault_cockpit_raw))' \
    && bad "T8: the raw remainder is still the OLD headline, not a card" \
    || ok "T8: the raw remainder is a card too"
# Lights: the shared component, painted from the model's summing, never a literal.
grep -q 'fun sectionLight(rows: List<Row>, observed: Boolean' "$CP" && grep -q 'fun overallLight(' "$CP" \
    && ok "T8: the card and hero lights are summed in the model" || bad "T8: no sectionLight/overallLight in VaultCockpit"
grep -q 'StatusLight.text(ctx, state)' "$FV" && grep -q 'StatusLight.colour(ctx, state)' "$FV" && grep -q 'StatusLight.description(ctx, rowLabel, state)' "$FV" \
    && ok "T8: the chrome paints glyph, colour and spoken description from StatusLight" || bad "T8: the chrome does not paint from StatusLight"
codeof "$FV" | grep -qE '0x[0-9A-Fa-f]{6,8}|Color\.parseColor|#[0-9A-Fa-f]{6}' \
    && bad "T8: FleetCockpitView carries a colour literal — a private copy of a palette or light colour" \
    || ok "T8: no colour literal in the chrome (palette + StatusLight only)"
codeof "$PF" | grep -q 'private val NEUTRAL = 0x' && bad "T8: the fragment still owns a private grey" || ok "T8: the fragment's grey is StatusLight's Unknown"
# Power-Saving-safe: drawn once, no animation, no ticker.
codeof "$FV" | grep -qE 'animate\(\)|ObjectAnimator|ValueAnimator|postDelayed|Handler\(' \
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
codeof "$FV" "$PF" | grep -qE '"ic_[a-z_]+"' && bad "T8: an icon name is a Kotlin literal on the cockpit" || ok "T8: icon names live only in build.json"
jq -e '[.ui.vault_connect.cockpit.sections[] | select(.observed == false)] | length > 0' "$BJ" >/dev/null \
    && ok "T8: an unobservable section is declared as data (its light is Not verifiable, not a guessed colour)" \
    || bad "T8: no section declares observed:false — the keyboard's light would be a guess"
# Fleet is the default tab once the journey has been walked (#573: before that,
# the page opens on Connect — the cockpit has nothing to compare against yet).
grep -q 'selectedTab = if (VaultConnect.Imported.bundle == null && !ProfileJourney.allDone(journeyState(ctx))) connectTab else importedTab' "$PF" \
    && ok "T8: the page opens on Fleet once the journey is walked, on Connect before" || bad "T8: the page does not open on Fleet after the journey"
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
