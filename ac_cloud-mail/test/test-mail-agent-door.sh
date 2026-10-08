#!/usr/bin/env bash
# #913 - the fleet agent door to Cloud Mail: a QUERY-ONLY provider (messages by from / subject / since, one body)
# behind the CONSTELLATION_DATA signature permission, for Cloud Search's draft-only Agents.
#
#   G1  the manifest exports the provider behind the signature permission, on ${applicationId}.agentmail
#   G2  the provider implements query and nothing else: insert/update/delete answer null/0, no send/move/mark verb
#   G3  filters reach SQLite as bound arguments with LIKE escaped, never spliced; the statement is a SELECT
#   G4  a body read never marks the message read (agentBody opens with markRead = false)
#   G5  the contract's names match what Cloud Search declares (search.agents.engines.mail)
#   MUT each property, broken on a copy, goes red
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP="$ROOT/ac_cloud-mail"
D="$APP/app/src/main/kotlin/app/sterna/agentapi"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
CONTRACT="$D/AgentMailContract.kt"; PROVIDER="$D/AgentMailProvider.kt"
REPO="$APP/core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
SEARCH_BJ="$ROOT/ac_cloud-search/build.json"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for r in "$MANIFEST" "$CONTRACT" "$PROVIDER" "$REPO" "$SEARCH_BJ"; do
    [ -f "$r" ] || { echo "ERROR missing source: $r - this tester is unrun, not passing"; exit 1; }
done
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

g1() {
    python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read(); bad = 0
m = re.search(r'<provider\b[^>]*app\.sterna\.agentapi\.AgentMailProvider[^>]*?>', s, re.S)
if not m: print("    AgentMailProvider is not declared"); sys.exit(1)
t = m.group(0)
if 'android:exported="true"' not in t: print("    not exported"); bad = 1
if 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' not in t: print("    not guarded by the signature permission - any app could read the mail"); bad = 1
if "${applicationId}.agentmail" not in t: print("    authority is not ${applicationId}.agentmail"); bad = 1
sys.exit(bad)
PY
}
g2() {
    local f="$1" bad=0
    for sig in 'override fun insert(uri: Uri, values: ContentValues?): Uri? = null' \
               'override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0' \
               'override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0'; do
        grep -qF "$sig" "$f" || { echo "    a write verb is not stubbed out: $sig"; bad=1; }
    done
    local w; w="$(_code "$f" | grep -nE 'send\(|sendMessage|setRead|markRead *= *true|moveTo|deleteBy|setFlagged|setSeen|submit|outbox|emailDao|\.execSQL|DELETE |UPDATE |INSERT ' || true)"
    [ -z "$w" ] || { echo "    the provider reaches a write:"; printf '%s\n' "$w" | sed 's/^/        /'; bad=1; }
    return $bad
}
g3() {
    local c="$1" bad=0
    grep -qE 'SELECT id, accountId' "$c" || { echo "    the statement is not a spelled-out SELECT"; bad=1; }
    grep -qE "ESCAPE '" "$c" || { echo "    LIKE is not escaped"; bad=1; }
    grep -qE 'fun likeEscape' "$c" || { echo "    no escape function"; bad=1; }
    # a value must be added as an argument, never concatenated into the statement
    local w; w="$(_code "$c" | grep -nE 'where\.append\([^)]*(c\.from|c\.subject|c\.since)|"\$\{?c\.(from|subject)' || true)"
    [ -z "$w" ] || { echo "    a filter is spliced into the statement:"; printf '%s\n' "$w" | sed 's/^/        /'; bad=1; }
    return $bad
}
g4() {
    grep -qE 'openMessage\(credentials, emailId, markRead = false\)' "$1" || { echo "    agentBody can mark a message read"; return 1; }
}
g5() {
    python3 - "$SEARCH_BJ" "$CONTRACT" <<'PY'
import json, re, sys
j = json.load(open(sys.argv[1]))["search"]["agents"]["engines"]["mail"]
k = open(sys.argv[2]).read(); bad = 0
def c(n):
    m = re.search(r'const val %s = "([^"]+)"' % n, k); return m and m.group(1)
for key, const in (("authority_suffix", "AUTHORITY_SUFFIX"), ("path_messages", "PATH_MESSAGES"), ("path_body", "PATH_BODY"),
                   ("param_from", "P_FROM"), ("param_subject", "P_SUBJECT"), ("param_since", "P_SINCE"), ("param_limit", "P_LIMIT"),
                   ("param_account", "P_ACCOUNT"), ("param_id", "P_ID")):
    if c(const) != j.get(key): print("    search.agents.engines.mail.%s=%r but the engine says %r" % (key, j.get(key), c(const))); bad = 1
cols = re.search(r'MESSAGE_COLUMNS: Array<String> = arrayOf\((.*?)\)', k, re.S).group(1)
if re.findall(r'"([a-z_]+)"', cols) != j["message_columns"]: print("    message_columns differ"); bad = 1
bc = re.search(r'BODY_COLUMNS: Array<String> = arrayOf\((.*?)\)', k, re.S).group(1)
if re.findall(r'"([a-z_]+)"', bc) != j["body_columns"]: print("    body_columns differ"); bad = 1
if c("PERMISSION") != "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA": print("    permission differs"); bad = 1
sys.exit(bad)
PY
}

echo "-- G1 the provider is exported behind the signature permission --"
g1 "$MANIFEST" && pass "exported, CONSTELLATION_DATA-guarded, on \${applicationId}.agentmail" || fail "the door is unguarded or undeclared"
echo "-- G2 query only --"
g2 "$PROVIDER" && pass "insert/update/delete stubbed; no send, move, mark or delete reachable" || fail "the provider can write"
echo "-- G3 bound arguments --"
g3 "$CONTRACT" && pass "SELECT with bound, LIKE-escaped values" || fail "a filter can reach SQLite as SQL"
echo "-- G4 reading is not marking --"
g4 "$REPO" && pass "agentBody opens with markRead = false" || fail "reading marks mail read"
echo "-- G5 Cloud Search and the engine agree --"
g5 && pass "search.agents.engines.mail matches the engine's constants and columns" || fail "the declared contract and the engine disagree"

MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
HOLLOW=0
_red() { local label="$1"; shift; if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_mut() { python3 -c 'import sys;p,o,n=sys.argv[1:4];s=open(p).read();assert o in s,"mutation target absent: "+o;open(p,"w").write(s.replace(o,n,1))' "$@"; }
echo "-- MUT --"
cp "$MANIFEST" "$MUT/m.xml"; _mut "$MUT/m.xml" 'android:authorities="${applicationId}.agentmail"
            android:exported="true"
            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' 'android:authorities="${applicationId}.agentmail"
            android:exported="true"'; _red "G1 the permission guard removed" g1 "$MUT/m.xml"
cp "$MANIFEST" "$MUT/m.xml"; _mut "$MUT/m.xml" 'android:authorities="${applicationId}.agentmail"' 'android:authorities="${applicationId}.mailbox"'; _red "G1 the authority renamed" g1 "$MUT/m.xml"
cp "$PROVIDER" "$MUT/p.kt"; _mut "$MUT/p.kt" 'override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0' 'override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1'; _red "G2 delete no longer stubbed" g2 "$MUT/p.kt"
cp "$PROVIDER" "$MUT/p.kt"; _mut "$MUT/p.kt" 'override fun onCreate(): Boolean = true' 'override fun onCreate(): Boolean = true
    private suspend fun leak() { repo().setRead(null!!, "x", true) }'; _red "G2 a mark-read call in the provider" g2 "$MUT/p.kt"
cp "$CONTRACT" "$MUT/c.kt"; _mut "$MUT/c.kt" 'fun likeEscape' 'fun likeEsc'; _red "G3 no escape function" g3 "$MUT/c.kt"
cp "$CONTRACT" "$MUT/c.kt"; _mut "$MUT/c.kt" 'where.append(" AND subject LIKE ? ESCAPE' 'where.append(c.subject + " AND subject LIKE ? ESCAPE'; _red "G3 a filter spliced into the SQL" g3 "$MUT/c.kt"
cp "$REPO" "$MUT/r.kt"; _mut "$MUT/r.kt" 'openMessage(credentials, emailId, markRead = false)' 'openMessage(credentials, emailId, markRead = true)'; _red "G4 reading marks mail read" g4 "$MUT/r.kt"
cp "$CONTRACT" "$MUT/c.kt"; _mut "$MUT/c.kt" 'const val P_SINCE = "since"' 'const val P_SINCE = "after"'
SEARCH_BAK="$MUT/search.json"; cp "$SEARCH_BJ" "$SEARCH_BAK"
g5m() { python3 - "$MUT/c.kt" "$SEARCH_BJ" <<'PY'
import json, re, sys
j = json.load(open(sys.argv[2]))["search"]["agents"]["engines"]["mail"]
k = open(sys.argv[1]).read()
m = re.search(r'const val P_SINCE = "([^"]+)"', k)
sys.exit(0 if m and m.group(1) == j["param_since"] else 1)
PY
}
_red "G5 a parameter renamed in the engine only" g5m

[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))
echo "-- agent door (mail): $FAILURES failure(s) --"
[ "$FAILURES" -eq 0 ]
