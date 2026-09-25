#!/usr/bin/env bash
# Tester (#573): Configs ▸ Profile ▸ Connect is THE JOURNEY — sign in → who →
# which device → get everything — one designed flow in the #570 cockpit's chrome.
#
# The behaviour (the state machine and its layout tree, provider parsing, the
# device-grant steps, the registry walk, the strip geometry) runs on the JVM:
# ProfileJourneyTest, SignInTest, UserRegistryTest, AppTabsStyleTest. This file
# pins what a JVM test cannot see:
#   T1  the provider list is DATA: build.json::ui.vault_connect.sign_in declares
#       FOUR ways in (#578: Authelia bearer, Authelia web-auth, GitHub, Google),
#       unique ids and labels, exactly ONE primary, kinds the code dispatches
#       on, the two SSO ways of DISTINCT kinds, Google inert (empty client id —
#       never invented), baked to ONE BuildConfig field; the GitHub-only fields are gone
#   T2  nothing in Kotlin names a provider, an endpoint, a client id, a user, an
#       address or a device — the seed lives in cloud-infra's superapp-users.json
#   T3  every journey_* / sign_in_* string the Kotlin uses exists in EVERY locale,
#       and no declared one is dead
#   T4  the Connect tab IS the journey: the tab builds renderJourney and nothing
#       else; the four steps of ProfileJourney, in order; every card tagged step:*;
#       the step badges are data (cockpit.journey_icons names every step); the
#       chrome is the cockpit's (no colour literal); and the OLD surface is GONE —
#       the account-email box, the bearer box, the permanent mail-code box, the
#       Vault configs header, the Imports row, the GitHub-only dialog, the
#       first-pass spinners
#   T5  the device-grant token and the session never reach a store; a bearer is
#       stored only through ConfigsPrefs.setAutheliaCredential after it proved itself
#   T6  the picks persist ids only; the peer pick selects the cockpit device; a
#       fetch REMEMBERS (and caches the registry) but never applies; step 4's
#       Apply is the only apply, and it writes the chosen peer's profiles
#   T7  (cross-repo, when cloud-infra sits beside) every auth_providers id is a
#       declared provider here; one primary identity, one primary peer; every
#       peer on a mesh
#   T8  mutation: a scratch ProfileFragment whose Connect tab no longer builds the
#       journey turns T4 RED
#   T9  mutation (#578): the four-way check turns RED when the web-auth way is
#       folded back into the bearer's kind, when the fragment sends the web pill
#       to the bearer dialog, and when Google is given an invented client id
set -uo pipefail
APP="${SA_APP:-$(cd "$(dirname "$0")/.." && pwd)}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
GR="$APP/app/build.gradle"
PKG="$APP/app/src/main/java/com/diegonmarcos/superapp/profile"
PF="$PKG/ProfileFragment.kt"
PJ="$PKG/ProfileJourney.kt"
PV="$PKG/ProfileJourneyView.kt"
SI="$PKG/SignIn.kt"
UR="$PKG/UserRegistry.kt"
CA="$PKG/ConfigAutoImport.kt"
RES="$APP/app/src/main/res"
for f in "$BJ" "$GR" "$PF" "$PJ" "$PV" "$SI" "$UR" "$CA"; do
    [ -f "$f" ] || { echo "FAIL: $f missing — this tester is unrun, not passing"; exit 1; }
done

# Code only — whole-line comments dropped, so prose about what must not
# happen does not read as it happening.
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }

echo "== T1: the provider list is data =="
N=$(jq '.ui.vault_connect.sign_in.providers | length' "$BJ" 2>/dev/null || echo 0)
[ "${N:-0}" -ge 4 ] && ok "T1: $N providers declared" || bad "T1: fewer than 4 providers declared ($N)"
IDS=$(jq -r '.ui.vault_connect.sign_in.providers[].id' "$BJ")
[ "$(echo "$IDS" | sort | uniq -d | wc -l)" = 0 ] && ok "T1: provider ids are unique" || bad "T1: duplicate provider id"
P=$(jq '[.ui.vault_connect.sign_in.providers[] | select(.primary == true)] | length' "$BJ")
[ "$P" = 1 ] && ok "T1: exactly one primary provider" || bad "T1: $P primary providers"
for k in $(jq -r '.ui.vault_connect.sign_in.providers[].kind' "$BJ" | sort -u); do
    grep -q "\"$k\" -> Kind\." "$SI" && ok "T1: kind '$k' is dispatched by SignIn.kt" || bad "T1: kind '$k' is declared but SignIn.kt does not dispatch on it"
done
for id in $IDS; do
    g=$(jq -r --arg id "$id" '.ui.vault_connect.sign_in.providers[] | select(.id == $id) | .grants | length' "$BJ")
    [ "${g:-0}" -ge 1 ] && ok "T1: '$id' grants something" || bad "T1: '$id' grants nothing"
done
grep -q '"UI_VAULT_CONNECT_SIGN_IN_B64"' "$GR" && grep -q 'BuildConfig.UI_VAULT_CONNECT_SIGN_IN_B64' "$SI" \
    && ok "T1: one BuildConfig field, read by SignIn.kt" || bad "T1: the sign_in blob is not baked or not read"
grep -qE 'UI_GH_OAUTH|github_oauth' "$GR" && bad "T1: gradle still bakes the GitHub-only OAuth fields" || ok "T1: no GitHub-only OAuth field in gradle"
jq -e '.ui.config_source.github_oauth' "$BJ" >/dev/null 2>&1 && bad "T1: build.json still carries ui.config_source.github_oauth" || ok "T1: the OAuth client lives only in sign_in"

# ── the four ways in (#578), as a function so T9 can run it on mutated copies ──
# $1 = build.json, $2 = ProfileFragment.kt; prints the first broken rule, returns non-zero on one.
fourways() {
    local bj="$1" pf="$2" k
    [ "$(jq '.ui.vault_connect.sign_in.providers | length' "$bj")" = 4 ] || { echo "not four providers"; return 1; }
    [ "$(jq '[.ui.vault_connect.sign_in.providers[].label] | unique | length' "$bj")" = 4 ] || { echo "labels are not unique"; return 1; }
    for k in authelia_bearer authelia_web; do
        [ "$(jq --arg k "$k" '[.ui.vault_connect.sign_in.providers[] | select(.kind == $k)] | length' "$bj")" = 1 ] \
            || { echo "kind $k is not declared exactly once"; return 1; }
    done
    [ "$(jq '[.ui.vault_connect.sign_in.providers[] | select(.kind == "device_flow")] | length' "$bj")" = 2 ] || { echo "not two device-flow providers"; return 1; }
    # Google: declared, and inert until the owner mints a client id — never invented here.
    [ "$(jq -r '[.ui.vault_connect.sign_in.providers[] | select(.kind == "device_flow" and (.client_id // "") == "")] | length' "$bj")" = 1 ] \
        || { echo "exactly one device-flow provider (Google) must carry an empty client_id"; return 1; }
    # The fragment gives each SSO way its own branch and its own dialog — no shared path.
    local code; code=$(codeof "$pf")
    echo "$code" | awk '/SignIn.Kind.AUTHELIA_WEB ->/{getline l; print l}' | grep -q 'showAutheliaWebAuthDialog()' \
        || { echo "the web-auth pill does not open the web-auth dialog"; return 1; }
    echo "$code" | awk '/SignIn.Kind.AUTHELIA_BEARER ->/{getline l; print l}' | grep -q 'showAutheliaBearerDialog()' \
        || { echo "the bearer pill does not open the bearer dialog"; return 1; }
    echo "$code" | grep -q 'via = autheliaProvider(SignIn.Kind.AUTHELIA_WEB)' \
        || { echo "the web-auth session is not recorded against the web-auth provider"; return 1; }
    return 0
}
echo "== T1b: FOUR ways in — Authelia bearer, Authelia web-auth, GitHub, Google =="
msg=$(fourways "$BJ" "$PF") && ok "T1b: four ways, two distinct SSO kinds, Google inert, each SSO way has its own dialog" || bad "T1b: $msg"
jq -e '.ui.vault_connect.sign_in.providers[] | select(.primary == true) | select(.kind == "authelia_bearer")' "$BJ" >/dev/null \
    && ok "T1b: the primary is the bearer way" || bad "T1b: the primary provider is not the bearer way"
grep -q '"authelia_bearer" -> Kind.AUTHELIA_BEARER' "$SI" && grep -q '"authelia_web" -> Kind.AUTHELIA_WEB' "$SI" \
    && ok "T1b: SignIn.kt dispatches both SSO kinds" || bad "T1b: SignIn.kt does not map both SSO kinds"

echo "== T2: no provider, endpoint, client id, user, address or device literal in Kotlin =="
for id in $IDS; do
    codeof "$SI" "$PF" "$PJ" "$PV" "$UR" "$CA" | grep -v -- '-> Kind\.' | grep -qE "\"$id\"" && bad "T2: provider id '$id' is a Kotlin literal" || ok "T2: '$id' is not a Kotlin literal"
done
for u in $(jq -r '.ui.vault_connect.sign_in.providers[] | .device_code_url // empty, .token_url // empty, .userinfo_url // empty, (.client_id | select(. != "" and . != null))' "$BJ"); do
    codeof "$SI" "$PF" "$PJ" "$PV" | grep -qF "$u" && bad "T2: '$u' is a Kotlin literal" || ok "T2: '$u' lives only in build.json"
done
if codeof "$SI" "$PF" "$PJ" "$PV" "$UR" "$CA" | grep -qiE '@diegonmarcos\.com|diego coelho|samsung|surface|galaxy|termux|10\.0\.0\.[0-9]+|fd0c:1d0'; then
    bad "T2: a user, address or device name is a Kotlin literal in the profile package"
else
    ok "T2: no user, address or device literal in the profile package"
fi

echo "== T3: every journey string exists in every locale, none is dead =="
USED=$(grep -ohE 'R\.string\.(journey|sign_in)_[a-z_]+' "$PF" "$PJ" "$PV" "$SI" | sed 's/R\.string\.//' | sort -u)
[ "$(echo "$USED" | grep -c .)" -ge 40 ] && ok "T3: $(echo "$USED" | grep -c .) journey strings used" || bad "T3: too few journey strings used — labels are literals"
for loc in "$RES"/values*/strings.xml; do
    for s in $USED; do
        grep -q "name=\"$s\"" "$loc" || bad "T3: $s missing from ${loc#$APP/}"
    done
done
DEAD=$(grep -oE 'name="(journey|sign_in)_[a-z_]+"' "$RES/values/strings.xml" | sed 's/name="//; s/"//' | sort -u | comm -23 - <(echo "$USED"))
[ -z "$DEAD" ] && ok "T3: no dead journey string" || bad "T3: declared but unused: $(echo "$DEAD" | tr '\n' ' ')"
[ "$FAIL" = 0 ] && ok "T3: all present in $(ls "$RES"/values*/strings.xml | wc -l) locale files"

# ── T4 as a function, so T8 can run it on a mutated copy ──
t4() {   # $1 = ProfileFragment path; prints nothing, returns 0 when the tab is the journey
    local pf="$1"
    grep -q 'renderJourney(ctx, connect)' "$pf" || return 1
    # Between the Connect marker and the Fleet render, the tab builds the journey and nothing else.
    local block
    # The markers are comment lines, so slice the raw file first, then drop comments.
    block=$(awk '/Connect \(tab 1\)/{f=1} f{print} f&&/renderImported\(ctx, imported\)/{exit}' "$pf" | codeof)
    [ -n "$block" ] || return 1
    echo "$block" | grep -qE 'addView|sectionHeader|label\(|caption\(|pickButton' && return 1
    for old in autheliaEmailEditor 'secretField(' '"Mail 2FA confirmation code"' vault_connect_header '"Imports"' showGithubDeviceDialog 'buildSignIn(' 'buildRegistry(' registry_peer_pick; do
        grep -qF -- "$old" "$pf" && return 1
    done
    return 0
}
echo "== T4: the Connect tab IS the journey, and the old surface is gone =="
t4 "$PF" && ok "T4: Connect builds renderJourney and nothing else; the old boxes and tiles are gone" || bad "T4: the Connect tab is not (only) the journey, or an old control survives"
STEPS=$(grep -oE 'enum class Step \{ [A-Z_, ]+ \}' "$PJ" | sed 's/.*{ //; s/ }//; s/,//g')
[ "$(echo $STEPS | wc -w)" = 4 ] && ok "T4: four steps: $STEPS" || bad "T4: ProfileJourney.Step is not four steps ($STEPS)"
echo "$STEPS" | grep -q '^SIGN_IN WHO DEVICE GET$' && ok "T4: in order sign in → who → device → get" || bad "T4: step order is $STEPS"
grep -q 'ProfileJourney.tag(step)' "$PV" && grep -q 'fun tag(step: Step): String = "step:"' "$PJ" && ok "T4: every card is tagged step:<name>" || bad "T4: cards are not tagged by step"
grep -q 'FleetCockpitView.card(' "$PV" && grep -q 'FleetCockpitView.hero(' "$PV" && grep -q 'FleetCockpitView.pill(' "$PF" \
    && ok "T4: the chrome is the cockpit's (hero, card, pill)" || bad "T4: the journey does not use the cockpit chrome"
codeof "$PV" "$PJ" | grep -qE '0x[0-9A-Fa-f]{6,8}' && bad "T4: a colour literal in the journey view" || ok "T4: no colour literal — the palette and the shared light own every ink"
for step in $(echo "$STEPS" | tr 'A-Z' 'a-z'); do
    jq -e --arg s "$step" '.ui.vault_connect.cockpit.journey_icons[$s] | select(. != null and . != "")' "$BJ" >/dev/null \
        && ok "T4: badge for step '$step' is declared in build.json" || bad "T4: cockpit.journey_icons has no '$step'"
done
grep -q 'journeyIcons\[step.name.lowercase()\]' "$PF" && ok "T4: the fragment reads the badges off the declaration" || bad "T4: step badges are not read from cockpit.journey_icons"
grep -q 'selectedTab = if (VaultConnect.Imported.bundle == null && !ProfileJourney.allDone' "$PF" \
    && ok "T4: the page opens on the journey until it has been walked" || bad "T4: the page does not land on the journey"

echo "== T5: the token and the session never reach a store =="
codeof "$SI" | grep -qE 'SharedPreferences|\.edit\(\)|putString|ConfigsPrefs|writeText' && bad "T5: SignIn.kt writes a store" || ok "T5: SignIn.kt touches no store"
grep -q 'data class Session(val provider: String, val identity: String)' "$SI" && ok "T5: the session holds provider + identity, no credential" \
                                                                              || bad "T5: the session carries more than provider + identity"
codeof "$PF" | grep -E 'accessToken' | grep -qE 'Prefs|edit\(|putString' && bad "T5: the device-grant token reaches a store" || ok "T5: the token is used once and dropped"
grep -q 'secret = token' "$SI" && ok "T5: the userinfo call redacts the token from echoed bodies" || bad "T5: userinfo does not pass the token as the redacted secret"
STORES=$(codeof "$PF" | grep -c 'setAutheliaCredential(')
[ "$STORES" = 1 ] && grep -q 'if (storeBearer.isNotBlank())' "$PF" && ok "T5: the bearer is stored once, only after it proved itself" \
                  || bad "T5: setAutheliaCredential is called $STORES times / not gated on a proven bearer"

echo "== T6: picks are ids; a fetch remembers, step 4 alone applies, for the chosen peer =="
grep -q 'fun selectPeer(ctx: Context, id: String)' "$UR" && grep -q 'fun selectIdentity(ctx: Context, email: String)' "$UR" \
    && ok "T6: only ids are stored for the picks" || bad "T6: the pick API changed"
grep -q 'fun current(ctx: Context): Registry?' "$UR" && grep -q 'fun remember(ctx: Context, root: JSONObject)' "$UR" \
    && ok "T6: the registry is cached so steps 2–3 survive a restart" || bad "T6: no registry cache"
grep -q 'VaultCockpit.selectDevice(ctx, p.vaultDevice)' "$PF" && ok "T6: the peer pick selects the cockpit device" || bad "T6: the peer pick does not select the cockpit device"
RF=$(codeof "$PF" | awk '/private fun runFetch\(/{f=1} f{print} f&&/^    }$/{exit}')
echo "$RF" | grep -q 'UserRegistry.remember(appCtx, outcome.body)' && ok "T6: every fetch route remembers and caches the registry" || bad "T6: runFetch does not remember the artifact"
echo "$RF" | grep -q 'ConfigAutoImport.apply' && bad "T6: a fetch still applies — step 4 is the only apply" || ok "T6: a fetch never applies"
[ "$(codeof "$PF" | grep -c 'ConfigAutoImport.apply(')" = 1 ] && ok "T6: exactly one Apply on the page" || bad "T6: ConfigAutoImport.apply is called more than once on the page"
grep -q 'UserRegistry.selectedPeer(context)' "$CA" && grep -q 'UserRegistry.peerProfiles(root, peerId)' "$CA" \
    && ok "T6: the apply step writes the chosen peer's profiles" || bad "T6: ConfigAutoImport ignores the chosen peer"
grep -q 'UserRegistry.markApplied(ctx, stamp())' "$PF" && ok "T6: a successful apply is recorded for step 4's light" || bad "T6: apply does not record itself"

echo "== T7: the ONE declaration, when checked out beside this repo =="
USERS=""
for c in "$APP/../../cloud-infra" "$APP/../../../cloud-infra"; do
    [ -f "$c/1_cloud-configs/src/inputs/superapp-users.json" ] && USERS="$c/1_cloud-configs/src/inputs/superapp-users.json" && INFRA="$c" && break
done
if [ -n "$USERS" ]; then
    for id in $(jq -r '.users[].auth_providers[]' "$USERS" | sort -u); do
        echo "$IDS" | grep -qx "$id" && ok "T7: policy provider '$id' is declared here" || bad "T7: superapp-users.json offers '$id', which build.json does not declare"
    done
    for id in $IDS; do
        jq -e --arg id "$id" '[.users[].auth_providers[]] | index($id)' "$USERS" >/dev/null \
            && ok "T7: declared provider '$id' is offered by some user's policy" || bad "T7: '$id' is declared here but no user's auth_providers offers it — the pill would vanish once the artifact arrives"
    done
    for slug in $(jq -r '.users | keys[]' "$USERS"); do
        pi=$(jq -r --arg s "$slug" '[.users[$s].identities[] | select(.primary == true)] | length' "$USERS")
        pp=$(jq -r --arg s "$slug" '[.users[$s].peers[] | select(.primary == true)] | length' "$USERS")
        [ "$pi" = 1 ] && ok "T7: $slug has one primary identity" || bad "T7: $slug has $pi primary identities"
        [ "$pp" = 1 ] && ok "T7: $slug has one primary peer" || bad "T7: $slug has $pp primary peers"
        ni=$(jq -r --arg s "$slug" '.users[$s].identities | length' "$USERS")
        np=$(jq -r --arg s "$slug" '.users[$s].peers | length' "$USERS")
        [ "$ni" -ge 2 ] && [ "$np" -ge 3 ] && ok "T7: $slug declares $ni identities and $np peers" || bad "T7: $slug declares $ni identities / $np peers (expected ≥2 / ≥3)"
        for peer in $(jq -r --arg s "$slug" '.users[$s].peers | keys[]' "$USERS"); do
            c=$(jq -r --arg s "$slug" --arg p "$peer" '.users[$s].peers[$p].wg_client' "$USERS")
            jq -e --arg c "$c" '.native.wireguard.clients[$c] // .native.wireguard_public.clients[$c]' "$INFRA/1_cloud-configs/dist/_cloud-data-consolidated.json" >/dev/null 2>&1 \
                && ok "T7: peer '$peer' → wg_client '$c' is on a mesh" || bad "T7: peer '$peer' names wg_client '$c', on no mesh"
        done
    done
else
    echo "  UNVERIFIABLE: cloud-infra is not checked out beside this repository — the cross-repo checks did not run (T1–T6 above did)"
fi

echo "== T8: mutation — a Connect tab that is not the journey turns T4 red =="
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
grep -v 'renderJourney(ctx, connect)' "$PF" > "$TMP/no-journey.kt"
t4 "$TMP/no-journey.kt" && bad "T8: T4 passed a fragment whose Connect tab builds no journey" || ok "T8: no journey → T4 RED"
sed 's/renderJourney(ctx, connect)/connect.addView(autheliaEmailEditor(ctx)); renderJourney(ctx, connect)/' "$PF" > "$TMP/old-box.kt"
t4 "$TMP/old-box.kt" && bad "T8: T4 passed a fragment that put the account-email box back" || ok "T8: an old box back on the tab → T4 RED"

echo "== T9: mutation — the four-way check turns red =="
jq '.ui.vault_connect.sign_in.providers |= map(if .id == "authelia_web" then .kind = "authelia_bearer" else . end)' "$BJ" > "$TMP/folded.json"
fourways "$TMP/folded.json" "$PF" >/dev/null && bad "T9: passed a build.json whose web-auth way is the bearer's kind" || ok "T9: web-auth folded into the bearer kind → RED"
jq '.ui.vault_connect.sign_in.providers |= map(select(.id != "authelia_web"))' "$BJ" > "$TMP/three.json"
fourways "$TMP/three.json" "$PF" >/dev/null && bad "T9: passed three providers" || ok "T9: one SSO way dropped → RED"
jq '.ui.vault_connect.sign_in.providers |= map(if .id == "google" then .client_id = "invented.apps.googleusercontent.com" else . end)' "$BJ" > "$TMP/invented.json"
fourways "$TMP/invented.json" "$PF" >/dev/null && bad "T9: passed an invented Google client id" || ok "T9: Google given a client id → RED"
sed 's/{ showAutheliaWebAuthDialog() })$/{ showAutheliaBearerDialog() })/' "$PF" > "$TMP/web-to-bearer.kt"
cmp -s "$PF" "$TMP/web-to-bearer.kt" && bad "T9: the mutation did not change the fragment (tester is stale)" \
    || { fourways "$BJ" "$TMP/web-to-bearer.kt" >/dev/null && bad "T9: passed a web pill that opens the bearer dialog" || ok "T9: web pill → bearer dialog → RED"; }
fourways "$BJ" "$PF" >/dev/null && ok "T9: the unmutated tree is still green" || bad "T9: the unmutated tree is red"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
