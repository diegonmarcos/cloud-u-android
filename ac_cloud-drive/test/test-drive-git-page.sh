#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #608 — SYNC ▸ GIT is DECLARED and DENSE: two sections from build.json,    ║
# ║ a public set that is really public, a login that reuses the fleet's ONE  ║
# ║ sign-in for the `repo` scope, fourteen per-repository operations that     ║
# ║ every one reach libs:git-sync, and clones that land in the store's git    ║
# ║ folder — never a second git, never a second list, never a big card       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Gradle proves the page compiles. It cannot see that an
# operation the declaration promises has no handler (a button that does
# nothing), that a handler exists for an operation nobody declared (a verb with
# no label), that the "public" set contains a repository the API calls private
# (a clone that fails forever), that the listing endpoint was typed into Kotlin
# beside the declaration, that the force verbs quietly became synonyms for the
# plain ones, or that the "dense row" is a card again. This file can.
#
#   P1  the declaration is whole and ordered: ui.sync.git carries the two
#       sections (public, personal), the personal split (Public, Private), a
#       WebAuth way and an SSH way, an https api with {owner}/{name} in its web
#       url, the three remote modes with read-only marked, fourteen operations
#       including the six transport/commit verbs and the two DESTRUCTIVE ones,
#       an owner, the name families and a non-empty public set.
#   P2  the public set is derived, not hand-picked: every name begins with a
#       declared name family, the list is unique and alphabetical, THIS
#       repository (cloud-u-android) is in it, every repository the seed
#       manifest marks `seed` is in it, and nothing the manifest marks
#       `private: true` is.
#   P3  declaration ↔ dispatch, both directions: GitReposScreen's
#       `when (section.id)` is exactly the declared section ids, and its
#       `when (op.id)` is exactly the declared operation ids.
#   P4  every git verb is the ENGINE's: the coordinator's `when (opId)` calls
#       e.fetch / e.pull / e.commit / e.push / e.forcePush / e.forcePull,
#       GitEngine declares the two force verbs, the JVM suite EXERCISES them
#       (and proves the plain verbs refuse the same divergence first), and the
#       app still imports no JGit.
#   P5  the login is the fleet's ONE sign-in, narrowed: SignInWays is hosted
#       with policy = the DECLARED provider, the credential is
#       SignInResult.accessToken (which libs:auth DEFAULTS, so the superapp's
#       existing call sites are untouched), the SSH way reuses the key path the
#       per-repo sheet already holds, and no token is logged or persisted here.
#   P6  the listing comes from the declaration: no endpoint literal in the
#       page's sources, the parser is pure and JVM-tested, and the two groups
#       are the PROVIDER's own `private` flag, each alphabetical.
#   P7  the reads are real reads: hooks = .git/hooks minus *.sample, workflows =
#       .github/workflows/*.y(a)ml, size walks the tree without following a
#       symlink, all three JVM-tested by name.
#   P8  DENSE: the repository row is a Row with a height floor and a status DOT,
#       NOT a DriveCard; the operations rail lives behind the row's expand
#       disclosure; and the clone lands in SharedStore.repoDir.
#   M   mutation-proof: an operation dropped from the declaration → P3 red; a
#       section id misspelt in the screen → P3 red; a private repository added
#       to the public set → P2 red; forcePush removed from the engine → P4 red;
#       the unmutated tree stays green.
#
# OWN-SOURCE ONLY: everything read here is under this app or a module dir its
# build.json declares. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
PAGE="$SRC/sync/GitReposScreen.kt"
COORD="$SRC/sync/GitSyncCoordinator.kt"
LIST="$SRC/sync/GitHubRepos.kt"
SCAN="$SRC/sync/GitRepoScan.kt"
DECL="$SRC/Declarations.kt"
STORE="$SRC/SharedStore.kt"
REPOS="$APP/data/drive-git-repos.json"
JVM="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/GitPageTest.kt"
GIT_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:git-sync"]["dir"])))' "$BJ" "$APP")"
ENGINE="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitEngine.kt"
ENGINE_TEST="$GIT_MOD/src/test/java/com/diegonmarcos/cloudlib/gitsync/GitEngineTest.kt"
AUTH_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:auth"]["dir"])))' "$BJ" "$APP")"
AUTH_UI="$AUTH_MOD/src/main/java/com/diegonmarcos/cloudlib/auth/SignInUi.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$PAGE" "$COORD" "$LIST" "$SCAN" "$DECL" "$STORE" "$REPOS" "$JVM" "$ENGINE" "$ENGINE_TEST" "$AUTH_UI"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block can run them on copies ──

# p1 <build.json>
p1() {
    python3 - "$1" <<'PYTHON'
import json, sys
g = ((json.load(open(sys.argv[1]))["ui"].get("sync") or {}).get("git") or {})
bad = 0
if [s.get("id") for s in g.get("sections", [])] != ["public", "personal"]:
    print("    ui.sync.git.sections is %s, not public/personal" % [s.get("id") for s in g.get("sections", [])]); bad = 1
if any(not s.get("label") or not s.get("icon") for s in g.get("sections", [])):
    print("    a section lacks a label or an icon"); bad = 1
if not any(s.get("id") == "personal" and s.get("login_required") for s in g.get("sections", [])):
    print("    the personal section does not state login_required"); bad = 1
if [x.get("id") for x in g.get("personal_groups", [])] != ["public", "private"]:
    print("    ui.sync.git.personal_groups is %s, not public/private" % [x.get("id") for x in g.get("personal_groups", [])]); bad = 1
kinds = [w.get("kind") for w in g.get("login_ways", [])]
if "webauth" not in kinds or "ssh_key" not in kinds:
    print("    login_ways carries %s: both a webauth and an ssh_key way are required" % kinds); bad = 1
web = next((w for w in g.get("login_ways", []) if w.get("kind") == "webauth"), {})
if not web.get("provider") or web.get("scope") != "repo" or not web.get("lists"):
    print("    the webauth way must name a provider, ask the `repo` scope and state lists: true (got %r)" % web); bad = 1
if next((w for w in g.get("login_ways", []) if w.get("kind") == "ssh_key"), {}).get("lists"):
    print("    the ssh_key way claims it lists an account: SSH cannot"); bad = 1
api = g.get("api") or {}
if not (api.get("base_url") or "").startswith("https://") or not api.get("repos_path") or (api.get("max_pages") or 0) < 1:
    print("    ui.sync.git.api is incomplete: %r" % api); bad = 1
if "{owner}" not in (api.get("web_url") or "") or "{name}" not in (api.get("web_url") or ""):
    print("    api.web_url does not carry the {owner}/{name} placeholders"); bad = 1
modes = {m.get("id"): m for m in g.get("remote_modes", [])}
if sorted(modes) != ["https", "readonly", "ssh"]:
    print("    remote_modes is %s, not https/ssh/readonly" % sorted(modes)); bad = 1
for mid, m in modes.items():
    if "{owner}" not in (m.get("url") or "") or "{name}" not in (m.get("url") or ""):
        print("    remote mode %s has no {owner}/{name} url shape" % mid); bad = 1
if not (modes.get("readonly") or {}).get("read_only"):
    print("    the read-only mode does not state read_only: true, so push would be offered on it"); bad = 1
if (modes.get("ssh") or {}).get("url", "").startswith("https"):
    print("    the ssh mode's url is an https url"); bad = 1
ops = [o.get("id") for o in g.get("ops", [])]
want = ["fetch", "pull", "commit", "push", "force_push", "force_pull", "metadata", "web",
        "stats", "history", "remote", "hooks", "actions", "size"]
if ops != want:
    print("    ui.sync.git.ops is %s, not the fourteen declared operations in order %s" % (ops, want)); bad = 1
if any(not o.get("label") or not o.get("icon") for o in g.get("ops", [])):
    print("    an operation lacks a label or an icon"); bad = 1
destructive = sorted(o["id"] for o in g.get("ops", []) if o.get("destructive"))
if destructive != ["force_pull", "force_push"]:
    print("    the destructive operations are %s: exactly force_push and force_pull must be marked" % destructive); bad = 1
if not g.get("owner") or not g.get("name_families") or not g.get("public_repos"):
    print("    owner, name_families or public_repos is empty"); bad = 1
sys.exit(bad)
PYTHON
}

# p2 <build.json> <drive-git-repos.json>
p2() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
manifest = json.load(open(sys.argv[2]))["repos"]
families = tuple(g["name_families"])
names = [r["repo"] for r in g["public_repos"]]
bad = 0
strays = [n for n in names if not n.startswith(families)]
if strays:
    print("    public_repos entries outside the declared name families %s: %s" % (list(families), strays)); bad = 1
if len(set(names)) != len(names):
    print("    public_repos has duplicates"); bad = 1
if names != sorted(names):
    print("    public_repos is not alphabetical by name: %s" % names); bad = 1
if any(not r.get("caption") for r in g["public_repos"]):
    print("    a public_repos entry has no caption"); bad = 1
if "cloud-u-android" not in names:
    print("    cloud-u-android is missing from the declared public set (#608 names it explicitly)"); bad = 1
by_name = {r["name"]: r for r in manifest}
for r in manifest:
    if r.get("seed") and r["name"] not in names:
        print("    %s is seeded into the store but absent from the page's public set" % r["name"]); bad = 1
private_here = [n for n in names if by_name.get(n, {}).get("private")]
if private_here:
    print("    the public set carries repositories the seed manifest marks private: %s" % private_here); bad = 1
sys.exit(bad)
PYTHON
}

# p3 <build.json> <GitReposScreen.kt>
p3() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
src = open(sys.argv[2], encoding="utf-8").read()
bad = 0
def dispatched(subject):
    """The quoted ids of `when (subject) { ... }`, delimited by BRACE MATCHING — a
    regex that stops at the first closing brace reads only the first branch, and
    would then report a one-branch dispatch as complete."""
    head = "when (%s) {" % subject
    at = src.find(head)
    if at < 0: return None
    i = at + len(head)
    depth = 1
    while i < len(src) and depth:
        if src[i] == "{": depth += 1
        elif src[i] == "}": depth -= 1
        i += 1
    body = src[at + len(head):i - 1]
    # Only the branch LABELS: a quoted literal at the start of a line, or after a comma
    # on that same label list. Anything inside a branch body is not a dispatched id.
    ids = []
    for line in body.splitlines():
        m = re.match(r'\s*("[a-z_]+"(?:\s*,\s*"[a-z_]+")*)\s*->', line)
        if m: ids += re.findall(r'"([a-z_]+)"', m.group(1))
    return sorted(set(ids))
sections = dispatched("section.id")
if sections is None:
    print("    GitReposScreen has no `when (section.id)` dispatch"); sys.exit(1)
declared_sections = sorted(s["id"] for s in g["sections"])
if sections != declared_sections:
    print("    sections declared %s vs dispatched %s" % (declared_sections, sections)); bad = 1
ops = dispatched("op.id")
if ops is None:
    print("    GitReposScreen has no `when (op.id)` dispatch"); sys.exit(1)
declared_ops = sorted(o["id"] for o in g["ops"])
if ops != declared_ops:
    print("    operations declared %s vs dispatched %s" % (declared_ops, ops)); bad = 1
sys.exit(bad)
PYTHON
}

# p4 <GitSyncCoordinator.kt> <GitEngine.kt>
p4() {
    grep -qE 'when \(opId\) \{' "$1" &&
    grep -qE 'OP_FETCH -> e\.fetch\(auth = auth\)' "$1" &&
    grep -qE 'OP_PULL -> e\.pull\(rebase = repo\.pullRebase, auth = auth\)' "$1" &&
    grep -qE 'e\.commit\(' "$1" &&
    grep -qE 'OP_PUSH -> e\.push\(auth = auth\)' "$1" &&
    grep -qE 'OP_FORCE_PUSH -> e\.forcePush\(auth = auth\)' "$1" &&
    grep -qE 'OP_FORCE_PULL -> e\.forcePull\(auth = auth\)' "$1" &&
    grep -qE '^\s*fun forcePush\(' "$2" &&
    grep -qE '^\s*fun forcePull\(' "$2"
}

echo "── P1 the declaration is whole and ordered ──"
p1 "$BJ" && pass "ui.sync.git declares both sections, both groups, both login ways, the https api, the three remote modes and the fourteen operations" || fail "ui.sync.git is not what #608 declares"

echo "── P2 the public set is derived, not hand-picked ──"
p2 "$BJ" "$REPOS" && pass "every declared public repository is in a declared name family, unique, alphabetical; cloud-u-android is in it; the seed set is a subset; no manifest-private repository is" || fail "the declared public set disagrees with its own rule or with the seed manifest"

echo "── P3 declaration ↔ dispatch, both directions ──"
p3 "$BJ" "$PAGE" && pass "the screen dispatches exactly the declared sections and exactly the declared operations" || fail "the page's dispatch and the declaration disagree"

echo "── P4 every git verb is the engine's ──"
p4 "$COORD" "$ENGINE" && pass "the coordinator runs each transport/commit operation through GitEngine, force verbs included" || fail "an operation does not go through the engine (or the engine lacks the force verbs)"
if grep -qE 'fun forcePushOverwritesTheRemoteAndForcePullOverwritesTheLocalSide' "$ENGINE_TEST" \
   && grep -qE 'assertFalse\("a diverged push must be rejected' "$ENGINE_TEST" \
   && grep -qE 'forced\.summary\.contains\("force-pushed"\)' "$ENGINE_TEST" \
   && grep -qE 'forced\.summary\.contains\("force-pulled"\)' "$ENGINE_TEST"; then
    pass "the JVM suite drives both force verbs on real repositories AND shows the plain verbs refuse the same divergence first"
else
    fail "GitEngineTest does not prove the force verbs do what the plain ones cannot"
fi
if grep -rqE 'org\.eclipse\.jgit' "$SRC"; then fail "the app imports JGit directly — libs:git-sync is the engine"; else pass "no JGit in the app: every verb goes through the engine"; fi
if grep -qE 'setForce\(true\)' "$ENGINE" && grep -qE 'ResetCommand\.ResetType\.HARD\)\.setRef\(ref\.name\)' "$ENGINE"; then pass "forcePush takes the fast-forward check off; forcePull resets HARD onto the fetched remote ref"; else fail "the force verbs are not implemented as force (a synonym for push/pull would pass every name check)"; fi

echo "── P5 the login is the fleet's ONE sign-in, narrowed ──"
if grep -qE 'SignInWays\(host = host, policy = listOf\(webauth\.provider\)' "$PAGE"; then pass "the page hosts libs:auth's own surface, narrowed to the DECLARED provider"; else fail "the page does not host SignInWays with the declared provider as the policy"; fi
# #629 the browser grant is now the FALLBACK and marks its token as not-from-the-vault; the
# vault-first path itself is pinned by test-drive-git-vault-credential.sh.
if grep -qE 'login = login\.copy\(identity = result\.identity, token = result\.accessToken, fromVault = false\)' "$PAGE" && grep -qE 'fetchListing\(result\.accessToken\)' "$PAGE"; then pass "the listing and the private clone use the approval's own access token"; else fail "the page does not take the token off the sign-in result"; fi
if grep -qE 'val accessToken: String = ""' "$AUTH_UI"; then pass "libs:auth carries the token as a DEFAULTED field — the superapp's existing call sites are untouched"; else fail "SignInResult.accessToken is missing or not defaulted (a required field would break the other consumer)"; fi
if grep -qE 'accessToken = approval\.accessToken' "$AUTH_UI"; then pass "the device-grant dialog fills it in"; else fail "the device grant does not pass its token through"; fi
if grep -qE 'R\.string\.sync_auth_key_path' "$PAGE" && grep -qE 'sshKeyPath = if \(ssh\) login\.sshKeyPath else ""' "$PAGE" && grep -qE 'GitSyncCoordinator\.AUTH_SSH' "$PAGE"; then pass "the SSH way reuses the key path libs:git-sync already holds — no second key mechanism"; else fail "the SSH login does not reuse the existing key mechanism"; fi
LEAK="$(grep -nE 'Log\.[a-z]+\(.*(token|accessToken)|putString\(.*token' "$PAGE" "$LIST" || true)"
if [ -z "$LEAK" ]; then pass "no token is logged or written to preferences by the page"; else fail "a token leaves memory:"; printf '%s\n' "$LEAK" | sed 's/^/        /'; fi

echo "── P6 the listing comes from the declaration ──"
URLS="$(grep -nE '"https://' "$PAGE" "$LIST" "$COORD" || true)"
if [ -z "$URLS" ]; then pass "no endpoint literal beside the declaration"; else fail "an endpoint is typed into Kotlin:"; printf '%s\n' "$URLS" | sed 's/^/        /'; fi
if grep -qE 'fun parse\(text: String\): List<Repo>' "$LIST" && grep -qE 'fun fetch\(api: Declarations\.GitApiDecl, token: String\)' "$LIST"; then pass "the parser takes TEXT (JVM-testable) and the fetch takes the declared api"; else fail "GitHubRepos is not split into a pure parser and a declared fetch"; fi
if grep -qE 'fun group\(repos: List<Repo>, wantPrivate: Boolean\): List<Repo> =\s*$' "$LIST" && grep -qE 'repos\.filter \{ it\.private == wantPrivate \}\.sortedBy \{ it\.name\.lowercase\(\) \}' "$LIST"; then pass "the two groups are the PROVIDER's own private flag, each alphabetical"; else fail "the public/private split is not the provider's flag (or is unsorted)"; fi
if grep -qE 'page\.personalGroups\.forEach' "$PAGE" && grep -qE 'GitHubRepos\.group\(listing\.repos, group\.id == Declarations\.GIT_GROUP_PRIVATE\)' "$PAGE"; then pass "the page renders the DECLARED groups, in declared order"; else fail "the personal section does not render the declared groups"; fi
if grep -qE 'complete = false' "$LIST" && grep -qE 'R\.string\.git_listing_truncated' "$PAGE"; then pass "a listing stopped at the declared page cap SAYS so instead of showing a silent prefix"; else fail "a truncated listing is not reported"; fi

echo "── P7 the reads are real reads ──"
if grep -qE '\.git/hooks' "$SCAN" && grep -qE '!it\.name\.endsWith\("\.sample"\)' "$SCAN"; then pass "hooks = .git/hooks minus git's own samples"; else fail "the hooks read would count git's sample files as installed hooks"; fi
if grep -qE '\.github/workflows' "$SCAN" && grep -qE 'endsWith\("\.yml"\) \|\| it\.name\.endsWith\("\.yaml"\)' "$SCAN"; then pass "workflows = .github/workflows/*.y(a)ml"; else fail "the workflow read does not look where GitHub looks"; fi
if grep -qE 'Files\.isSymbolicLink\(child\.toPath\(\)\)' "$SCAN"; then pass "the size walk counts a symlink and never follows it (a link up the tree would walk forever)"; else fail "the size walk follows symlinks"; fi
for t in hooksSkipTheSamples workflowsAreTheYamlOnes sizeCountsTheTreeAndTheGitDirSeparately listingSplitsOnTheProvidersOwnFlag declaredPublicSetMatchesItsOwnRule remoteModesComposeTheDeclaredUrls; do
    grep -qE "fun $t\(" "$JVM" && pass "the JVM suite proves $t" || fail "GitPageTest lacks $t"
done

echo "── P8 dense, not a card per repository ──"
if grep -qE 'private fun GitRepoRow\(' "$PAGE" && grep -qE 'testTag\(DriveTags\.SYNC_GIT_ROW\)' "$PAGE" && grep -qE '\.heightIn\(min = DriveMetrics\.rowHeight\)' "$PAGE" && grep -qE 'StatusDot\(light, name\)' "$PAGE"; then pass "one compact Row per repository, with a height floor and the one-character light"; else fail "the repository row is not the dense row #608 asked for"; fi
if python3 - "$PAGE" <<'PYTHON'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
row = re.search(r"private fun GitRepoRow\((.*?)\n@Composable", src, re.S)
if not row: print("    GitRepoRow not found"); sys.exit(1)
if "DriveCard(" in row.group(1): print("    GitRepoRow draws a DriveCard: the dense row must not be a card"); sys.exit(1)
sys.exit(0)
PYTHON
then pass "the row itself draws no card — the card is what EXPANDING it shows"; else fail "the dense row is a card again"; fi
if grep -qE 'private fun GitRepoOpsBox\(' "$PAGE" && grep -qE 'testTag\(DriveTags\.SYNC_GIT_OPS\)' "$PAGE" && grep -qE 'if \(expanded == key\) \{' "$PAGE" && grep -qE 'Icons\.Filled\.ExpandLess else Icons\.Filled\.ExpandMore' "$PAGE"; then pass "the operations rail is collapsed behind the row's own disclosure"; else fail "the operations are not collapsed behind a disclosure"; fi
if grep -qE 'confirm = op' "$PAGE" && grep -qE 'R\.string\.git_confirm_force_push' "$PAGE" && grep -qE 'R\.string\.git_confirm_force_pull' "$PAGE"; then pass "a destructive operation asks first and says which side it destroys"; else fail "force push / force pull run on the tap"; fi
if grep -qE 'val blocked = readOnly && \(op\.id == GitSyncCoordinator\.OP_PUSH \|\| op\.id == GitSyncCoordinator\.OP_FORCE_PUSH\)' "$PAGE" && grep -qE 'R\.string\.git_readonly_blocked' "$PAGE"; then pass "a read-only remote refuses push and force push instead of offering a button that always fails"; else fail "the read-only remote mode is not enforced"; fi
if grep -qE 'val dir = SharedStore\.repoDir\(name\)' "$COORD"; then pass "a clone lands in the store's git folder, the same one the seed uses"; else fail "the page's clone does not land in SharedStore.repoDir"; fi

echo "== M mutation-proof =="
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["ops"]=b["ui"]["sync"]["git"]["ops"][:-1]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/no-size-op.json"
p3 "$TMP/no-size-op.json" "$PAGE" >/dev/null && fail "P3 passed a declaration missing an operation the screen handles (tester is vacuous)" || pass "an operation dropped from ui.sync.git.ops → P3 RED"
p1 "$TMP/no-size-op.json" >/dev/null && fail "P1 passed a thirteen-operation declaration (tester is vacuous)" || pass "the same drop → P1 RED"
sed 's/"personal" -> {/"personnal" -> {/' "$PAGE" > "$TMP/page.kt"
cmp -s "$PAGE" "$TMP/page.kt" && fail "the section mutation changed nothing (tester is stale)"
p3 "$BJ" "$TMP/page.kt" >/dev/null && fail "P3 passed a misspelt section dispatch (tester is vacuous)" || pass "a section id misspelt in the screen → P3 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["public_repos"].append({"repo":"cloud-data-my-ai-memory","caption":"x"}); b["ui"]["sync"]["git"]["public_repos"].sort(key=lambda r:r["repo"]); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/private-in-public.json"
p2 "$TMP/private-in-public.json" "$REPOS" >/dev/null && fail "P2 passed a PRIVATE repository in the public set (tester is vacuous)" || pass "a private repository added to the public set → P2 RED"
grep -v '    fun forcePush(' "$ENGINE" > "$TMP/engine.kt"
cmp -s "$ENGINE" "$TMP/engine.kt" && fail "the engine mutation changed nothing (tester is stale)"
p4 "$COORD" "$TMP/engine.kt" >/dev/null && fail "P4 passed an engine without forcePush (tester is vacuous)" || pass "forcePush removed from the engine → P4 RED"
p1 "$BJ" >/dev/null && p2 "$BJ" "$REPOS" >/dev/null && p3 "$BJ" "$PAGE" >/dev/null && p4 "$COORD" "$ENGINE" >/dev/null \
    && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-page: all checks passed"; else echo "test-drive-git-page: $FAILURES check(s) FAILED"; exit 1; fi
