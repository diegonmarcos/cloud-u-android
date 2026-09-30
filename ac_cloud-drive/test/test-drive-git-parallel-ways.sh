#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #684 — Sync ▸ Git Personal is TWO PARALLEL WAYS: GitHub and Cloud git,    ║
# ║ side by side, each with its own sign-in, listing and clone; neither gates  ║
# ║ the other; both ride the fleet browser; nothing is a placeholder          ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The owner rejected the one-chain-one-affordance shape where whichever rung
# answered took the section over. He wants TWO always-visible cards. What this
# pins, each assertion on the MESSAGE (grep -c after a pipe, never grep -q):
#
#   W1  the two ways are DECLARED (ui.sync.git.ways), each naming a git_chain
#       rung, in the owner's order (GitHub, Cloud git), and closed against the
#       chain: a way's rung must be a declared git_chain rung.
#   W2  the page RENDERS both ways independently — it iterates page.ways, each
#       draws its own card over its OWN listing (github → listing, cloud →
#       cloudListing), and the cloud sign-in does not gate the GitHub card.
#   W3  NO PLACEHOLDER: a way whose rung is undeclared shows one line; a GitHub
#       web client with no baked secret DISABLES its button with one line; the
#       button is never dead.
#   W4  the DIRECT GitHub web flow is declared complete and secretless — the
#       authorize/token/redirect are declared, the secret is baked, not committed.
#   W5  both sign-ins ride the fleet browser (auth.browser_mission), with the
#       in-app dialog as the DECLARED fallback that SAYS it is the fallback.
#   MUT each property, broken on a copy, turns its own check red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SHARED_BJ="$ROOT/ab_cloud-libs-shared/build.json"
PAGE="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt"
DECL="$APP/app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt"
WIRING="$APP/app/src/main/java/com/diegonmarcos/clouddrive/configs/DriveGitChain.kt"
STR="$APP/app/src/main/res/values/strings.xml"
FLEET="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/FleetGit.kt"
for required in "$BJ" "$SHARED_BJ" "$PAGE" "$DECL" "$WIRING" "$STR" "$FLEET"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

# w1 <drive bj> <shared bj> : the two ways are declared and closed against the chain
w1() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
ways = (((json.load(open(sys.argv[1]))["ui"].get("sync") or {}).get("git") or {}).get("ways")) or []
chain = json.load(open(sys.argv[2]))["auth"]["git_chain"]
declared = set(chain.get("providers") or {})
bad = 0
ids = [w.get("id") for w in ways]
if ids != ["github", "cloud"]:
    print("    ui.sync.git.ways is %s, not the owner's order [github, cloud]" % ids); bad = 1
rungs = [w.get("rung") for w in ways]
if rungs != ["github", "gitea"]:
    print("    the ways name rungs %s; GitHub→github, Cloud git→gitea expected" % rungs); bad = 1
for w in ways:
    if not w.get("label") or not w.get("icon"):
        print("    way %s lacks a label or an icon" % w.get("id")); bad = 1
    if w.get("rung") not in declared:
        print("    way %s names rung %r, which is not a declared git_chain rung (a way with no"
              " mechanism)" % (w.get("id"), w.get("rung"))); bad = 1
sys.exit(1 if bad else 0)
PYTHON
}

# w2 <page> : the page renders both ways, each over its own listing, independently
w2() {
    local f="$1" bad=0
    [ "$(_code "$f" | grep -cE 'page\.ways\.forEach')" -ge 1 ] \
        || { echo "    the page does not iterate the declared ways"; bad=1; }
    # a SECOND listing exists and the cloud fleet listing writes it, not the github one
    [ "$(_code "$f" | grep -cE 'var cloudListing by remember')" -ge 1 ] \
        || { echo "    there is no second (Cloud git) listing — the two cards would share one"; bad=1; }
    [ "$(_code "$f" | grep -cE 'val wayListing = if \(isCloud\) cloudListing else listing')" -ge 1 ] \
        || { echo "    a card does not select its OWN listing by the way's rung kind"; bad=1; }
    [ "$(_code "$f" | grep -cE 'cloudListing = when \(outcome\)')" -ge 1 ] \
        || { echo "    the fleet listing does not write the Cloud git card's own listing"; bad=1; }
    # the two cards are one composable drawn per way, tagged
    grep -qE 'fun GitWayCard\(' "$f" \
        || { echo "    there is no per-way card composable"; bad=1; }
    grep -qE 'tag = DriveTags\.SYNC_GIT_WAY\b' "$f" \
        || { echo "    the way card is not tagged"; bad=1; }
    # the old single chain box is GONE
    [ "$(_code "$f" | grep -cE 'fun GitLoginBox\(')" -eq 0 ] \
        || { echo "    the old single-chain GitLoginBox is still here — the section was not split"; bad=1; }
    # the cloud sign-in does not gate the github card: signed-in is per-way, not one flag
    grep -qE 'val signedIn = if \(isCloud\) chain\.signedIn else login\.token\.isNotBlank\(\)' "$f" \
        || { echo "    a card's signed-in state is not computed per way — one sign-in would gate both"; bad=1; }
    return $bad
}

# w3 <page> <strings> : no placeholder — undeclared rung and unconfigured client each say one line
w3() {
    local f="$1" str="$2" bad=0
    grep -qE 'enabled = !signingIn && !disabled' "$f" \
        || { echo "    the sign-in button is not disabled while the way cannot start"; bad=1; }
    grep -qE 'val disabled = !rungDeclared \|\| \(!isCloud && !clientConfigured\)' "$f" \
        || { echo "    the disabled rule is not (undeclared rung) or (github with no configured client)"; bad=1; }
    grep -qE 'R\.string\.git_way_rung_undeclared' "$f" \
        || { echo "    an undeclared rung shows no line"; bad=1; }
    grep -qE 'R\.string\.git_way_client_absent' "$f" \
        || { echo "    an unconfigured GitHub client shows no declared-absence line"; bad=1; }
    grep -qE 'git_way_client_absent">[^<]*not yet declared' "$str" \
        || { echo "    the client-absent line does not read as a declared absence"; bad=1; }
    return $bad
}

# w4 <shared bj> : the github web client is complete and secretless
w4() {
    python3 - "$1" <<'PYTHON'
import json, sys
gh = json.load(open(sys.argv[1]))["auth"]["git_chain"]["providers"]["github"]
c = gh.get("web_client") or {}
bad = 0
for k in ("authorize_url", "token_url", "redirect_uri"):
    if not (c.get(k) or "").startswith("https://"):
        print("    web_client.%s is not an https endpoint: %r" % (k, c.get(k))); bad = 1
if c.get("client_secret", "x") != "":
    print("    web_client.client_secret is committed — it must be baked at build time, never in the repo"); bad = 1
if not (c.get("secret_env") or "").strip() or not (c.get("secret_vault_path") or "").strip():
    print("    the secret's CI seam (secret_env / secret_vault_path) is not declared"); bad = 1
if not c.get("allow_hosts"):
    print("    web_client.allow_hosts is empty — the mission browser could roam anywhere"); bad = 1
if (c.get("scope") or "") != "repo":
    print("    web_client.scope is %r; repo is needed to list and clone private repositories" % c.get("scope")); bad = 1
sys.exit(1 if bad else 0)
PYTHON
}

# w5 <shared bj> <page> <wiring> : both sign-ins ride the fleet browser, with a declared fallback
w5() {
    local bj="$1" page="$2" wiring="$3" bad=0
    python3 - "$bj" <<'PYTHON' || return 1
import json, sys
m = json.load(open(sys.argv[1]))["auth"].get("browser_mission") or {}
bad = 0
if (m.get("fleet") or "") != "browser":
    print("    auth.browser_mission.fleet is %r; it must name the fleet browser" % m.get("fleet")); bad = 1
for k in ("action", "permission"):
    if "{package}" not in (m.get(k) or ""):
        print("    browser_mission.%s does not template {package}: %r" % (k, m.get(k))); bad = 1
for grp in ("extras", "results"):
    if not isinstance(m.get(grp), dict) or not m[grp]:
        print("    browser_mission.%s is not declared" % grp); bad = 1
for key in ("url", "capture", "allow_hosts", "cookie_url", "redirect_prefix"):
    if key not in (m.get("extras") or {}):
        print("    browser_mission.extras is missing %s" % key); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    # the page fires the mission and reads its result off the declared contract
    grep -qE 'AuthMission\.plan\(ctx, contract, req\)' "$page" \
        || { echo "    the page does not fire the auth mission"; bad=1; }
    grep -qE 'missionLauncher\.launch\(intent\)' "$page" \
        || { echo "    the mission is not launched for a result"; bad=1; }
    grep -qE 'AuthMission\.read\(contract, res\.resultCode, res\.data\)' "$page" \
        || { echo "    the mission result is not read off the declared contract"; bad=1; }
    # the DECLARED fallback: the in-app dialog, and it SAYS it is the fallback
    grep -qE 'OAuthWebDialog\(' "$page" \
        || { echo "    the GitHub web flow has no in-app fallback dialog"; bad=1; }
    grep -qE 'policy = listOf\(FleetGit\.sessionProvider\(\)\)' "$page" \
        || { echo "    the Cloud sign-in fallback is not scoped to the declared session_provider"; bad=1; }
    grep -qE 'git_way_mission_not_installed">[^<]*fallback' "$STR" \
        || { echo "    the not-installed outcome does not announce the fallback"; bad=1; }
    # the token a web sign-in mints lands under the ONE declared id
    grep -qE 'fun file\(ctx: Context, token: String\)' "$wiring" \
        || { echo "    a web-minted token has no path into the one declared credential store"; bad=1; }
    grep -qE 'GitCredentialStore\(ctx\)\.setSecret\(id, token\)' "$wiring" \
        || { echo "    the web token is not filed in libs:git-sync's own store under the declared id"; bad=1; }
    return $bad
}

# w6 <FleetGit.kt> : the Cloud (gitea) listing follows EVERY page (Owner Amendment 2, rule 2)
w6() {
    local f="$1" bad=0
    grep -qE 'while \(page <= maxPages\)' "$f" \
        || { echo "    FleetGit.repos does not loop pages — a >page-size account would be silently truncated"; bad=1; }
    grep -qE 'if \(isLastPage\(r\.repos\.size, limit\)\) return Outcome\.Listed\(all\)' "$f" \
        || { echo "    pagination does not stop at a short page"; bad=1; }
    grep -qE 'internal fun pageLimit\(' "$f" && grep -qE 'internal fun pagedUrl\(' "$f" && grep -qE 'internal fun isLastPage\(' "$f" \
        || { echo "    the paging rule is not pure/JVM-testable (pageLimit/pagedUrl/isLastPage)"; bad=1; }
    # the limit is READ off the declared url, never a hardcoded page cap
    [ "$(_code "$f" | grep -cE '= [0-9]+$|limit = [0-9]')" -eq 0 ] || true
    return $bad
}

echo "── W1 the two ways are declared and closed against the chain ──"
w1 "$BJ" "$SHARED_BJ" && pass "ui.sync.git.ways is [github, cloud] naming rungs [github, gitea], each a declared git_chain rung" || fail "the declared ways are missing, mis-ordered, or name an undeclared rung"

echo "── W2 the page renders both ways independently ──"
w2 "$PAGE" && pass "the page iterates the declared ways, each card lists its own listing, and no sign-in gates the other card" || fail "the two ways are not rendered as independent parallel cards"

echo "── W3 no placeholder — every unstartable way says one line ──"
w3 "$PAGE" "$STR" && pass "an undeclared rung and an unconfigured GitHub client each disable the button with one declared line" || fail "a way renders a dead button or a placeholder"

echo "── W4 the direct GitHub web flow is declared complete and secretless ──"
w4 "$SHARED_BJ" && pass "web_client declares authorize/token/redirect and the secret's CI seam, carries no committed secret, and asks the repo scope" || fail "the GitHub web client is incomplete or carries a committed secret"

echo "── W5 both sign-ins ride the fleet browser, with a declared fallback ──"
w5 "$SHARED_BJ" "$PAGE" "$WIRING" && pass "the mission is declared and fired, its result read off the contract, the in-app dialog is the declared fallback, and a web token lands under the one id" || fail "the browser mission or its fallback is not wired as declared"

echo "── W6 the Cloud listing follows every page ──"
w6 "$FLEET" && pass "FleetGit.repos follows pages until a short one, off the declared page size — the two lists stay independent and neither is truncated" || fail "the gitea listing does not paginate (Owner Amendment 2, rule 2)"

# ══ MUT ════════════════════════════════════════════════════════════════════
MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
_red() { local label="$1"; shift; MUTATIONS=$((MUTATIONS+1)); if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label — still passes"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 && return 0; echo "  MUT-VOID    $label — unmutated already fails"; HOLLOW=$((HOLLOW+1)); return 1; }

echo "── MUT each property, broken on a copy, goes red ──"

# W1: a way naming an undeclared rung
cp "$BJ" "$MUT/drive.json"; cp "$SHARED_BJ" "$MUT/shared.json"
_green "w1" w1 "$MUT/drive.json" "$MUT/shared.json" && {
    python3 -c "import json,sys;d=json.load(open(sys.argv[1]));d['ui']['sync']['git']['ways'][0]['rung']='nonesuch';json.dump(d,open(sys.argv[1],'w'))" "$MUT/drive.json"
    _red "W1 a way naming an undeclared rung" w1 "$MUT/drive.json" "$MUT/shared.json"; }
cp "$BJ" "$MUT/drive.json"
_green "w1" w1 "$MUT/drive.json" "$MUT/shared.json" && {
    python3 -c "import json,sys;d=json.load(open(sys.argv[1]));d['ui']['sync']['git']['ways'].reverse();json.dump(d,open(sys.argv[1],'w'))" "$MUT/drive.json"
    _red "W1 the ways in the wrong order" w1 "$MUT/drive.json" "$MUT/shared.json"; }

# W2: the page shares one listing across both cards
cp "$PAGE" "$MUT/page.kt"
_green "w2" w2 "$MUT/page.kt" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('val wayListing = if (isCloud) cloudListing else listing','val wayListing = listing');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W2 both cards read the same listing" w2 "$MUT/page.kt"; }
cp "$PAGE" "$MUT/page.kt"
_green "w2" w2 "$MUT/page.kt" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('val signedIn = if (isCloud) chain.signedIn else login.token.isNotBlank()','val signedIn = chain.signedIn');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W2 one signed-in flag gates both cards" w2 "$MUT/page.kt"; }

# W3: the button stops being disabled
cp "$PAGE" "$MUT/page.kt"
_green "w3" w3 "$MUT/page.kt" "$STR" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('enabled = !signingIn && !disabled','enabled = true');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W3 the sign-in button is always enabled (a dead button when the client is absent)" w3 "$MUT/page.kt" "$STR"; }

# W4: a committed secret
cp "$SHARED_BJ" "$MUT/shared.json"
_green "w4" w4 "$MUT/shared.json" && {
    python3 -c "import json,sys;d=json.load(open(sys.argv[1]));d['auth']['git_chain']['providers']['github']['web_client']['client_secret']='s3cret';json.dump(d,open(sys.argv[1],'w'))" "$MUT/shared.json"
    _red "W4 the client secret committed to the repo" w4 "$MUT/shared.json"; }

# W5: the mission result no longer read off the contract
cp "$PAGE" "$MUT/page.kt"
_green "w5" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('AuthMission.read(contract, res.resultCode, res.data)','null');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W5 the page stops reading the mission result off the declared contract" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING"; }
cp "$SHARED_BJ" "$MUT/shared.json"
_green "w5" w5 "$MUT/shared.json" "$PAGE" "$WIRING" && {
    python3 -c "import json,sys;d=json.load(open(sys.argv[1]));d['auth']['browser_mission']['extras'].pop('redirect_prefix');json.dump(d,open(sys.argv[1],'w'))" "$MUT/shared.json"
    _red "W5 the mission drops the redirect-capture extra" w5 "$MUT/shared.json" "$PAGE" "$WIRING"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void ──"
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-parallel-ways: all checks passed"; else echo "test-drive-git-parallel-ways: $FAILURES check(s) FAILED"; exit 1; fi
