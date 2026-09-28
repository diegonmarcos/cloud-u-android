#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #629 — the git credential comes from the VAULT, and the browser grant is  ║
# ║ a fallback that says so when the provider refuses it                      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS, in the owner's words: "why GitHub Apps??? it is just a Git
# Auth to get its ssh key!!!" He is right, and the evidence is on both sides.
#
# ON THE PROVIDER SIDE the browser road is shut: the declared client_id carries
# the `Ov23li` prefix, which is a GitHub APP, and GitHub Apps ship with Device
# Flow OFF — the device hit `device_flow_disabled - Device Flow must be
# explicitly enabled for this App`. That is a switch in github.com/settings/apps
# and NO code can reach it, so a fix "in the app" cannot exist.
#
# ON OUR SIDE the credential was already on the phone. cloud-vault
# C_A1-configs/git/sources.json declares git.github_token (and an ssh key pair),
# the #566 emitter really does put it in the artifact, and #608's DriveAuthApply
# already writes it into libs:git-sync's keystore credential store. The browser
# ceremony was duplicating a credential we had — and adding a provider setting as
# a single point of failure on top of it.
#
#   A1  the vault credential is READ, from the engines' own store under the
#       DECLARED credential_id, and the listing runs off it with no tap.
#   A2  a vault import writes it even when NOTHING is cloned yet — the fresh-phone
#       case, where the old plan() reported not-ok and threw the token away.
#   A3  the browser grant is drawn ONLY when there is no vault credential.
#   A4  there is no second credential store: the token goes into, and comes out
#       of, libs:git-sync's GitCredentialStore and nowhere else.
#   R1  the error->remedy mapping is DECLARED in one place
#       (ab_cloud-libs-shared/build.json::auth.grant_remedies) and covers
#       device_flow_disabled, with libs:auth as its single consumer.
#   MUT mutation-proof: a credential resolved from somewhere other than the
#       vault-delivered config, a Start button drawn while a vault credential is
#       present, and a removed remedy mapping each go RED.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
PAGE="$SRC/sync/GitReposScreen.kt"
APPLY="$SRC/configs/DriveAuthApply.kt"
BJ="$APP/build.json"
SHARED_BJ="$ROOT/ab_cloud-libs-shared/build.json"
AUTH_DECL="$ROOT/ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/AuthDeclaration.kt"
SIGNIN_UI="$ROOT/ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/SignInUi.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$PAGE" "$APPLY" "$BJ" "$SHARED_BJ" "$AUTH_DECL" "$SIGNIN_UI"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

# a1 <GitReposScreen.kt> : the vault credential is the one the page starts from
a1() {
    local f="$1" bad=0
    grep -qE 'DriveAuthApply\.vaultGitToken\(ctx\.applicationContext\)' "$f" \
        || { echo "    the page does not read the vault-delivered credential"; bad=1; }
    grep -qE 'login = login\.copy\(identity = page\.owner, token = vault, fromVault = true\)' "$f" \
        || { echo "    a vault credential does not become the page's login"; bad=1; }
    grep -qE 'fetchListing\(vault\)' "$f" \
        || { echo "    the personal listing is not fetched with the vault credential — the user would still have to tap"; bad=1; }
    grep -qE 'login\.fromVault -> stringResource\(R\.string\.git_login_from_vault' "$f" \
        || { echo "    the card does not state that the credential is the vault's"; bad=1; }
    return $bad
}

# a3 <GitReposScreen.kt> : no browser Start button while a vault credential is in hand
a3() {
    local f="$1" bad=0
    if ! awk '/if \(login\.fromVault\) \{/{seen=NR} seen && /} else if \(webauth != null\) \{/{after=NR} END{exit !(seen && after && after > seen)}' "$f"; then
        echo "    the device-grant surface is not gated on the ABSENCE of a vault credential"; bad=1
    fi
    # SignInWays must appear exactly once, inside the else arm.
    local ways; ways=$(grep -cE 'SignInWays\(host = host' "$f")
    [ "$ways" = "1" ] || { echo "    $ways sign-in surfaces on the page — one of them is not gated"; bad=1; }
    grep -qE 'R\.string\.git_login_vault_absent' "$f" || { echo "    the fallback does not say what the supported path is"; bad=1; }
    return $bad
}

# a4 <DriveAuthApply.kt> : one store, the declared id, and a token with no clone still lands
a4() {
    local f="$1" bad=0
    grep -qE 'val credentialIds: Map<String, String> get\(\) = Declarations\.authCredentialIds' "$f" \
        || { echo "    the credential id is not read off the declaration"; bad=1; }
    grep -qE 'credentialIds\[step\.section\]\?\.takeIf \{ it\.isNotBlank\(\) \}\?\.let \{ store\.setSecret\(it, token!!\) \}' "$f" \
        || { echo "    the vault credential is not written under its declared id"; bad=1; }
    grep -qE 'targets\.isEmpty\(\) && credential\.isNotBlank\(\) ->' "$f" \
        || { echo "    a fresh phone (no clone yet) still throws the vault token away"; bad=1; }
    grep -qE 'store\.authFor\(ManagedRepo\(id = id' "$f" \
        || { echo "    the credential is not read back through libs:git-sync's own auth"; bad=1; }
    # No second store: nothing here may open prefs, a file or a keystore of its own.
    if grep -qE 'EncryptedSharedPreferences|getSharedPreferences|MasterKey|KeyStore' "$f"; then
        echo "    this file opens a credential store of its own — libs:git-sync's is the only one"; bad=1
    fi
    return $bad
}

# r1 <shared build.json> : the remedy mapping is declared, in one place, and covers the real error
r1() {
    python3 - "$1" <<'PYTHON'
import json, sys
auth = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]
rows = auth.get("grant_remedies") or []
bad = 0
if not rows:
    print("    auth.grant_remedies is not declared: the error would be shown with no remedy"); bad += 1
hit = [r for r in rows if "device_flow_disabled" in (r.get("match") or "")]
if not hit:
    print("    no declared row matches device_flow_disabled — the error the owner actually hit"); bad += 1
for r in rows:
    if not (r.get("match") or "").strip():
        print("    a remedy row matches nothing"); bad += 1
    if not (r.get("remedy") or "").strip():
        print("    row %s carries no remedy, which is the whole point" % r.get("match")); bad += 1
for r in hit:
    text = (r.get("remedy") or "") + " " + (r.get("url") or "")
    if "settings/apps" not in text:
        print("    the device_flow_disabled remedy does not name where the switch lives"); bad += 1
    if "vault" not in text.lower():
        print("    the remedy does not say that the vault path is the supported one"); bad += 1
print("    declared remedies: %d" % len(rows))
sys.exit(1 if bad else 0)
PYTHON
}

# r2 <AuthDeclaration.kt> <SignInUi.kt> : one reader, one consumer
r2() {
    local decl="$1" ui="$2" bad=0
    grep -qE 'fun remedyFor\(message: String' "$decl" || { echo "    libs:auth has no lookup for a declared remedy"; bad=1; }
    grep -qE 'fun explain\(message: String' "$decl" || { echo "    libs:auth has no one wording rule for an error plus its remedy"; bad=1; }
    grep -qE 'root\.optJSONArray\("grant_remedies"\)' "$decl" || { echo "    the declaration is not parsed"; bad=1; }
    grep -qE 'AuthDeclaration\.explain\(phase\.message\)' "$ui" || { echo "    the device-grant failure is still worded without its remedy"; bad=1; }
    # The remedy text may exist in exactly one place: the declaration.
    if grep -rqE 'Device Flow must be explicitly enabled|settings/apps' "$decl" "$ui"; then
        echo "    the remedy sentence is hardcoded in Kotlin — it belongs to the declaration alone"; bad=1
    fi
    return $bad
}

echo "── A the vault is the primary git credential path ──"
a1 "$PAGE" && pass "the page authenticates from the vault-delivered credential and lists with it, untapped" || fail "the git credential does not come from the vault-delivered config"
a3 "$PAGE" && pass "the browser device grant is drawn only when there is no vault credential" || fail "the page still demands a browser login while a vault credential is present"
a4 "$APPLY" && pass "one store, the declared credential_id, and a fresh phone keeps the token" || fail "the vault credential is not stored/read through libs:git-sync's own store"
python3 - "$BJ" <<'PYTHON'
import json, sys
g = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["applies"]["git"]
bad = 0
if g.get("key") != "github_token":
    print("    auth.applies.git.key is %s; the artifact's git section carries github_token" % g.get("key")); bad += 1
if not (g.get("credential_id") or "").strip():
    print("    auth.applies.git declares no credential_id: the vault token would have nowhere to live"); bad += 1
sys.exit(1 if bad else 0)
PYTHON
if [ $? -eq 0 ]; then pass "the artifact key and the credential id are both declared"; else fail "auth.applies.git is not declared for the vault path"; fi

echo "── R the error→remedy mapping is DECLARED ──"
r1 "$SHARED_BJ" && pass "auth.grant_remedies covers device_flow_disabled, names the switch and the vault path" || fail "the remedy mapping is missing or says nothing actionable"
r2 "$AUTH_DECL" "$SIGNIN_UI" && pass "libs:auth is the one reader and the one consumer; no remedy string in Kotlin" || fail "the remedy is not read from the declaration / is hardcoded"

echo "── MUT mutations ──"
MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT

copy="$MUT/page1.kt"; cp "$PAGE" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('DriveAuthApply.vaultGitToken(ctx.applicationContext)','DrivePrefs(ctx).getString()')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if a1 "$copy" >/dev/null 2>&1; then fail "MUT a credential from somewhere other than the vault passed — A1 does not hold"; else pass "MUT the credential resolved outside the vault-delivered config goes RED"; fi

copy="$MUT/page2.kt"; cp "$PAGE" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('} else if (webauth != null) {','}\nif (webauth != null) {')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if a3 "$copy" >/dev/null 2>&1; then fail "MUT a Start button drawn beside a vault credential passed — A3 does not hold"; else pass "MUT the browser login demanded while the vault credential is present goes RED"; fi

copy="$MUT/apply.kt"; cp "$APPLY" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('targets.isEmpty() && credential.isNotBlank() ->','targets.isEmpty() && false ->')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if a4 "$copy" >/dev/null 2>&1; then fail "MUT a fresh phone discarding the vault token passed — A4 does not hold"; else pass "MUT the fresh-phone case throwing the token away goes RED"; fi

copy="$MUT/shared.json"; cp "$SHARED_BJ" "$copy"
python3 -c "
import json,sys;p=sys.argv[1];d=json.load(open(p,encoding='utf-8'))
d['auth']['grant_remedies']=[r for r in d['auth']['grant_remedies'] if 'device_flow_disabled' not in r['match']]
json.dump(d,open(p,'w',encoding='utf-8'))" "$copy"
if r1 "$copy" >/dev/null 2>&1; then fail "MUT the removed remedy mapping passed — R1 does not hold"; else pass "MUT the device_flow_disabled remedy removed goes RED"; fi

copy="$MUT/ui.kt"; cp "$SIGNIN_UI" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('AuthDeclaration.explain(phase.message)','phase.message')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if r2 "$AUTH_DECL" "$copy" >/dev/null 2>&1; then fail "MUT an unworded failure passed — R2 does not hold"; else pass "MUT the failure shown without its remedy goes RED"; fi

a1 "$PAGE" >/dev/null 2>&1 && a3 "$PAGE" >/dev/null 2>&1 && a4 "$APPLY" >/dev/null 2>&1 \
    && r1 "$SHARED_BJ" >/dev/null 2>&1 && r2 "$AUTH_DECL" "$SIGNIN_UI" >/dev/null 2>&1 \
    && pass "MUT control: the unmutated sources pass every mutated check" \
    || fail "MUT control: the unmutated sources do NOT pass — the mutations above prove nothing"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-vault-credential: all checks passed"; else echo "test-drive-git-vault-credential: $FAILURES check(s) FAILED"; exit 1; fi
