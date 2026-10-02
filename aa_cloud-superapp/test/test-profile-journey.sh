#!/usr/bin/env bash
# Tester (#573): Configs ▸ Profile ▸ Connect is THE JOURNEY — sign in → who →
# which device → get everything — one designed flow in the #570 cockpit's chrome.
#
# The behaviour (the state machine and its layout tree, provider parsing, the
# device-grant steps, the registry walk, the strip geometry) runs on the JVM:
# ProfileJourneyTest, SignInTest, UserRegistryTest, AppTabsStyleTest. This file
# pins what a JVM test cannot see:
#   T1  the provider list is DATA, and ONE: ab_cloud-libs-shared/build.json::auth.sign_in
#       (#587 — the fleet's shared libs:auth, the SAME module cloud-drive links) declares
#       FOUR ways in (#578: Authelia bearer, Authelia web-auth, GitHub, Google),
#       unique ids and labels, exactly ONE primary, kinds the code dispatches
#       on, the two SSO ways of DISTINCT kinds, both device-flow providers carrying
#       a PUBLIC client_id and NO committed client_secret (#611: Google's secret is
#       baked from the private vault), baked to ONE BuildConfig field of the LIB; this app's
#       build.json and gradle carry no provider, endpoint or GitHub-only field
#   T2  nothing in Kotlin names a provider, an endpoint, a client id, a user, an
#       address or a device — the seed lives in cloud-infra's superapp-users.json
#   T3  every journey_* / sign_in_* string the Kotlin uses exists in EVERY locale,
#       and no declared one is dead
#   T4  the CONNECT tab IS the journey (#695: Connect | Infos | Cloud
#       Constellation Setup; #766 the vault-export block below it is gone — the
#       vault fetch is the Authelia line's own leg): the page builds renderJourney
#       and nothing else of its own there; the four steps of ProfileJourney, in order;
#       every card tagged step:*; the step badges are data (cockpit.journey_icons
#       names every step); the chrome is the cockpit's (no colour literal); and
#       the OLD surface is GONE — the account-email box, the bearer box, the
#       permanent mail-code box, the Vault configs header, the Imports row, the
#       GitHub-only dialog, the first-pass spinners
#   T5  the device-grant token and the session never reach a store; a bearer is
#       stored only through ConfigsPrefs.setAutheliaCredential after it proved itself
#   T6  the picks persist ids only; the peer pick selects the cockpit device; a
#       fetch REMEMBERS (and caches the registry) but never applies; step 4's
#       Apply is the only apply, and it writes the chosen peer's profiles
#   T7  (cross-repo, when cloud-infra sits beside) every auth_providers id is a
#       declared provider here; one primary identity, one primary peer; every
#       peer on a mesh
#   T8  mutation: a scratch ProfileFragment whose Connect tab no longer builds the
#       journey, puts an old box back on it, or puts the old vault block back,
#       turns T4 RED
#   T9  mutation (#578): the four-way check turns RED when the web-auth way is
#       folded back into the bearer's kind, when the fragment sends the web pill
#       to the bearer dialog, and when a Google client_secret is committed to build.json
set -uo pipefail
APP="${SA_APP:-$(cd "$(dirname "$0")/.." && pwd)}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
GR="$APP/app/build.gradle"
PKG="$APP/app/src/main/java/com/diegonmarcos/superapp/profile"
PF="$PKG/ProfileFragment.kt"
PV="$PKG/ProfileJourneyView.kt"
CA="$PKG/ConfigAutoImport.kt"
RES="$APP/app/src/main/res"
# #587 the journey's engine and the sign-in surface are the fleet's libs:auth; the
# providers are the ONE shared declaration. Both live beside this app.
SHARED="$APP/../ab_cloud-libs-shared/build.json"
LIB="$APP/../ab_cloud-libs-shared/libs/auth"
LGR="$LIB/build.gradle"
LSRC="$LIB/src/main/java/com/diegonmarcos/cloudlib/auth"
PJ="$LSRC/ProfileJourney.kt"
SI="$LSRC/SignIn.kt"
UR="$LSRC/UserRegistry.kt"
UI="$LSRC/SignInUi.kt"
AD="$LSRC/AuthDeclaration.kt"
CFA="$LSRC/ConfigArtifact.kt"
DG="$LSRC/DeviceGrant.kt"
LRES="$LIB/src/main/res"
for f in "$BJ" "$GR" "$PF" "$PJ" "$PV" "$SI" "$UR" "$CA" "$SHARED" "$LGR" "$UI" "$AD" "$CFA" "$DG"; do
    [ -f "$f" ] || { echo "FAIL: $f missing — this tester is unrun, not passing"; exit 1; }
done

# Code only — whole-line comments dropped, so prose about what must not
# happen does not read as it happening. Grep captured text through here-strings,
# never `echo "$big" | grep -q` (#585b): under pipefail grep -q quits on the first
# match and the echo dies of SIGPIPE, turning a green tree red on the runner.
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }

echo "== T1: the provider list is data, in the ONE shared declaration =="
SIGNIN='.auth.sign_in'
N=$(jq "$SIGNIN.providers | length" "$SHARED" 2>/dev/null || echo 0)
[ "${N:-0}" -eq 3 ] && ok "T1: $N providers declared in ab_cloud-libs-shared/build.json::auth" || bad "T1: not the three declared providers ($N) — #641 deleted the GitHub device grant and nothing may re-add a provider unannounced"
IDS=$(jq -r "$SIGNIN.providers[].id" "$SHARED")
[ "$(echo "$IDS" | sort | uniq -d | wc -l)" = 0 ] && ok "T1: provider ids are unique" || bad "T1: duplicate provider id"
P=$(jq "[$SIGNIN.providers[] | select(.primary == true)] | length" "$SHARED")
[ "$P" = 1 ] && ok "T1: exactly one primary provider" || bad "T1: $P primary providers"
for k in $(jq -r "$SIGNIN.providers[].kind" "$SHARED" | sort -u); do
    grep -q "\"$k\" -> Kind\." "$SI" && ok "T1: kind '$k' is dispatched by SignIn.kt" || bad "T1: kind '$k' is declared but SignIn.kt does not dispatch on it"
done
for id in $IDS; do
    g=$(jq -r --arg id "$id" "$SIGNIN.providers[] | select(.id == \$id) | .grants | length" "$SHARED")
    [ "${g:-0}" -ge 1 ] && ok "T1: '$id' grants something" || bad "T1: '$id' grants nothing"
done
grep -q 'buildConfigField "String", "AUTH_B64"' "$LGR" && grep -q 'BuildConfig.AUTH_B64' "$AD" && grep -q 'AuthDeclaration.current.signIn' "$SI" \
    && ok "T1: one BuildConfig field (the lib's), decoded once, read by SignIn.kt" || bad "T1: the auth blob is not baked by the lib or not read"
grep -q 'file("${projectDir}/../../build.json")' "$LGR" && ok "T1: the lib bakes the SHARED file, never a consuming app's" || bad "T1: libs:auth/build.gradle does not read ../../build.json"
jq -e '.ui.vault_connect.sign_in' "$BJ" >/dev/null 2>&1 && bad "T1: this app's build.json STILL carries ui.vault_connect.sign_in — two declarations" || ok "T1: this app declares no provider of its own"
jq -e '.ui.config_source.base_url' "$BJ" >/dev/null 2>&1 && bad "T1: this app's build.json still carries the config endpoint — two declarations" || ok "T1: the config endpoint lives only in the shared declaration"
grep -qE 'UI_VAULT_CONNECT_SIGN_IN_B64|UI_CONFIG_SOURCE_BASE_URL|UI_CONFIG_GIT_REPO|UI_GH_OAUTH|github_oauth' "$GR" && bad "T1: gradle still bakes a sign-in / endpoint / GitHub-only field" || ok "T1: no sign-in, endpoint or GitHub-only field in this app's gradle"
jq -e '.ui.config_source.github_oauth' "$BJ" >/dev/null 2>&1 && bad "T1: build.json still carries ui.config_source.github_oauth" || ok "T1: the OAuth client lives only in sign_in"
grep -q "implementation project(':libs:auth')" "$GR" && jq -e '.modules["libs:auth"].dir' "$BJ" >/dev/null && ok "T1: libs:auth linked by reference (build.json::modules dir + gradle)" || bad "T1: libs:auth is not linked by reference"

# ── the three ways in (#578, narrowed by #641), as a function so T9 can run it on mutated copies ──
# $1 = the shared build.json, $2 = the lib's SignInUi.kt; prints the first broken rule, returns non-zero on one.
# #641 THE GITHUB DEVICE GRANT IS DELETED: its client was a GitHub APP, and GitHub Apps ship with
# Device Flow OFF, so the grant could never start. Google's is the ONE device grant left — and the
# count below is an equality, so this cannot pass by finding no device grant at all.
waysin() {
    local bj="$1" ui="$2" k
    [ "$(jq '.auth.sign_in.providers | length' "$bj")" = 3 ] || { echo "not three providers"; return 1; }
    [ "$(jq '[.auth.sign_in.providers[].label] | unique | length' "$bj")" = 3 ] || { echo "labels are not unique"; return 1; }
    [ "$(jq '[.auth.sign_in.providers[] | select(.id == "github")] | length' "$bj")" = 0 ] \
        || { echo "the github provider is declared again (a GitHub App: Device Flow is OFF, the grant cannot start)"; return 1; }
    grep -q 'Ov23li' "$bj" && { echo "a GitHub App client id is back in the shared declaration"; return 1; }
    for k in authelia_bearer authelia_web; do
        [ "$(jq --arg k "$k" '[.auth.sign_in.providers[] | select(.kind == $k)] | length' "$bj")" = 1 ] \
            || { echo "kind $k is not declared exactly once"; return 1; }
    done
    [ "$(jq '[.auth.sign_in.providers[] | select(.kind == "device_flow")] | length' "$bj")" = 1 ] || { echo "not exactly one device-flow provider"; return 1; }
    [ "$(jq -r '[.auth.sign_in.providers[] | select(.kind == "device_flow")][0].id' "$bj")" = "google" ] \
        || { echo "the one device grant left is not Google's"; return 1; }
    # #611: the device-flow provider carries a PUBLIC client_id and NO client_secret in this
    # public declaration — Google's is baked from the private vault at build time
    # (libs:auth build.gradle), never committed here.
    [ "$(jq -r '[.auth.sign_in.providers[] | select(.kind == "device_flow" and ((.client_id // "") == ""))] | length' "$bj")" = 0 ] \
        || { echo "every device-flow provider must carry a client_id"; return 1; }
    [ "$(jq -r '[.auth.sign_in.providers[] | select(.kind == "device_flow" and ((.client_secret // "") != ""))] | length' "$bj")" = 0 ] \
        || { echo "no device-flow provider may carry a committed client_secret (this is a public repo)"; return 1; }
    # The surface gives each SSO way its own branch and its own dialog — no shared path.
    local code; code=$(codeof "$ui")
    grep -qE 'SignIn\.Kind\.AUTHELIA_WEB ->.*open = Open\.Web\(p\)' <<<"$code" \
        || { echo "the web-auth pill does not open the web-auth dialog"; return 1; }
    grep -qE 'SignIn\.Kind\.AUTHELIA_BEARER ->.*open = Open\.Bearer\(p\)' <<<"$code" \
        || { echo "the bearer pill does not open the bearer dialog"; return 1; }
    grep -q 'is Open.Web -> WebAuthDialog(' <<<"$code" && grep -q 'is Open.Bearer -> BearerDialog(' <<<"$code" \
        || { echo "the two SSO dialogs are not distinct"; return 1; }
    grep -q 'host.onWebSession(cookie)' <<<"$code" \
        || { echo "the web-auth session is not handed to the host"; return 1; }
    return 0
}
echo "== T1b: THREE ways in — Authelia bearer, Authelia web-auth, Google (#641: no GitHub) =="
msg=$(waysin "$SHARED" "$UI") && ok "T1b: three ways, two distinct SSO kinds, ONE device grant (Google's) with a public client_id and no committed secret, each SSO way has its own dialog" || bad "T1b: $msg"
grep -q 'via = SignIn.byKind(SignIn.Kind.AUTHELIA_BEARER)' "$PF" && ok "T1b: the stored bearer's one tap is recorded against the bearer way" || bad "T1b: the stored bearer's fetch names no provider"
grep -q 'override fun onWebSession(cookie: String) { vaultSession = cookie }' "$PF" && ok "T1b: the browser session reaches the vault route (one login, both fetches)" || bad "T1b: the fragment drops the web-auth session"
jq -e '.auth.sign_in.providers[] | select(.primary == true) | select(.kind == "authelia_bearer")' "$SHARED" >/dev/null \
    && ok "T1b: the primary is the bearer way" || bad "T1b: the primary provider is not the bearer way"
grep -q '"authelia_bearer" -> Kind.AUTHELIA_BEARER' "$SI" && grep -q '"authelia_web" -> Kind.AUTHELIA_WEB' "$SI" \
    && ok "T1b: SignIn.kt dispatches both SSO kinds" || bad "T1b: SignIn.kt does not map both SSO kinds"

echo "== T2: no provider, endpoint, client id, user, address or device literal in Kotlin (app AND lib) =="
KT=("$SI" "$UI" "$AD" "$CFA" "$DG" "$PF" "$PJ" "$PV" "$UR" "$CA")
for id in $IDS; do
    grep -qE "\"$id\"" <<<"$(grep -v -- '-> Kind\.' <<<"$(codeof "${KT[@]}")")" && bad "T2: provider id '$id' is a Kotlin literal" || ok "T2: '$id' is not a Kotlin literal"
done
for u in $(jq -r '.auth.sign_in.providers[] | .device_code_url // empty, .token_url // empty, .userinfo_url // empty, (.client_id | select(. != "" and . != null))' "$SHARED"); do
    grep -qF "$u" <<<"$(codeof "${KT[@]}")" && bad "T2: '$u' is a Kotlin literal" || ok "T2: '$u' lives only in the shared build.json"
done
for u in $(jq -r '.auth.config_source.base_url, .auth.config_source.user, .auth.vault_connect.base_url, .auth.config_source.git.repo' "$SHARED"); do
    grep -qF "\"$u\"" <<<"$(codeof "${KT[@]}")" && bad "T2: endpoint/user/repo '$u' is a Kotlin literal" || ok "T2: '$u' lives only in the shared build.json"
done
# Device names as WORDS: Compose's colour roles (onSurfaceVariant) are not the Surface laptop.
# (The old `codeof | grep -q` form passed here only because awk died of SIGPIPE — #585b.)
if grep -qiE '@diegonmarcos\.com|diego coelho|\bsamsung\b|\bsurface\b|\bgalaxy\b|\btermux\b|10\.0\.0\.[0-9]+|fd0c:1d0' <<<"$(codeof "${KT[@]}")"; then
    bad "T2: a user, address or device name is a Kotlin literal in the profile package or the lib"
else
    ok "T2: no user, address or device literal in the profile package or the lib"
fi

echo "== T3: every journey string exists in every locale, none is dead (app), and the lib's likewise =="
USED=$(grep -ohE 'R\.string\.(journey|sign_in)_[a-z_]+' "$PF" "$PV" | sed 's/R\.string\.//' | sort -u)
[ "$(echo "$USED" | grep -c .)" -ge 40 ] && ok "T3: $(echo "$USED" | grep -c .) journey strings used" || bad "T3: too few journey strings used — labels are literals"
grep -q 'name="sign_in_' "$RES/values/strings.xml" && bad "T3: this app still declares sign_in_* strings — the surface's words are the lib's" || ok "T3: the sign-in words live in the lib alone"
# EVERY Kotlin file of the lib, not SignInUi.kt alone: the surface grew a second
# file (OAuthWebDialog.kt) and its captions read as dead strings, failing every
# SuperApp ship on a string that was in use.
LUSED=$(grep -rohE --include='*.kt' 'R\.string\.auth_[a-z_]+' "$LSRC" | sed 's/R\.string\.//' | sort -u)
[ "$(echo "$LUSED" | grep -c .)" -ge 20 ] && ok "T3: the lib names $(echo "$LUSED" | grep -c .) auth_* strings" || bad "T3: the lib's surface types its captions"
for loc in "$LRES"/values*/strings.xml; do
    for s in $LUSED; do grep -q "name=\"$s\"" "$loc" || bad "T3: lib string $s missing from ${loc#$LIB/}"; done
done
# A string is DEAD only when NO lib source names it (the scope e33c80ef4 widened
# above). #695 factors the check so it can be mutation-proved: a planted string no
# lib file names must still read as dead.
lib_dead() {   # $1 = the lib's values/strings.xml, $2 = its Kotlin source dir; prints the dead auth_* names
    local all
    # [a-z0-9_]: a resource name may carry digits, and a narrower class would not
    # even SEE such a string, dead or alive.
    all=$(grep -ohE 'R\.string\.auth_[a-z0-9_]+' "$2"/*.kt | sed 's/R\.string\.//' | sort -u)
    grep -oE 'name="auth_[a-z0-9_]+"' "$1" | sed 's/name="//; s/"//' | sort -u | comm -23 - <(echo "$all")
}
LDEAD=$(lib_dead "$LRES/values/strings.xml" "$LSRC")
[ -z "$LDEAD" ] && ok "T3: no dead lib string; present in $(ls "$LRES"/values*/strings.xml | wc -l) locale files" || bad "T3: lib declares but never uses: $(echo "$LDEAD" | tr '\n' ' ')"
# Mutation: a string NO lib source names is still dead — the widened scope did not blind the check.
T3M="$(mktemp -d)"
sed 's#</resources>#    <string name="auth_t3_planted_unused">x</string>\n</resources>#' "$LRES/values/strings.xml" > "$T3M/strings.xml"
grep -q 'auth_t3_planted_unused' "$T3M/strings.xml" || bad "T3-mutation: the planted string did not land (tester stale)"
grep -qx 'auth_t3_planted_unused' <<<"$(lib_dead "$T3M/strings.xml" "$LSRC")" && ok "T3-mutation: a string no lib source names → reported dead" \
    || bad "T3-mutation: a planted unused lib string was NOT reported dead"
rm -rf "$T3M"
for loc in "$RES"/values*/strings.xml; do
    for s in $USED; do
        grep -q "name=\"$s\"" "$loc" || bad "T3: $s missing from ${loc#$APP/}"
    done
done
DEAD=$(grep -oE 'name="(journey|sign_in)_[a-z_]+"' "$RES/values/strings.xml" | sed 's/name="//; s/"//' | sort -u | comm -23 - <(echo "$USED"))
[ -z "$DEAD" ] && ok "T3: no dead journey string" || bad "T3: declared but unused: $(echo "$DEAD" | tr '\n' ' ')"
[ "$FAIL" = 0 ] && ok "T3: all present in $(ls "$RES"/values*/strings.xml | wc -l) locale files"

# ── T4 as a function, so T8 can run it on a mutated copy ──
t4() {   # $1 = ProfileFragment path; prints nothing, returns 0 when Connect opens with the journey
    local pf="$1" j w
    grep -q 'renderJourney(ctx, connect)' "$pf" || return 1
    # #766 THE JOURNEY IS THE PAGE: the old vault-export block that sat below it
    # (its own login, mailed code and fetch) is the Authelia line's own leg now,
    # and every line fetches the vault configs — so it must not come back.
    grep -q 'renderVault(ctx, connect)' "$pf" && return 1
    # Between the CONNECT marker and the Infos render, the page builds its
    # renderers and nothing else of its own — no box, header, caption or pill.
    local block
    # The markers are comment lines, so slice the raw file first, then drop comments.
    block=$(awk '/── CONNECT: sign in, fetch/{f=1} f{print} f&&/renderInfos\(ctx, col\)/{exit}' "$pf" | codeof)
    [ -n "$block" ] || return 1
    grep -qE 'addView|sectionHeader|label\(|caption\(|pickButton' <<<"$block" && return 1
    for old in autheliaEmailEditor 'secretField(' '"Mail 2FA confirmation code"' vault_connect_header '"Imports"' showGithubDeviceDialog 'buildSignIn(' 'buildRegistry(' registry_peer_pick; do
        grep -qF -- "$old" "$pf" && return 1
    done
    return 0
}
echo "== T4: the Connect tab opens with the journey, then the fetch; the old surface is gone =="
t4 "$PF" && ok "T4: Connect builds the journey and nothing else of its own; the old vault block, boxes and tiles are gone" || bad "T4: the Connect tab is not the journey alone, or an old control survives"
STEPS=$(grep -oE 'enum class Step \{ [A-Z_, ]+ \}' "$PJ" | sed 's/.*{ //; s/ }//; s/,//g')
[ "$(echo $STEPS | wc -w)" = 4 ] && ok "T4: four steps: $STEPS" || bad "T4: ProfileJourney.Step is not four steps ($STEPS)"
grep -q '^SIGN_IN WHO DEVICE GET$' <<<"$STEPS" && ok "T4: in order sign in → who → device → get" || bad "T4: step order is $STEPS"
grep -q 'ProfileJourney.tag(step)' "$PV" && grep -q 'fun tag(step: Step): String = "step:"' "$PJ" && ok "T4: every card is tagged step:<name>" || bad "T4: cards are not tagged by step"
grep -q 'FleetCockpitView.card(' "$PV" && grep -q 'FleetCockpitView.hero(' "$PV" && grep -q 'FleetCockpitView.pill(' "$PF" \
    && ok "T4: the chrome is the cockpit's (hero, card, pill)" || bad "T4: the journey does not use the cockpit chrome"
grep -qE '0x[0-9A-Fa-f]{6,8}' <<<"$(codeof "$PV" "$PJ")" && bad "T4: a colour literal in the journey view" || ok "T4: no colour literal — the palette and the shared light own every ink"
for step in $(echo "$STEPS" | tr 'A-Z' 'a-z'); do
    jq -e --arg s "$step" '.ui.vault_connect.cockpit.journey_icons[$s] | select(. != null and . != "")' "$BJ" >/dev/null \
        && ok "T4: badge for step '$step' is declared in build.json" || bad "T4: cockpit.journey_icons has no '$step'"
done
grep -q 'journeyIcons\[step.name.lowercase()\]' "$PF" && ok "T4: the fragment reads the badges off the declaration" || bad "T4: step badges are not read from cockpit.journey_icons"
# #695 step 1 is the declared LINES: each Authelia way hosts the SHARED surface
# narrowed to the providers of its kind that the artifact's policy offers.
grep -q 'SignInWays(host = signInHost, policy = providerIds' "$PF" && grep -q 'SignIn.offered(policy).filter { it.kind.name.lowercase() == way.kind }' "$PF" \
    && ok "T4: step 1 hosts the shared surface per way, narrowed by kind and by the artifact's policy" || bad "T4: step 1 does not host SignInWays per declared way"
grep -q 'FleetCockpitView.pill(c, way.label, onClick)' "$PF" && ok "T4: the shared ways are drawn with the cockpit's pill, labelled by the declaration" || bad "T4: the shared ways are not drawn with FleetCockpitView.pill"
grep -qE 'private fun (showAutheliaBearerDialog|showAutheliaWebAuthDialog|showDeviceFlowDialog|startDeviceFlow)\(' "$PF" && bad "T4: the fragment still carries a sign-in dialog of its own — a copy of the lib's" || ok "T4: no private sign-in dialog left in the fragment"
grep -q 'selectedTab = if (VaultConnect.Imported.bundle == null && !ProfileJourney.allDone' "$PF" \
    && ok "T4: the page opens on the journey (Connect) until it has been walked" || bad "T4: the page does not land on the journey"
grep -q 'selectedTab = if (VaultConnect.Imported.bundle == null && !ProfileJourney.allDone(journeyState(ctx))) connectTab else infosTab' "$PF" \
    && ok "T4: and lands on the Infos read-out once it has been" || bad "T4: the landing does not name connectTab/infosTab (#695)"

echo "== T5: the token and the session never reach a store =="
grep -qE 'SharedPreferences|\.edit\(\)|putString|ConfigsPrefs|writeText' <<<"$(codeof "$SI" "$UI" "$DG" "$CFA" "$AD")" && bad "T5: the lib writes a store" || ok "T5: the lib (SignIn, the surface, the grant, the fetches) touches no store"
grep -q 'data class Session(val provider: String, val identity: String)' "$SI" && ok "T5: the session holds provider + identity, no credential" \
                                                                              || bad "T5: the session carries more than provider + identity"
grep -qE 'Prefs|edit\(|putString' <<<"$(grep -E 'accessToken' <<<"$(codeof "$PF" "$UI" "$DG")")" && bad "T5: the device-grant token reaches a store" || ok "T5: the token is used once and dropped"
grep -q 'accessToken' <<<"$(codeof "$PF")" && bad "T5: the device-grant token reaches the fragment at all" || ok "T5: the fragment never sees a device-grant token"
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
RF=$(codeof "$PF" | awk '/private fun landed\(/{f=1} f{print} f&&/^    }$/{exit}')
grep -q 'UserRegistry.remember(appCtx, artifact)' <<<"$RF" && ok "T6: every way in lands in ONE place that remembers and caches the registry" || bad "T6: landed() does not remember the artifact"
grep -q 'ConfigAutoImport.apply' <<<"$RF" && bad "T6: a fetch still applies — step 4 is the only apply" || ok "T6: a fetch never applies"
[ "$(codeof "$PF" | grep -c 'landed(')" -ge 3 ] && ok "T6: the lib's host, the stored bearer and the SSH clone all land through landed()" || bad "T6: not every way in lands through landed()"
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
        grep -qx "$id" <<<"$IDS" && ok "T7: policy provider '$id' is declared here" || bad "T7: superapp-users.json offers '$id', which build.json does not declare"
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

echo "== T8: mutation — a Connect tab that is not the journey-first page turns T4 red =="
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
grep -v 'renderJourney(ctx, connect)' "$PF" > "$TMP/no-journey.kt"
t4 "$TMP/no-journey.kt" && bad "T8: T4 passed a fragment whose Connect tab builds no journey" || ok "T8: no journey → T4 RED"
sed 's/renderJourney(ctx, connect)/connect.addView(autheliaEmailEditor(ctx)); renderJourney(ctx, connect)/' "$PF" > "$TMP/old-box.kt"
cmp -s "$PF" "$TMP/old-box.kt" && bad "T8: the old-box mutation did not apply (tester stale)" \
    || { t4 "$TMP/old-box.kt" && bad "T8: T4 passed a fragment that put the account-email box back" || ok "T8: an old box back on the tab → T4 RED"; }
# #766 the old vault-export block back under the journey is not the page.
awk '{print} /^        renderJourney\(ctx, connect\)$/{print "        renderVault(ctx, connect)"}' "$PF" > "$TMP/vault-back.kt"
cmp -s "$PF" "$TMP/vault-back.kt" && bad "T8: the vault-block mutation did not apply (tester stale)" \
    || { t4 "$TMP/vault-back.kt" && bad "T8: T4 passed a Connect page with the old vault-export block back" || ok "T8: the old vault block back on Connect → T4 RED"; }

echo "== T9: mutation — the ways-in check turns red =="
jq '.auth.sign_in.providers |= map(if .id == "authelia_web" then .kind = "authelia_bearer" else . end)' "$SHARED" > "$TMP/folded.json"
waysin "$TMP/folded.json" "$UI" >/dev/null && bad "T9: passed a declaration whose web-auth way is the bearer's kind" || ok "T9: web-auth folded into the bearer kind → RED"
jq '.auth.sign_in.providers |= map(select(.id != "authelia_web"))' "$SHARED" > "$TMP/two.json"
waysin "$TMP/two.json" "$UI" >/dev/null && bad "T9: passed two providers" || ok "T9: one SSO way dropped → RED"
jq '.auth.sign_in.providers |= map(if .id == "google" then .client_secret = "leaked-secret" else . end)' "$SHARED" > "$TMP/leaked.json"
waysin "$TMP/leaked.json" "$UI" >/dev/null && bad "T9: passed a Google client_secret committed to the public build.json" || ok "T9: a client_secret committed to the public build.json → RED"
# #641 THE INVERSION, mutation-proved: re-declaring the GitHub App device grant goes RED.
jq '.auth.sign_in.providers += [{"id":"github","label":"GitHub","kind":"device_flow","device_code_url":"https://github.com/login/device/code","token_url":"https://github.com/login/oauth/access_token","client_id":"Ov23liOg9JhezyYUCHmS","client_secret":"","scope":"repo","grants":["identity","repo_artifact"]}]' "$SHARED" > "$TMP/github-back.json"
msg=$(waysin "$TMP/github-back.json" "$UI") && bad "T9: passed a declaration that re-declares the GitHub App device grant" \
    || case "$msg" in *"not three providers"*|*"github provider is declared again"*) ok "T9: the github device grant re-declared → RED ($msg)";; *) bad "T9: red for the wrong reason: $msg";; esac
# And a second shape of the same mutation, one that keeps the count at three: swap Google out for GitHub.
jq '.auth.sign_in.providers |= map(if .id == "google" then {"id":"github","label":"GitHub","kind":"device_flow","device_code_url":"https://github.com/login/device/code","token_url":"https://github.com/login/oauth/access_token","client_id":"Ov23liOg9JhezyYUCHmS","client_secret":"","scope":"repo","grants":["identity","repo_artifact"]} else . end)' "$SHARED" > "$TMP/github-swap.json"
msg=$(waysin "$TMP/github-swap.json" "$UI") && bad "T9: passed a declaration whose one device grant is the GitHub App again" \
    || case "$msg" in *"github provider is declared again"*) ok "T9: GitHub swapped in for Google → RED ($msg)";; *) bad "T9: red for the wrong reason: $msg";; esac
sed 's/{ open = Open.Web(p) }/{ open = Open.Bearer(p) }/' "$UI" > "$TMP/web-to-bearer.kt"
cmp -s "$UI" "$TMP/web-to-bearer.kt" && bad "T9: the mutation did not change the surface (tester is stale)" \
    || { waysin "$SHARED" "$TMP/web-to-bearer.kt" >/dev/null && bad "T9: passed a web pill that opens the bearer dialog" || ok "T9: web pill → bearer dialog → RED"; }
waysin "$SHARED" "$UI" >/dev/null && ok "T9: the unmutated tree is still green" || bad "T9: the unmutated tree is red"

echo "== T10: the fleet wizard (#622) is declarative, and its done-checks are real measurements =="
WIZ="$PKG/Wizard.kt"
wizard_order() { jq -r '(.ui.profile.wizard.steps // [])[].id' "$1" | tr '\n' ' ' | sed 's/ $//'; }
WANT_STEPS="identity vault permissions apps repos wireguard fleet finish"
[ "$(wizard_order "$BJ")" = "$WANT_STEPS" ] \
    && ok "T10: build.json declares the 8 wizard steps in order" \
    || bad "T10: wizard step order is '$(wizard_order "$BJ")'"
# The wizard is DATA: the engine reads the baked blob, the fragment iterates it,
# the blob is baked from build.json — no step id or order is spelled out in Kotlin.
grep -q 'BuildConfig.UI_PROFILE_WIZARD_B64' "$WIZ" && ok "T10: Wizard reads the baked declaration" || bad "T10: Wizard does not read UI_PROFILE_WIZARD_B64"
grep -q 'Wizard.steps()' "$PF" && grep -q 'for (step in steps)' "$PF" && ok "T10: renderWizard iterates the declared steps" || bad "T10: renderWizard does not iterate Wizard.steps()"
grep -qF 'UI_PROFILE_WIZARD_B64' "$APP/app/build.gradle" && ok "T10: the wizard blob is baked" || bad "T10: UI_PROFILE_WIZARD_B64 is not baked"
# Every done-check is a LIVE measurement of actual device state (#452: a wizard
# that remembers success is worse than one that re-checks). Each real probe must
# be present, and no stored 'step done' flag may exist.
real_checks() {   # $1 = Wizard.kt; 0 iff every step's real probe is present
    for probe in 'SignIn.Current.session' 'autheliaEmail' 'appliedAt' \
                 'Environment.isExternalStorageManager' 'getEnabledListenerPackages' \
                 'android.permission.DUMP' 'getPackageInfo' 'interfacePrivateKey' 'K_GITHUB_TOKEN'; do
        grep -qF "$probe" "$1" || return 1
    done
    return 0
}
apps_measured() { grep -qE '"apps" -> .*declaredApps\(\)' "$1"; }
real_checks "$WIZ" && ok "T10-real: every done-check carries its real system/state probe" || bad "T10-real: a done-check is missing its real probe"
apps_measured "$WIZ" && ok "T10-real: the apps step measures installed packages, not a flag" || bad "T10-real: the apps step is not a real measurement"
grep -qE 'getBoolean\("?wizard|putBoolean\("?wizard|wizard_done|stepDone' "$WIZ" \
    && bad "T10-real: the wizard remembers a done flag (#452)" || ok "T10-real: no stored done flag — every check re-measures"

# #626/#695 THE ROUTES MUST LAND. A step pointing at a tab the strip does not
# declare (tab:vault / tab:repos / tab:fleet were all removed) would be a row that
# silently does nothing when tapped — the defect shape every strip change invites.
routes_land() {   # $1 = build.json; 0 iff every tab: route names a DECLARED tab
    python3 - "$1" <<'PYROUTES'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]["profile"]
tabs = {t["id"] for t in (ui.get("tabs") or []) if isinstance(t, dict)}
bad = [s["id"] for s in (ui.get("wizard") or {}).get("steps", [])
       if s.get("route", "").startswith("tab:") and s["route"][4:] not in tabs]
sys.exit(1 if bad else 0)
PYROUTES
}
routes_land "$BJ" && ok "T10: every wizard step's tab: route names a declared tab" || bad "T10: a wizard step routes to a tab that is not in ui.profile.tabs"

echo "== T10-mutation: a reordered step, or a faked (remembered) done-check, turns red =="
SC="$(mktemp -d)"; trap 'rm -rf "$SC"' EXIT
cp "$BJ" "$SC/build.json"
python3 - "$SC/build.json" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p)); s=d["ui"]["profile"]["wizard"]["steps"]; s[2],s[3]=s[3],s[2]; json.dump(d,open(p,"w"))
PY
[ "$(wizard_order "$SC/build.json")" = "$WANT_STEPS" ] && bad "T10-mutation: a reordered step was NOT caught" || ok "T10-mutation: a reordered step is caught"
# Fake the apps done-check as a bare `true` (a remembered/hardcoded success)
# while its real measurement is removed — the #452 trap made concrete.
sed 's/"apps" -> declaredApps().*/"apps" -> true/' "$WIZ" > "$SC/Wizard.kt"
if grep -q '"apps" -> declaredApps' "$SC/Wizard.kt"; then bad "T10-mutation: the apps mutation did not apply (tester stale)"
else apps_measured "$SC/Wizard.kt" && bad "T10-mutation: a faked apps done-check (true) was NOT caught" || ok "T10-mutation: a faked apps done-check is caught"; fi
cp "$BJ" "$SC/build.json"
python3 - "$SC/build.json" <<'PYDEAD'
import json,sys
p=sys.argv[1]; d=json.load(open(p))
d["ui"]["profile"]["wizard"]["steps"][0]["route"]="tab:vault"   # a tab #626 removed and #695 did not bring back
json.dump(d,open(p,"w"))
PYDEAD
routes_land "$SC/build.json" && bad "T10-mutation: a step routing to a removed tab was NOT caught" || ok "T10-mutation: a step routing to a removed tab is caught"
rm -rf "$SC"; trap - EXIT

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
