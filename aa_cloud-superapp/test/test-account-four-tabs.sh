#!/usr/bin/env bash
# Tester (#778): Configs ▸ Account is exactly FOUR tabs — Connect, Profiles,
# Runtime, Drift — and each keeps its promise. (Was test-account-three-tabs.sh,
# #695/#713/#766; Connect's sections A/A2/B/E/G/V are unchanged.)
#
# The journey is test-profile-journey.sh; AccountDriftTest / AccountStoreTest /
# AccountUploadTest / AccountModelTest / AccountTabsComposeTest run the rules on
# the JVM. This file pins what those cannot:
#   A  CONNECT is three declared LINES, in this order: Authelia → Gitea (WebAuth |
#      Bearer), GitHub (WebAuth | SSH / PAT), Import File; every way is dispatched
#      on its kind alone, and a kind with no handler carries its declared reason.
#   A2 IMPORT FILE is no second importer: ImportConfigsFragment.classify, then
#      landVault (every sign-in's landing); every other verdict refused in red.
#   B  NO GitHub OAuth app on this surface.
#   C  PROFILES is the declared copy (L, else S) through the declared mask: a
#      masked row carries only a length, the rule fails closed, every value drawn
#      on Profiles / Runtime / Drift passes shownValue → the mask, and the debug
#      API neither prints nor sets a masked field.
#   T  THE STRIP is the four declared tabs in order, labels off the declaration,
#      each id one column, each tab tagged account:<id>.
#   D  DRIFT: the declared pairs (S↔R, L↔S, L↔R), the upload target BESIDE the
#      generated server file, R comparisons limited to what R observed,
#      runtime → declared never taking an unobserved, read-only or empty value, the
#      upload naming its sha with the token in a header only; every action (push /
#      pull per item, app, all; upload; discard; export; populate; save) is one
#      model call; /api/account/* advertised, served and registered.
#   E  the GitHub token (PAT) is used once: never stored, logged or in a URL.
#   G  CONNECT IS THE JOURNEY AND NOTHING ELSE, every way the SAME pill, GitHub
#      WebAuth is gh's own sign-in in the gh engine.
#   V  EVERY LINE FETCHES THE VAULT CONFIGS AND ANSWERS WHICH DEVICE.
#   F  PROFILES IS THE WHOLE SCHEMA: the declared skeleton matches cloud-vault's
#      schema.json + sources.json when the vault sits beside; every declared field
#      has a row, filled or empty.
#   F2 NO VAULT SECTION IS DROPPED, and the apps topic lists each declared app.
#   H  RUNTIME IS PER APP: every declared app has a live reader (its `runtime`
#      block says how it is reached) and a status card; Setup's index, wizard,
#      cockpit cards and repos are deleted; every landing stores S.
# Every rule is mutation-proved below; each mutation is checked to have APPLIED.
set -uo pipefail
APP="${SA_APP:-$(cd "$(dirname "$0")/.." && pwd)}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
SHARED="$APP/../ab_cloud-libs-shared/build.json"
PKG="$APP/app/src/main/java/com/diegonmarcos/superapp/profile"
PF="$PKG/ProfileFragment.kt"
IM="$PKG/InfoMask.kt"
CP="$PKG/VaultCockpit.kt"
GS="$PKG/GitSshVault.kt"
GE="$PKG/GhEngine.kt"
AT="$PKG/AccountTabs.kt"      # #778 Profiles · Runtime · Drift (Compose)
AM="$PKG/AccountModel.kt"     # the one model the tabs and the debug API share
AD="$PKG/AccountDrift.kt"     # the three-file engine
AR="$PKG/AccountRuntime.kt"   # per-app live readers / pushers
AU="$PKG/AccountUpload.kt"    # L → server commit
ADA="$PKG/AccountDebugApi.kt" # /api/account/*
RES="$APP/app/src/main/res"
for f in "$BJ" "$SHARED" "$PF" "$IM" "$CP" "$GS" "$GE" "$AT" "$AM" "$AD" "$AR" "$AU" "$ADA" "$RES/values/strings.xml"; do
    [ -f "$f" ] || { echo "FAIL: $f missing — this tester is unrun, not passing"; exit 1; }
done
# Code only — whole-line comments dropped, so prose about what must not happen
# does not read as it happening. Captured text is grepped through here-strings
# (never `echo | grep -q` under pipefail, #585b).
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }
fnof() { awk -v n="$2" '$0 ~ "private fun "n"\\(" {f=1} f{print} f&&/^    }$/{exit}' "$1"; }
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT

# ── A · Connect: the declared lines ─────────────────────────────────────────
lines_ok() {   # $1 = build.json, $2 = shared build.json; prints the first broken rule
    python3 - "$1" "$2" <<'PYA'
import json, sys
c = json.load(open(sys.argv[1]))["ui"]["profile"].get("connect") or {}
auth = {p.get("kind") for p in json.load(open(sys.argv[2]))["auth"]["sign_in"]["providers"]}
PAGE = {"github_ssh_pat", "vault_file", "gh_auth_login"}
lines = c.get("lines") or []
def die(m): print(m); sys.exit(1)
if len(lines) != 3: die("not exactly three lines (%d)" % len(lines))
if len({l.get("id") for l in lines}) != 3: die("line ids are not unique")
# Render order is declaration order: sign-in, sign-in, then the file alternative.
want = [{"authelia_web", "authelia_bearer"}, {"gh_auth_login", "github_ssh_pat"}, {"vault_file"}]
for l, w in zip(lines, want):
    ways = l.get("ways") or []
    if len(ways) != len(w): die("line %s has %d ways, not %d" % (l.get("id"), len(ways), len(w)))
    if not str(l.get("label", "")).strip(): die("line %s has no label" % l.get("id"))
    if {x.get("kind") for x in ways} != w: die("line %s ways are %s, not %s" % (l.get("id"), sorted(x.get("kind") for x in ways), sorted(w)))
    for x in ways:
        if not str(x.get("label", "")).strip(): die("way %s has no label" % x.get("id"))
        k = x.get("kind")
        if k not in auth and k not in PAGE and not str(x.get("note", "")).strip():
            die("way %s (kind %s) has no handler and no note saying why" % (x.get("id"), k))
if not str(c.get("vault_file", "")).strip(): die("no vault_file declared")
t = c.get("github_contents_url", "")
if not all(p in t for p in ("{repo}", "{path}", "{ref}")): die("github_contents_url is not a {repo}/{path}/{ref} template")
PYA
}
echo "== A: Connect is three declared lines in order, each way dispatched on its kind =="
msg=$(lines_ok "$BJ" "$SHARED") && ok "A: three lines in order (Authelia, GitHub, Import File), kinds as declared, every unhandled kind carries its reason" || bad "A: $msg"
grep -q 'UI_PROFILE_CONNECT_B64' "$APP/app/build.gradle" && grep -q 'BuildConfig.UI_PROFILE_CONNECT_B64' "$PF" \
    && ok "A: the lines are baked and read off the declaration" || bad "A: UI_PROFILE_CONNECT_B64 is not baked or not read"
grep -q 'for (line in connectLines())' "$PF" && grep -q 'buildWay(ctx, s, way, policy, cell, extras, status)' "$PF" \
    && ok "A: step 1 iterates the declared lines and ways" || bad "A: step 1 does not iterate connectLines()"
BW=$(fnof "$PF" buildWay | codeof)
grep -qF 'SignIn.offered(policy).filter { it.kind.name.lowercase() == way.kind }' <<<"$BW" \
    && ok "A: libs:auth kinds are matched generically (a provider's kind, lower-cased)" || bad "A: the Authelia ways are not matched on the lib's kinds"
grep -qF 'getString(R.string.connect_way_unwired, way.label, way.note)' <<<"$BW" \
    && ok "A: a kind with no handler draws its declared reason" || bad "A: an unhandled kind does not say why"
KT_AUTH_KINDS=$(jq -r '.auth.sign_in.providers[].kind' "$SHARED" | sort -u)
lit=""; for k in $KT_AUTH_KINDS; do grep -qF "\"$k\"" <<<"$(codeof "$PF")" && lit="$lit $k"; done
[ -z "$lit" ] && ok "A: no sign-in kind is a Kotlin literal in the fragment" || bad "A: kind literal(s) in Kotlin:$lit"
for k in GITHUB_SSH_PAT:github_ssh_pat GH_AUTH_LOGIN:gh_auth_login VAULT_FILE:vault_file; do
    # (`vault_file` is also the connect block's JSON key, read by optString — that use is not a kind)
    [ "$(codeof "$PF" | grep -v 'optString("vault_file")' | grep -cF "\"${k#*:}\"")" = 1 ] && grep -q "private const val KIND_${k%%:*} = \"${k#*:}\"" "$PF" \
        && ok "A: the page kind ${k#*:} is named once" || bad "A: the page kind ${k#*:} is not named exactly once"
done
LABELS=$(jq -r '.ui.profile.connect.lines[] | .label, .ways[].label' "$BJ")
lab=""; while IFS= read -r l; do [ -n "$l" ] && grep -qF "\"$l\"" <<<"$(codeof "$PF")" && lab="$lab [$l]"; done <<<"$LABELS"
[ -z "$lab" ] && ok "A: no line or way label is a Kotlin literal" || bad "A: label(s) typed in Kotlin:$lab"

echo "-- A-mutation: a note dropped, a fourth line, the file line dropped or moved, an undeclared kind, a kind typed in Kotlin --"
amut() {   # $1 = python over c (the connect block); 0 iff lines_ok goes red; 2 iff nothing changed
    cp "$BJ" "$TMP/a.json"
    python3 - "$TMP/a.json" "$1" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p)); c=d["ui"]["profile"]["connect"]; exec(sys.argv[2]); json.dump(d,open(p,"w"))
PY
    cmp -s "$BJ" "$TMP/a.json" && return 2
    ! lines_ok "$TMP/a.json" "$SHARED" >/dev/null
}
for m in 'c["lines"][1]["ways"][0]["label"]=""' \
         'c["lines"].append({"id":"x","label":"X","ways":[]})' \
         'c["lines"].pop(2)' \
         'c["lines"].insert(0, c["lines"].pop(2))' \
         'c["lines"][2]["ways"][0]["kind"]="file_magic"' \
         'c["lines"][0]["ways"][0]["kind"]="authelia_magic"' \
         'c.pop("vault_file")'; do
    amut "$m"; rc=$?
    case $rc in 0) ok "A-mutation: caught — $m";; 2) bad "A-mutation: did not apply — $m";; *) bad "A-mutation: NOT caught — $m";; esac
done
sed 's/val offered = SignIn.offered(policy).filter { it.kind.name.lowercase() == way.kind }/val offered = SignIn.offered(policy).filter { way.kind == "authelia_web" }/' "$PF" > "$TMP/a.kt"
if cmp -s "$PF" "$TMP/a.kt"; then bad "A-mutation: the kind-literal mutation did not apply"
else lit=""; for k in $KT_AUTH_KINDS; do grep -qF "\"$k\"" <<<"$(codeof "$TMP/a.kt")" && lit="$lit $k"; done
     [ -n "$lit" ] && ok "A-mutation: a kind typed in Kotlin is caught ($lit)" || bad "A-mutation: a kind typed in Kotlin was NOT caught"; fi

# ── A2 · Import File: the existing classifier, the sign-in's landing ───────
ICF="$APP/app/src/main/java/com/diegonmarcos/superapp/settings/ImportConfigsFragment.kt"
file_ok() {   # $1 = ProfileFragment.kt, $2 = ImportConfigsFragment.kt; prints the first broken rule
    local bw iv
    bw=$(fnof "$1" buildWay | codeof); iv=$(fnof "$1" importVaultFile | codeof)
    grep -q 'private const val KIND_VAULT_FILE = "vault_file"' "$1" || { echo "the file kind is not named once"; return 1; }
    grep -qF 'if (way.kind == KIND_VAULT_FILE)' <<<"$bw" || { echo "buildWay does not dispatch the file kind"; return 1; }
    grep -qF 'vaultFilePicker.launch(' <<<"$bw" || { echo "the file way does not open the picker"; return 1; }
    grep -q 'OpenDocument()) { uri ->' <<<"$(grep -A1 'private val vaultFilePicker' "$1")" || { echo "the picker is not the system document picker"; return 1; }
    [ -n "$iv" ] || { echo "importVaultFile is gone"; return 1; }
    grep -qF 'ImportConfigsFragment.classify(text)' <<<"$iv" || { echo "the file is not read through ImportConfigsFragment.classify"; return 1; }
    grep -qF 'VaultFile.Verdict.Bundle -> landVault(status, v.bundle, via = fileVia)' <<<"$iv" || { echo "the export does not land through landVault"; return 1; }
    grep -qE 'VaultConnect\.Imported|VaultFile\.classify\(' <<<"$iv" && { echo "importVaultFile writes the import or classifies itself — a second importer"; return 1; }
    grep -qF 'else -> refuse(com.diegonmarcos.superapp.settings.ImportConfigsFragment.refusal(ctx, v)' <<<"$iv" || { echo "a non-export file is not refused with its reason"; return 1; }
    grep -qF 'view?.snack(text)' <<<"$iv" || { echo "a refusal is not also a snack (loud)"; return 1; }
    grep -qF 'fun classify(text: String): VaultFile.Verdict =' "$2" || { echo "ImportConfigsFragment.classify is not shared"; return 1; }
    local rf; rf=$(awk '/fun refusal\(ctx: Context, v: VaultFile.Verdict\): String\? = when \(v\) \{/{f=1} f{print} f&&/^        }$/{exit}' "$2")
    for v in Empty NotJson Encrypted UnknownSchema Unrecognised Blob; do
        grep -q "Verdict\.$v -> ctx.getString(R.string\." <<<"$rf" || { echo "refusal() gives no sentence for $v"; return 1; }
    done
    grep -qF 'Verdict.Bundle -> null' <<<"$rf" || { echo "refusal() refuses the export itself"; return 1; }
    return 0
}
echo "== A2: Import File reads through the existing classifier and lands like a sign-in =="
msg=$(file_ok "$PF" "$ICF") && ok "A2: picker → ImportConfigsFragment.classify → landVault; every other verdict refused with its reason" || bad "A2: $msg"
for loc in values values-es; do
    grep -q 'name="connect_file_blob">✗' "$RES/$loc/strings.xml" && ok "A2: the blob refusal is worded in $loc" || bad "A2: connect_file_blob missing or not a refusal in $loc"
done
echo "-- A2-mutation: a second importer, a lost landing, a silent refusal, a missing verdict sentence --"
f2mut() {   # $1 = file (pf|icf), $2 = sed expression; 0 iff file_ok goes red; 2 iff nothing changed
    cp "$PF" "$TMP/pf.kt"; cp "$ICF" "$TMP/icf.kt"
    sed -i "$2" "$TMP/$1.kt"
    if [ "$1" = pf ]; then cmp -s "$PF" "$TMP/pf.kt" && return 2; else cmp -s "$ICF" "$TMP/icf.kt" && return 2; fi
    ! file_ok "$TMP/pf.kt" "$TMP/icf.kt" >/dev/null
}
for m in 'pf|s/is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle -> landVault(status, v.bundle, via = fileVia)/is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle -> { VaultConnect.Imported.bundle = v.bundle }/' \
         'pf|s/ImportConfigsFragment.classify(text)/com.diegonmarcos.cloudlib.auth.VaultFile.classify(text, emptySet(), emptySet())/' \
         'pf|s/fun refuse(text: String) { show(status, RED, text); view?.snack(text) }/fun refuse(text: String) { }/' \
         'pf|s/if (way.kind == KIND_VAULT_FILE) {/if (false) {/' \
         'icf|/is VaultFile.Verdict.Encrypted -> ctx.getString(R.string.import_encrypted/d'; do
    amt="${m%%|*}"; expr="${m#*|}"
    f2mut "$amt" "$expr"; rc=$?
    case $rc in 0) ok "A2-mutation: caught — $amt: ${expr:0:70}";; 2) bad "A2-mutation: did not apply — $amt: ${expr:0:70}";; *) bad "A2-mutation: NOT caught — $amt: ${expr:0:70}";; esac
done

# ── B · no GitHub OAuth app on the Account surface ─────────────────────────
OAUTH_RE='client_id|client_secret|clientSecret|clientId|oauth/landed|login/oauth|web_client|webClient|OAuthWeb'
oauth_free() {   # $1 = build.json, $2..$n = sources; prints the first hit
    local bj="$1"; shift
    local hit
    # _doc* keys are prose the app never reads (they may STATE this rule); every other key is scanned.
    hit=$(jq -c '.ui.profile | walk(if type == "object" then with_entries(select(.key | startswith("_doc") | not)) else . end)' "$bj" | grep -oE "$OAUTH_RE" | head -1)
    [ -n "$hit" ] && { echo "ui.profile declares $hit"; return 1; }
    hit=$(codeof "$@" | grep -oE "$OAUTH_RE" | head -1)
    [ -n "$hit" ] && { echo "the Account sources name $hit"; return 1; }
    return 0
}
echo "== B: the Account surface declares and names no GitHub OAuth app =="
msg=$(oauth_free "$BJ" "$PF" "$IM" "$CP" "$GS" "$GE") && ok "B: no client id, client secret, OAuth landing or web client on the Account surface" || bad "B: $msg"
jq -e '.ui.profile.connect.lines[].ways[] | select(.kind == "gh_auth_login") | .note | test("gh")' "$BJ" >/dev/null \
    && ok "B: GitHub WebAuth is declared as gh's own sign-in, not an app of the fleet's" || bad "B: the GitHub WebAuth way does not say it is gh's own sign-in"
echo "-- B-mutation: a client id declared, a landing declared, a web client named in Kotlin --"
jq '.ui.profile.connect.lines[1].ways[0].client_id = "Iv1.planted"' "$BJ" > "$TMP/b1.json"
oauth_free "$TMP/b1.json" "$PF" >/dev/null && bad "B-mutation: a declared client_id was NOT caught" || ok "B-mutation: a declared client_id is caught"
jq '.ui.profile.connect.redirect = "https://api.example.test/git/oauth/landed"' "$BJ" > "$TMP/b2.json"
oauth_free "$TMP/b2.json" "$PF" >/dev/null && bad "B-mutation: a declared OAuth landing was NOT caught" || ok "B-mutation: a declared OAuth landing is caught"
sed 's/private fun showGithubPatDialog(via: String) {/private fun showGithubPatDialog(via: String) { val c = OAuthWeb.parse(null)/' "$PF" > "$TMP/b3.kt"
cmp -s "$PF" "$TMP/b3.kt" && bad "B-mutation: the OAuthWeb mutation did not apply" \
    || { oauth_free "$BJ" "$TMP/b3.kt" >/dev/null && bad "B-mutation: OAuthWeb named in the fragment was NOT caught" || ok "B-mutation: OAuthWeb named in the fragment is caught"; }
sed 's/        const val LOGIN_POLL = "loginPoll"/        const val LOGIN_POLL = "loginPoll"\n        const val GH_CLIENT_ID = "Iv1.planted"; val clientId = GH_CLIENT_ID/' "$GE" > "$TMP/b4.kt"
cmp -s "$GE" "$TMP/b4.kt" && bad "B-mutation: the GhEngine client-id mutation did not apply" \
    || { oauth_free "$BJ" "$PF" "$TMP/b4.kt" >/dev/null && bad "B-mutation: a client id in GhEngine was NOT caught" || ok "B-mutation: a client id planted in GhEngine is caught"; }

# ── C · Profiles: the declared copy, masked ─────────────────────────────────
echo "== C: Profiles draws the declared copy through the declared mask; no secret reaches a composable =="
PROF=$(awk '/^fun ProfilesTab\(/{f=1} f{print} f&&/^}$/{exit}' "$AT" | codeof)
grep -qF 'for (section in InfoMask.sectionsFor(InfoMask.schema, emptyList(), shown)) {' <<<"$PROF" && grep -qF 'InfoMask.declared.schemaRows(section, shown?.opt(section.id))' <<<"$PROF" \
    && ok "C: every Profiles row is a row of the declared copy, through the mask" || bad "C: Profiles is not drawn from the declared copy through the mask"
grep -qF 'val shown = m.shown()' <<<"$PROF" && grep -qF 'fun shown(): JSONObject? = local ?: server()?.body' "$AM" \
    && ok "C: what Profiles shows is the local copy L, else the server file S" || bad "C: Profiles does not show L-else-S"
masked_row_blank() { grep -qF 'Row(path, "", Kind.MASKED, text.length)' "$1"; }
masked_row_blank "$IM" && ok "C: a masked row carries its length and an EMPTY text — the secret never leaves InfoMask" || bad "C: a masked row still carries its text"
draws_no_secret() { grep -qF 'row.text' <<<"$(grep -E 'InfoMask\.Kind\.MASKED ->' "$1")" && return 1; return 0; }
draws_no_secret "$AT" && ok "C: a masked row is drawn from its size alone" || bad "C: a composable draws a masked row's text"
shown_ok() {   # $1 = AccountTabs.kt; every free value goes through shownValue, which asks the mask
    local sv; sv=$(awk '/^fun shownValue\(/{f=1} f{print} f&&/^}$/{exit}' "$1")
    grep -qF 'InfoMask.declared.hides(path, text) -> ' <<<"$sv" || { echo "shownValue does not ask the mask"; return 1; }
    grep -qF 'shownValue(path, values[path]?.let(AccountDrift::text))' "$1" || { echo "Runtime draws a value past the mask"; return 1; }
    grep -qF 'shownValue(f.path, f.a)' "$1" && grep -qF 'shownValue(f.path, f.b)' "$1" || { echo "Drift draws a value past the mask"; return 1; }
    grep -qF 'InfoMask.Kind.SHOWN -> shownValue(full, row.text)' "$1" || { echo "Profiles draws a shown value past the mask"; return 1; }
    return 0
}
msg=$(shown_ok "$AT") && ok "C: Runtime, Drift and Profiles draw every value through shownValue → the mask" || bad "C: $msg"
grep -qF 'if (r.kind == InfoMask.Kind.SHOWN) put("text", r.text.take(120)) else put("size", r.size)' "$ADA" \
    && grep -qF 'InfoMask.declared.hides(path, value)) "✗ not an editable field' "$ADA" \
    && ok "C: the debug API prints only shown values and refuses to set a masked field" || bad "C: the debug API can print or set a masked value"
grep -qF 'pathRes.any { it == null } || valueRes.any { it == null }' "$IM" && grep -qF '(pathRes.isEmpty() && valueRes.isEmpty())' "$IM" \
    && ok "C: the rule fails closed (no pattern, or one that does not compile, masks every leaf)" || bad "C: the mask does not fail closed"
# The rule itself, ported from InfoMask.rows/hides, run over a bundle.
mask_leaks() {   # $1 = build.json, $2 = bundle.json, $3 = known-secret path regex; prints each leak; 1 iff any
    python3 - "$1" "$2" "$3" <<'PYC'
import json, re, sys
m = json.load(open(sys.argv[1]))["ui"]["profile"]["infos"]["mask"]
pr = [re.compile(p, re.I) for p in m["paths"]]; vr = [re.compile(p) for p in m["values"]]
co = int(m.get("collapse_over", 12)); SEP = " › "
secret = re.compile(sys.argv[3], re.I)
tokenish = re.compile(r"(?i:bearer)\s+\S{16,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|PrivateKey|-----BEGIN|[A-Za-z0-9+/=]{40,}|[A-Za-z0-9_-]{40,}")
def join(p, k): return k if not p else p + SEP + k
def text(v):
    if v is None: return "null"
    if isinstance(v, bool): return "true" if v else "false"
    if isinstance(v, (dict, list)): return json.dumps(v)
    return str(v)
leaks = []
def walk(sec, v, path):
    if isinstance(v, dict) and v.get("pending") is True: return
    if isinstance(v, (dict, list)) and len(v) > co: return
    if isinstance(v, dict) and v:
        for k, x in v.items(): walk(sec, x, join(path, k)); return
    if isinstance(v, list) and v:
        for i, x in enumerate(v): walk(sec, x, join(path, "[%d]" % i)); return
    full, t = join(sec, path), text(v)
    hidden = any(r.search(full) for r in pr) or any(r.search(t) for r in vr)
    if not hidden and (secret.search(full) or tokenish.search(t)): leaks.append(full)
b = json.load(open(sys.argv[2])); b = b.get("bundle", b)
for s in b:
    if s.startswith("_") or s == "schema_version": continue
    walk(s, b[s], "")
for l in leaks: print(l)
sys.exit(1 if leaks else 0)
PYC
}
SECRET_PATHS='^(git › (github_token|ssh_private_key)|mail › passwords › .*|ai › tokens › .*|mesh › profiles › .*|autocomplete › .* › text)$'
# A synthetic bundle, assembled here so the committed file holds no token-shaped literal.
python3 - "$TMP/fixture.json" <<'PYF'
import json, sys
tok = "gh" + "p_" + "a1B2c3D4e5" * 4
json.dump({"schema_version": 1,
  "git": {"github_token": tok, "ssh_private_key": "-----BEGIN " + "OPENSSH PRIVATE KEY-----\nAAAA", "repos": [{"repo": "front"}]},
  "mail": {"passwords": {"ME_PASSWORD": "correct-horse-battery-staple"}, "accounts": {"a": {"name": "me", "pass_env": "ME_PASSWORD"}}},
  "mesh": {"profiles": {"config-v4-full": "[Interface]\nPrivateKey = " + "K" * 43 + "=\nAddress = 10.9.9.9/32"}},
  "ai": {"tokens": {"x": "sk" + "-or-v1-" + "0123456789abcdef" * 3}},
  "autocomplete": {"default": [{"text": "clip %d" % i} for i in range(20)], "urls_pub": [{"text": "Authorization: Bearer " + "q" * 24}]},
  "about": {"profile": {"name": "Test Person"}}}, open(sys.argv[1], "w"))
PYF
out=$(mask_leaks "$BJ" "$TMP/fixture.json" "$SECRET_PATHS") && ok "C: the declared rule masks every secret of the synthetic bundle" || bad "C: the declared rule leaks: $(echo $out)"
VAULT_BUNDLE=""
for v in "${CLOUD_VAULT:+$CLOUD_VAULT/C_A1-configs/profile-secrets.json}" "$APP/../../cloud-vault/C_A1-configs/profile-secrets.json" "$APP/../../../cloud-vault/C_A1-configs/profile-secrets.json"; do
    [ -f "$v" ] && VAULT_BUNDLE="$v" && break
done
if [ -n "$VAULT_BUNDLE" ]; then
    out=$(mask_leaks "$BJ" "$VAULT_BUNDLE" "$SECRET_PATHS") \
        && ok "C: the declared rule masks every secret of the REAL vault export ($(du -h "$VAULT_BUNDLE" | cut -f1); paths only, no value printed)" \
        || bad "C: the declared rule leaks in the real vault export at: $(echo $out)"
else
    echo "  UNVERIFIABLE: cloud-vault is not checked out beside this repo — the real-export check did not run (the synthetic one above did)"
fi
echo "-- C-mutation: a path pattern dropped, a masked row given its text, a composable that draws it, shownValue unmasked --"
jq '.ui.profile.infos.mask.paths |= map(select(. != "pass"))' "$BJ" > "$TMP/c1.json"
cmp -s "$BJ" "$TMP/c1.json" && bad "C-mutation: the pattern drop did not apply" \
    || { mask_leaks "$TMP/c1.json" "$TMP/fixture.json" "$SECRET_PATHS" >/dev/null && bad "C-mutation: dropping 'pass' leaked nothing — the rule check cannot fail" || ok "C-mutation: dropping 'pass' leaks the mail password → RED"; }
# The value patterns overlap on purpose (a WireGuard key is both "PrivateKey" and a
# long base64 run), so the proof drops them ALL: the profile's path names no secret.
jq '.ui.profile.infos.mask.values = []' "$BJ" > "$TMP/c2.json"
mask_leaks "$TMP/c2.json" "$TMP/fixture.json" "$SECRET_PATHS" >/dev/null && bad "C-mutation: emptying the value patterns leaked nothing" || ok "C-mutation: no value pattern → the WireGuard profile leaks → RED"
sed 's/Row(path, "", Kind.MASKED, text.length)/Row(path, text, Kind.MASKED, text.length)/' "$IM" > "$TMP/c3.kt"
cmp -s "$IM" "$TMP/c3.kt" && bad "C-mutation: the masked-text mutation did not apply" \
    || { masked_row_blank "$TMP/c3.kt" && bad "C-mutation: a masked row carrying its text was NOT caught" || ok "C-mutation: a masked row carrying its text → RED"; }
sed 's/InfoMask.Kind.MASKED -> ctx.getString(R.string.infos_row_masked, row.size)/InfoMask.Kind.MASKED -> row.text + ctx.getString(R.string.infos_row_masked, row.size)/' "$AT" > "$TMP/c4.kt"
cmp -s "$AT" "$TMP/c4.kt" && bad "C-mutation: the composable mutation did not apply" \
    || { draws_no_secret "$TMP/c4.kt" && bad "C-mutation: a composable drawing a masked row's text was NOT caught" || ok "C-mutation: a composable drawing a masked row's text → RED"; }
for e in 's/    InfoMask.declared.hides(path, text) -> /    false -> /' \
         's/shownValue(f.path, f.b)/f.b.orEmpty()/' \
         's/shownValue(path, values\[path\]?.let(AccountDrift::text))/values[path]?.let(AccountDrift::text).orEmpty()/'; do
    sed "$e" "$AT" > "$TMP/c5.kt"
    if cmp -s "$AT" "$TMP/c5.kt"; then bad "C-mutation: did not apply — ${e:0:70}"
    else shown_ok "$TMP/c5.kt" >/dev/null && bad "C-mutation: NOT caught — ${e:0:70}" || ok "C-mutation: caught — ${e:0:70}"; fi
done

# ── T · the strip: exactly four declared tabs ──────────────────────────────
echo "== T: Account is exactly four declared tabs — Connect, Profiles, Runtime, Drift — mapped id → column =="
tabs_ok() {   # $1 = build.json, $2 = ProfileFragment.kt; prints the first broken rule
    local ids want cols
    ids=$(jq -r '[.ui.profile.tabs[].id] | join(",")' "$1"); want="connect,profiles,runtime,drift"
    [ "$ids" = "$want" ] || { echo "declared tabs are [$ids], not [$want]"; return 1; }
    jq -e '[.ui.profile.tabs[] | select((.label // "") | length == 0)] | length == 0' "$1" >/dev/null || { echo "a tab has no label"; return 1; }
    cols=$(grep -oE 'val columns = mapOf\([^)]*\)' "$2" | grep -oE '"[a-z]+" to' | tr -d '" ' | sed 's/to$//' | paste -sd, -)
    [ "$cols" = "$want" ] || { echo "the fragment maps [$cols], not [$want]"; return 1; }
    grep -qF 'val tabs = AccountModel.tabs().mapNotNull { t -> columns[t.id]?.let { Tab(t.label, it) } }' "$2" || { echo "the strip is not the declaration through the column map"; return 1; }
    local lab=""; while IFS= read -r l; do [ -n "$l" ] && grep -qF "\"$l\"" <<<"$(codeof "$2" "$AT" "$AM")" && lab="$lab [$l]"; done <<<"$(jq -r '.ui.profile.tabs[].label' "$1")"
    [ -z "$lab" ] || { echo "tab label(s) typed in Kotlin:$lab"; return 1; }
    return 0
}
msg=$(tabs_ok "$BJ" "$PF") && ok "T: four declared tabs in order, each label off the declaration, each id one column" || bad "T: $msg"
grep -q 'UI_PROFILE_TABS_B64' "$APP/app/build.gradle" && grep -qF 'BuildConfig.UI_PROFILE_TABS_B64' "$AM" \
    && ok "T: the strip is baked and read off the declaration (AccountModel.tabs)" || bad "T: UI_PROFILE_TABS_B64 is not baked or not read"
for t in profiles runtime drift; do
    grep -qF "AccountTags.tab(\"$t\")" "$AT" && ok "T: the $t tab carries its test tag (account:$t)" || bad "T: the $t tab has no test tag"
done
echo "-- T-mutation: a fifth tab, a tab dropped, two tabs swapped, a label typed in Kotlin, a column unmapped --"
for jm in '.ui.profile.tabs += [{"id":"setup","label":"Cloud Constellation Setup"}]' \
          '.ui.profile.tabs |= map(select(.id != "drift"))' \
          '.ui.profile.tabs |= [.[0], .[2], .[1], .[3]]' \
          '.ui.profile.tabs[1].label = ""'; do
    jq "$jm" "$BJ" > "$TMP/t.json"
    if cmp -s "$BJ" "$TMP/t.json"; then bad "T-mutation: did not apply — $jm"
    else tabs_ok "$TMP/t.json" "$PF" >/dev/null && bad "T-mutation: NOT caught — $jm" || ok "T-mutation: caught — $jm"; fi
done
for e in 's/"runtime" to runtime, //' 's/val tabs = AccountModel.tabs().mapNotNull { t -> columns\[t.id\]?.let { Tab(t.label, it) } }/val tabs = listOf(Tab("Profiles", profiles))/'; do
    sed "$e" "$PF" > "$TMP/t.kt"
    if cmp -s "$PF" "$TMP/t.kt"; then bad "T-mutation: did not apply — ${e:0:70}"
    else tabs_ok "$BJ" "$TMP/t.kt" >/dev/null && bad "T-mutation: NOT caught — ${e:0:70}" || ok "T-mutation: caught — ${e:0:70}"; fi
done

# ── D · Drift: three files, one pattern, the sync directions ───────────────
echo "== D: Drift keeps S, R and L in one pattern, compares the declared pairs, and every sync direction is one model function =="
drift_decl_ok() {   # $1 = build.json; prints the first broken rule
    python3 - "$1" <<'PYD'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["profile"].get("drift") or {}
def die(m): print(m); sys.exit(1)
pairs = d.get("pairs") or []
if [p.get("id") for p in pairs] != ["SR", "LS", "LR"]: die("pairs are %s, not SR, LS, LR" % [p.get("id") for p in pairs])
for p in pairs:
    if {p.get("a"), p.get("b")} - {"S", "R", "L"} or p.get("a") == p.get("b"): die("pair %s compares %s with %s" % (p.get("id"), p.get("a"), p.get("b")))
    if not str(p.get("label", "")).strip(): die("pair %s has no label" % p.get("id"))
u = d.get("upload") or {}
path = str(u.get("path", ""))
if not path: die("no upload path")
if path.endswith("profile-secrets.json"): die("the upload targets the GENERATED server file (emit.py guards it against hand edits)")
if not all(x in str(u.get("api", "")) for x in ("{repo}", "{path}")): die("the upload api is not a {repo}/{path} template")
if "{device}" not in str(u.get("message", "")): die("the commit message does not name the device")
if not isinstance(d.get("runtime_deadline_ms"), int) or d["runtime_deadline_ms"] <= 0: die("no runtime deadline")
PYD
}
msg=$(drift_decl_ok "$BJ") && ok "D: the pairs (S↔R, L↔S, L↔R), the upload target beside the generated file, and the runtime deadline are declared" || bad "D: $msg"
LOCALPY=""
for v in "${CLOUD_VAULT:+$CLOUD_VAULT/C_A1-configs/local.py}" "$APP/../../cloud-vault/C_A1-configs/local.py" "$APP/../../../cloud-vault/C_A1-configs/local.py"; do [ -f "$v" ] && LOCALPY="$v" && break; done
if [ -n "$LOCALPY" ]; then
    vp=$(grep -oE '^LOCAL = CONFIGS / "[^"]+"' "$LOCALPY" | cut -d'"' -f2)
    [ -n "$vp" ] && [ "$(jq -r '.ui.profile.drift.upload.path' "$BJ")" = "C_A1-configs/$vp" ] \
        && ok "D: the upload path is the one cloud-vault's local.py checks (C_A1-configs/$vp)" || bad "D: the upload path is not the file cloud-vault's local.py checks ($vp)"
else
    echo "  UNVERIFIABLE: cloud-vault is not beside this repo — the upload path was not compared with local.py's"
fi
grep -q 'UI_PROFILE_DRIFT_B64' "$APP/app/build.gradle" && grep -qF 'BuildConfig.UI_PROFILE_DRIFT_B64' "$AM" \
    && ok "D: the drift declaration is baked and read" || bad "D: UI_PROFILE_DRIFT_B64 is not baked or not read"
engine_ok() {   # $1 = AccountDrift.kt, $2 = AccountModel.kt, $3 = AccountUpload.kt; prints the first broken rule
    grep -qF 'if (p.a == Slot.R || p.b == Slot.R) AccountRuntime.observed(runtime()?.apps) else null' "$2" || { echo "a comparison with R is not limited to what R observed"; return 1; }
    local rd; rd=$(awk '/    fun runtimeToDeclared\(/{f=1} f{print} f&&/^    }$/{exit}' "$1")
    grep -qF 'p !in observed -> skipped[p] = Skip.NOT_OBSERVED' <<<"$rd" || { echo "an unobserved field can be pulled into L"; return 1; }
    grep -qF 'p in readOnly -> skipped[p] = Skip.READ_ONLY' <<<"$rd" || { echo "a read-only field can be pulled into L"; return 1; }
    grep -qF 'v == null -> skipped[p] = Skip.HOLDS_NONE' <<<"$rd" || { echo "a runtime holding nothing can erase a declared value"; return 1; }
    grep -qF 'fun sha256(body: JSONObject): String' "$1" && grep -qF 'canonical(body)' <<<"$(grep -A2 'fun sha256' "$1")" || { echo "the file hash is not over canonical JSON"; return 1; }
    grep -qF '"Authorization" to "Bearer $token"' "$3" || { echo "the upload token does not ride a header"; return 1; }
    grep -qE '(url|api|path).*\$\{?token' "$3" && { echo "the upload token reaches a URL"; return 1; }
    grep -qF 'if (sha != null) put.put("sha", sha)' "$3" || { echo "an update does not name the sha it replaces"; return 1; }
    return 0
}
msg=$(engine_ok "$AD" "$AM" "$AU") && ok "D: R comparisons cover what R observed; runtime→declared never takes unobserved, read-only or empty values; the upload names its sha and keeps the token in a header" || bad "D: $msg"
actions_ok() {   # $1 = AccountTabs.kt; each Drift action calls its ONE model function
    grep -qF 'ActionButton(stringResource(R.string.account_push_all), AccountTags.PUSH_ALL, !busy) { io { m.pushServerToRuntime(' "$1" || { echo "push-all is not the model's server → runtime"; return 1; }
    grep -qF 'ActionButton(stringResource(R.string.account_pull_all), AccountTags.PULL_ALL, !busy) { io { m.pullRuntimeToLocal(' "$1" || { echo "pull-all is not the model's runtime → declared"; return 1; }
    grep -qF 'ActionButton(stringResource(R.string.account_upload), AccountTags.UPLOAD, !busy) { upload() }' "$1" || { echo "upload is not wired"; return 1; }
    grep -qF 'ActionButton(stringResource(R.string.account_discard), AccountTags.DISCARD, !busy) { m.discardLocal() }' "$1" || { echo "discard is not the model's"; return 1; }
    grep -qF 'AccountTags.pushApp(app)' "$1" && grep -qF 'AccountTags.pullApp(app)' "$1" || { echo "no per-app sync"; return 1; }
    grep -qF 'AccountTags.pushItem(f.path)' "$1" && grep -qF 'AccountTags.pullItem(f.path)' "$1" || { echo "no per-item sync"; return 1; }
    grep -qF 'AccountTags.exportFile(slot)' "$1" && grep -qF 'AccountTags.EXPORT_REPORT' "$1" || { echo "the files or the report cannot be exported"; return 1; }
    grep -qF 'AccountTags.POPULATE_RUNTIME) { m.populateFromRuntime() }' "$1" && grep -qF 'AccountTags.POPULATE_SERVER) { m.populateFromServer() }' "$1" \
        && grep -qF 'AccountTags.SAVE, enabled = m.local != null) { m.save() }' "$1" || { echo "a Profiles action is not the model's"; return 1; }
    return 0
}
msg=$(actions_ok "$AT") && ok "D: push / pull per item, per app and all; upload; discard; export of S, R, L and the report; populate ×2 and save — each one model call" || bad "D: $msg"
api_ok() {   # $1 = AccountDebugApi.kt, $2 = App.kt
    local op; for op in tabs profiles runtime drift refresh populate edit save sync upload; do
        grep -qF "Op(\"$op\"" "$1" || { echo "/api/account/$op is not advertised"; return 1; }
        grep -qE "^ *(\"\", )?\"$op\" ->" "$1" || { echo "/api/account/$op is not served"; return 1; }
    done
    grep -qF 'AccountDebugApi.register(this)' "$2" || { echo "the account routes are never registered"; return 1; }
    grep -qF 'm.pushServerToRuntime(paths)' "$1" && grep -qF 'm.pullRuntimeToLocal(paths)' "$1" && grep -qF 'm.discardLocal()' "$1" || { echo "sync does not call the model"; return 1; }
    return 0
}
msg=$(api_ok "$ADA" "$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt") && ok "D: /api/account/{tabs,profiles,runtime,drift} and the action ops are advertised, served and registered" || bad "D: $msg"
echo "-- D-mutation: the R scope dropped, an empty runtime value pulled, the token in the URL, an update without its sha, an action unwired, a route dropped, the generated file as target --"
dm() {   # $1 = which file var, $2 = sed; 0 iff its check goes red; 2 iff nothing changed
    local f; eval f=\$$1; sed "$2" "$f" > "$TMP/d.kt"; cmp -s "$f" "$TMP/d.kt" && return 2
    case $1 in AD) ! engine_ok "$TMP/d.kt" "$AM" "$AU" >/dev/null;; AM) ! engine_ok "$AD" "$TMP/d.kt" "$AU" >/dev/null;; AU) ! engine_ok "$AD" "$AM" "$TMP/d.kt" >/dev/null;;
               AT) ! actions_ok "$TMP/d.kt" >/dev/null;; ADA) ! api_ok "$TMP/d.kt" "$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt" >/dev/null;; esac
}
for m in 'AM|s/if (p.a == Slot.R || p.b == Slot.R) AccountRuntime.observed(runtime()?.apps) else null/null/' \
         'AD|s/                v == null -> skipped\[p\] = Skip.HOLDS_NONE/                v == null -> Unit/' \
         'AD|s/                p in readOnly -> skipped\[p\] = Skip.READ_ONLY/                false -> Unit/' \
         'AU|s/target.url + "?ref=" + target.branch/target.url + "?ref=" + target.branch + "\&access_token=$token"/' \
         'AU|s/if (sha != null) put.put("sha", sha)/Unit/' \
         'AT|s/AccountTags.DISCARD, !busy) { m.discardLocal() }/AccountTags.DISCARD, !busy) { }/' \
         'AT|s/AccountTags.pullItem(f.path)/AccountTags.RESULT/' \
         'ADA|s/            "drift" -> m.report()/            "drift_old" -> m.report()/'; do
    v="${m%%|*}"; e="${m#*|}"
    dm "$v" "$e"; rc=$?
    case $rc in 0) ok "D-mutation: caught — $v: ${e:0:70}";; 2) bad "D-mutation: did not apply — $v: ${e:0:70}";; *) bad "D-mutation: NOT caught — $v: ${e:0:70}";; esac
done
for jm in '.ui.profile.drift.upload.path = "C_A1-configs/profile-secrets.json"' '.ui.profile.drift.pairs |= .[1:]' '.ui.profile.drift.pairs[0].b = "S"'; do
    jq "$jm" "$BJ" > "$TMP/d.json"
    if cmp -s "$BJ" "$TMP/d.json"; then bad "D-mutation: did not apply — $jm"
    else drift_decl_ok "$TMP/d.json" >/dev/null && bad "D-mutation: NOT caught — $jm" || ok "D-mutation: caught — $jm"; fi
done

# ── E · the GitHub token is used once ───────────────────────────────────────
echo "== E: the pasted GitHub token is a one-request credential =="
pat_clean() {   # $1 = ProfileFragment.kt; 0 iff the PAT path stores, logs and URL-embeds nothing
    local d f
    d=$(fnof "$1" showGithubPatDialog | codeof); f=$(fnof "$1" fetchVaultFileWithToken | codeof)
    [ -n "$d" ] && [ -n "$f" ] || return 1
    grep -qE 'Prefs\(|putSecret|putString|edit\(\)|Log\.|writeText|setAutheliaCredential' <<<"$d$f" && return 1
    grep -qF 'tokenField?.setText("")' <<<"$d" || return 1
    grep -qF '"Authorization" to "Bearer $token"' <<<"$f" || return 1
    grep -qF 'secret = token' <<<"$f" || return 1
    grep -qE 'url *=.*\$token|token=|\?access_token' <<<"$f" && return 1
    return 0
}
pat_clean "$PF" && ok "E: the token rides one header, is the redacted secret, is emptied from the box, and reaches no store or log" || bad "E: the PAT path keeps, logs or embeds the token"
grep -qF 'api.github.com' <<<"$(codeof "$PF")" && bad "E: the GitHub API host is a Kotlin literal" || ok "E: the contents URL is the declared template, no host in Kotlin"
grep -qF 'GitSshVault.fetchArtifact(cacheDir, key, pass, path)' "$PF" && grep -qF 'val path = vaultFile()' "$PF" \
    && ok "E: the SSH way reads the declared vault export (the old artifact path is absent from the repo)" || bad "E: the SSH way does not read the declared vault file"
echo "-- E-mutation: the token stored, the token logged --"
sed 's/                    tokenField?.setText("")/                    ConfigsPrefs(ctx).putSecret("git", "github_token", token); tokenField?.setText("")/' "$PF" > "$TMP/e1.kt"
cmp -s "$PF" "$TMP/e1.kt" && bad "E-mutation: the store mutation did not apply" \
    || { pat_clean "$TMP/e1.kt" && bad "E-mutation: a stored token was NOT caught" || ok "E-mutation: the token stored → RED"; }
sed 's/        val cs = AuthDeclaration.configSource\n        val url = connectDecl/&/; /private fun fetchVaultFileWithToken/{n; s/^\(        val cs = AuthDeclaration.configSource\)$/\1; android.util.Log.i("x", token)/}' "$PF" > "$TMP/e2.kt"
cmp -s "$PF" "$TMP/e2.kt" && bad "E-mutation: the log mutation did not apply" \
    || { pat_clean "$TMP/e2.kt" && bad "E-mutation: a logged token was NOT caught" || ok "E-mutation: the token logged → RED"; }

# ── G · Connect: the journey and nothing after; one pill design; GitHub WebAuth wired ──
echo "== G: Connect is the journey and nothing after it, every way is the same pill, GitHub WebAuth is gh's own sign-in =="
connect_tail_ok() {   # $1 = ProfileFragment.kt; prints the first broken rule
    local blk
    blk=$(awk '/── CONNECT: sign in, fetch/{f=1} f{print} f&&/── PROFILES · RUNTIME · DRIFT/{exit}' "$1" | codeof | grep -E '^ *render[A-Za-z]*\(')
    [ "$(echo $blk)" = "renderJourney(ctx, connect)" ] || { echo "Connect renders [$(echo $blk)], not exactly the journey"; return 1; }
    # No other call anywhere hands the Connect column to a renderer.
    [ "$(codeof "$1" | grep -cE '\(ctx, connect\)')" = 1 ] || { echo "something else renders into the Connect column"; return 1; }
    return 0
}
msg=$(connect_tail_ok "$PF") && ok "G: Connect renders the journey and nothing after it" || bad "G: $msg"
for gone in 'private fun renderTokens(' 'private fun renderDevicePick(' 'TOKENS_TEXT' 'private fun renderVault(' 'vaultCodeBox' 'journey_vault_header' 'journey_vault_fetch_open'; do
    grep -qF "$gone" "$PF" && bad "G: the stale Connect tail survives ($gone)" || ok "G: the stale Connect tail is deleted ($gone)"
done
grep -qF 'private fun buildDeviceStep(' "$PF" && grep -qF 'VaultCockpit.selectDevice(ctx, p.vaultDevice)' "$PF" \
    && ok "G: the device pick lives on Connect (journey step 3) — #778 deleted the Setup hero's second one" || bad "G: the device pick has no home"
pills_ok() {   # $1 = ProfileFragment.kt; every page-kind way and every lib way draws FleetCockpitView.pill
    local bw; bw=$(fnof "$1" buildWay | codeof)
    for k in KIND_GITHUB_SSH_PAT KIND_GH_AUTH_LOGIN KIND_VAULT_FILE; do
        grep -qF 'cell.addView(wayPill(ctx, way)' <<<"$(awk -v k="$k" '$0 ~ "if \\(way.kind == "k"\\) \\{" {f=1} f{print} f&&/return$/{exit}' <<<"$bw")" \
            || { echo "$k does not draw the shared pill"; return 1; }
    done
    grep -qF 'pickButton(' <<<"$bw" && { echo "buildWay still draws a plain button"; return 1; }
    grep -qF 'FleetCockpitView.pill(ctx, way.label, onClick)' <<<"$(fnof "$1" wayPill)" || { echo "wayPill is not the cockpit pill"; return 1; }
    grep -qF 'FleetCockpitView.pill(c, way.label, onClick)' <<<"$(fnof "$1" signInWay)" || { echo "the Authelia ways are not the cockpit pill"; return 1; }
    return 0
}
msg=$(pills_ok "$PF") && ok "G: Authelia → Gitea, GitHub and Import File all draw the one cockpit pill" || bad "G: $msg"
gh_ok() {   # $1 = ProfileFragment.kt, $2 = GhEngine.kt, $3 = build.json; prints the first broken rule
    local bw gs gp; bw=$(fnof "$1" buildWay | codeof); gs=$(fnof "$1" ghSignIn | codeof); gp=$(fnof "$1" ghPrompt | codeof)
    jq -e '.ui.profile.connect.lines[] | select(.id == "github") | .ways[] | select(.kind == "gh_auth_login" and .label == "WebAuth" and (.rung | length > 0))' "$3" >/dev/null \
        || { echo "the GitHub line declares no WebAuth way naming its git-chain rung"; return 1; }
    jq -e '.engines.gh | .fleet == "lib-gh" and .min_contract >= 1' "$3" >/dev/null || { echo "engines.gh is not declared against the lib-gh row"; return 1; }
    grep -qF 'ghSignIn(way, status)' <<<"$(awk '$0 ~ /if \(way.kind == KIND_GH_AUTH_LOGIN\) \{/ {f=1} f{print} f&&/return$/{exit}' <<<"$bw")" \
        || { echo "the WebAuth pill does not start ghSignIn"; return 1; }
    grep -qF 'GhEngine(ctx)' <<<"$gs" && grep -qF 'engine.login(host)' <<<"$gs" && grep -qF 'engine.token(host)' <<<"$gs" \
        || { echo "ghSignIn does not run gh's own login through the engine"; return 1; }
    grep -qF 'is GhEngine.Check.NotInstalled -> getString(R.string.connect_gh_missing' <<<"$gs" && grep -qF 'is GhEngine.Check.TooOld -> getString(R.string.connect_gh_old' <<<"$gs" \
        || { echo "a missing or old engine is not its own sentence"; return 1; }
    grep -qF 'fetchVaultFileWithToken(token, hint)' <<<"$gs" && grep -qF 'landVault(status, o.body, via = way.via)' <<<"$gs" \
        || { echo "the gh token does not read the vault export once and land like a sign-in"; return 1; }
    grep -qE 'putSecret|setAutheliaCredential|Prefs\(|Log\.' <<<"$gs$gp" && { echo "the gh path stores or logs something"; return 1; }
    grep -qF 'AuthDeclaration.browserMission?.pkg' <<<"$gp" && grep -qF '.setPackage(browser))' <<<"$gp" \
        || { echo "the sign-in page does not open in cloud-browser"; return 1; }
    grep -qF 'BuildConfig.GH_ENGINE_ACTION' "$2" || { echo "GhEngine does not find the engine by its declared action"; return 1; }
    return 0
}
msg=$(gh_ok "$PF" "$GE" "$BJ") && ok "G: GitHub WebAuth = gh auth login in the gh engine, page in cloud-browser, token read once and landed" || bad "G: $msg"
for loc in values values-es; do
    for s in connect_gh_caption connect_gh_missing connect_gh_old connect_gh_failed connect_gh_no_token connect_gh_prompt connect_gh_no_browser; do
        grep -q "name=\"$s\"" "$RES/$loc/strings.xml" || bad "G: $s missing from $loc"
    done
done
echo "-- G-mutation: anything after the journey, the old vault block back, a plain button on a line, WebAuth unwired, the page in any browser, the token stored --"
gmut() {   # $1 = which check, $2 = sed expr on PF; 0 iff the check goes red; 2 iff nothing changed
    sed "$2" "$PF" > "$TMP/g.kt"; cmp -s "$PF" "$TMP/g.kt" && return 2
    case $1 in tail) ! connect_tail_ok "$TMP/g.kt" >/dev/null;; pills) ! pills_ok "$TMP/g.kt" >/dev/null;; gh) ! gh_ok "$TMP/g.kt" "$GE" "$BJ" >/dev/null;; esac
}
for m in 'tail|s/^        renderJourney(ctx, connect)$/        renderJourney(ctx, connect)\n        renderRepos(ctx, connect)/' \
         'tail|s/^        renderJourney(ctx, connect)$/        renderJourney(ctx, connect)\n        renderVault(ctx, connect)/' \
         'tail|s/^        renderJourney(ctx, connect)$/        renderRepos(ctx, connect)\n        renderJourney(ctx, connect)/' \
         'pills|s/            cell.addView(wayPill(ctx, way) { ghSignIn(way, status) })/            cell.addView(pickButton(ctx, way.label) { ghSignIn(way, status) })/' \
         'pills|s/^            cell.addView(wayPill(ctx, way) {$/            cell.addView(pickButton(ctx, way.label) {/' \
         'gh|s/            cell.addView(wayPill(ctx, way) { ghSignIn(way, status) })/            cell.addView(caption(ctx, way.note))/' \
         'gh|s/.setPackage(browser))/)/' \
         'gh|s/            if (token == null) { show(status, RED, "✗ $failure"); return@launch }/            if (token == null) { show(status, RED, "✗ $failure"); return@launch }; ConfigsPrefs(ctx).putSecret("git", "github_token", token)/' \
         'gh|s/                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> landVault(status, o.body, via = way.via)/                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> Unit/'; do
    w="${m%%|*}"; e="${m#*|}"
    gmut "$w" "$e"; rc=$?
    case $rc in 0) ok "G-mutation: caught — $w: ${e:0:80}";; 2) bad "G-mutation: did not apply — $w: ${e:0:80}";; *) bad "G-mutation: NOT caught — $w: ${e:0:80}";; esac
done
jq '(.ui.profile.connect.lines[1].ways) |= map(select(.kind != "gh_auth_login"))' "$BJ" > "$TMP/g1.json"
cmp -s "$BJ" "$TMP/g1.json" && bad "G-mutation: dropping GitHub WebAuth did not apply" \
    || { gh_ok "$PF" "$GE" "$TMP/g1.json" >/dev/null && bad "G-mutation: GitHub WebAuth dropped was NOT caught" || ok "G-mutation: GitHub WebAuth dropped from the GitHub line → RED"; }
lines_ok "$TMP/g1.json" "$SHARED" >/dev/null && bad "G-mutation: A did not see the WebAuth way go" || ok "G-mutation: A also goes red when WebAuth is dropped"

# ── V · every line fetches the vault configs and answers which device (#766) ──
echo "== V: the Authelia line continues to the vault, every landing adopts the vault's registry, step 4 has no second Import File =="
vault_leg_ok() {   # $1 = ProfileFragment.kt; prints the first broken rule
    local ss lh al vd vf lv gs ha
    ss=$(fnof "$1" buildSignInStep | codeof); lh=$(fnof "$1" landed | codeof); al=$(fnof "$1" afterLanding | codeof)
    vd=$(fnof "$1" showVaultFetchDialog | codeof); vf=$(fnof "$1" vaultFetch | codeof); lv=$(fnof "$1" landVault | codeof)
    gs=$(fnof "$1" buildGetStep | codeof); ha=$(awk '/private val signInHost = object/{f=1} f{print} f&&/^    }$/{exit}' "$1" | codeof)
    grep -qF 'line.ways.any { it.kind in AUTHELIA_KINDS }' <<<"$ss" && grep -qF 'showVaultFetchDialog(line.label)' <<<"$ss" \
        || { echo "the Authelia line has no vault pill"; return 1; }
    grep -qF 'in AUTHELIA_KINDS' <<<"$lh" && grep -qF 'vaultLegVia = ' <<<"$lh" || { echo "an Authelia landing does not arm the vault leg"; return 1; }
    grep -qF 'showVaultFetchDialog(it)' <<<"$al" || { echo "afterLanding does not open the vault leg"; return 1; }
    grep -qF 'afterLanding()' <<<"$ha" || { echo "the shared sign-in's landing does not continue"; return 1; }
    grep -qF 'runFetch(status, { afterLanding() },' <<<"$(fnof "$1" buildStoredBearer | codeof)" || { echo "the stored bearer's one tap does not continue"; return 1; }
    grep -qE '^ *vaultStart\(status\)$' <<<"$vd" || { echo "opening the vault leg does not mail the code"; return 1; }
    grep -qF 'vaultFetch(status, it, via)' <<<"$vd" || { echo "the vault leg does not fetch"; return 1; }
    grep -qF 'landVault(status, o.body, redrawNow = false, via = via)' <<<"$vf" || { echo "the vault fetch does not land like every line"; return 1; }
    grep -qF 'UserRegistry::fromVault' <<<"$lv" && grep -qF 'UserRegistry.adopt(c, it)' <<<"$lv" || { echo "a landing does not adopt the vault's registry"; return 1; }
    grep -qF 'VaultConnect.Imported.via = via' <<<"$lv" || { echo "a landing does not record its way"; return 1; }
    grep -qE 'import_configs|journey_import_file' <<<"$gs" && { echo "step 4 still carries a second Import File"; return 1; }
    local n; n=$(codeof "$1" | grep -c 'landVault(status, ' ); [ "$n" -ge 4 ] || { echo "only $n landings"; return 1; }
    [ "$(codeof "$1" | grep 'landVault(status, ' | grep -vc 'via = ')" = 0 ] || { echo "a landing does not say which way fetched it"; return 1; }
    return 0
}
msg=$(vault_leg_ok "$PF") && ok "V: the Authelia line's vault leg opens after an Authelia landing and from its pill; every landing names its way and adopts the vault's registry" || bad "V: $msg"
AUTHSRC="$APP/../ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth"
fr=$(awk '/    fun fromVault\(/{f=1} f{print} f&&/^    }$/{exit}' "$AUTHSRC/UserRegistry.kt" | codeof)
grep -qF 'optString("peer")' <<<"$fr" && grep -qF '"vault_device", device' <<<"$fr" && grep -qF 'optJSONObject("electronics")' <<<"$fr" \
    && ok "V: the vault's registry is electronics.*.<device>.peer, keyed by the vault device" || bad "V: fromVault does not read electronics' peers"
pj=$(codeof "$AUTHSRC/ProfileJourney.kt")
grep -qF '|| vaultFetched' <<<"$pj" && grep -qF '!s.artifactInMemory && !s.vaultFetched -> Lock.REFETCH' <<<"$pj" \
    && ok "V: holding the vault export is a sign-in, and step 4 is not locked behind a re-fetch" || bad "V: the journey ignores a vault landing"
for loc in values values-es; do
    for k in connect_vault_pill connect_vault_title connect_vault_go connect_vault_resend connect_vault_caption journey_signed_in_vault; do
        grep -q "name=\"$k\"" "$RES/$loc/strings.xml" || bad "V: $k missing from $loc"
    done
done
echo "-- V-mutation: the pill unwired, the auto-continue dropped, no code mailed, no registry adopted, the second Import File back, a landing without its way --"
for e in 's/showVaultFetchDialog(line.label)/Unit/' \
         's/        vaultLegVia?.let { vaultLegVia = null; if (isAdded) showVaultFetchDialog(it) }/        vaultLegVia = null/' \
         's/            view?.post { afterLanding() }/            view?.post { redraw() }/' \
         's/^                vaultStart(status)$/                Unit/' \
         's/UserRegistry.adopt(c, it)/Unit/' \
         's/        VaultConnect.Imported.via = via/        Unit/' \
         's|        // #766 no second Import File here: it is Connect.s third line.|        body.addView(pickButton(ctx, "x") { (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)?.onTileClicked("action:import_configs") })|' \
         's/landVault(status, v.bundle, via = fileVia)/landVault(status, v.bundle)/' \
         's/                    if (landVault(status, o.body, redrawNow = false, via = via)) onLanded()/                    Unit/'; do
    sed "$e" "$PF" > "$TMP/v.kt"
    if cmp -s "$PF" "$TMP/v.kt"; then bad "V-mutation: did not apply — ${e:0:80}"
    elif vault_leg_ok "$TMP/v.kt" >/dev/null; then bad "V-mutation: NOT caught — ${e:0:80}"
    else ok "V-mutation: caught — ${e:0:80}"; fi
done

# ── F · Profiles: every field of the declared schema ───────────────────────────
echo "== F: Profiles renders the WHOLE schema — every declared section and field, filled or empty =="
schema_rows_cover() {   # $1 = build.json, $2 = bundle.json ("" = none); prints each declared field with no row; 1 iff any
    python3 - "$1" "$2" <<'PYF'
import json, sys
s = json.load(open(sys.argv[1]))["ui"]["profile"]["infos"]["schema"]["sections"]
b = {}
if sys.argv[2]:
    b = json.load(open(sys.argv[2])); b = b.get("bundle", b)
SEP = " › "
def unfilled(v): return v is None or (isinstance(v, str) and not v.strip()) or (isinstance(v, (dict, list)) and not v)
def rows(sec, data):   # port of InfoMask.schemaRows: one row per field (or its leaves), then the extras
    out = []
    for f in sec["fields"]:
        v = data
        for seg in f.split(SEP): v = v.get(seg) if isinstance(v, dict) else None
        out.append(f if unfilled(v) else f)   # a filled field walks to >= 1 row under its own path
    tops = {f.split(SEP)[0] for f in sec["fields"]}
    out += [k for k in (data or {}) if k not in tops]
    return out
miss = []
if not s: miss.append("(no section declared)")
for sec in s:
    if not sec.get("fields"): miss.append(sec["id"] + " (no field declared)")
    r = rows(sec, b.get(sec["id"]))
    miss += ["%s › %s" % (sec["id"], f) for f in sec["fields"] if f not in r]
for m in miss: print(m)
sys.exit(1 if miss else 0)
PYF
}
schema_code_ok() {   # $1 = InfoMask.kt, $2 = AccountTabs.kt
    local sr pt; sr=$(awk '/fun schemaRows\(/{f=1} f{print} f&&/^    }$/{exit}' "$1" | codeof); pt=$(awk '/^fun ProfilesTab\(/{f=1} f{print} f&&/^}$/{exit}' "$2" | codeof)
    grep -qF 'for (field in section.fields) {' <<<"$sr" || { echo "schemaRows does not walk every declared field"; return 1; }
    grep -qF 'if (unfilled(v)) out += Row(field, "", Kind.EMPTY) else walk(section.id, v, field, out)' <<<"$sr" || { echo "an unfilled field is not an EMPTY row"; return 1; }
    grep -qF 'if (k !in tops) walk(section.id, o.opt(k), k, out)' <<<"$sr" || { echo "keys beyond the schema are dropped"; return 1; }
    grep -qF 'for (section in InfoMask.sectionsFor(InfoMask.schema, emptyList(), shown)) {' <<<"$pt" && grep -qF 'for (row in rows) {' <<<"$pt" \
        || { echo "ProfilesTab does not iterate the declared schema and every row"; return 1; }
    grep -qE '^ *return$|return@Column' <<<"$pt" && { echo "ProfilesTab returns early, before the schema"; return 1; }
    grep -qF 'InfoMask.Kind.EMPTY -> ctx.getString(R.string.infos_row_empty)' "$2" || { echo "an EMPTY row is not drawn as empty"; return 1; }
    return 0
}
msg=$(schema_code_ok "$IM" "$AT") && ok "F: ProfilesTab iterates the declared schema; every field is a row, filled or empty; extras still drawn" || bad "F: $msg"
grep -q 'UI_PROFILE_INFOS_B64' "$APP/app/build.gradle" && grep -qF 'parseSchema(baked?.optJSONObject("schema"))' "$IM" \
    && ok "F: the schema is baked with the mask and read off it" || bad "F: the schema is not baked or not read"
out=$(schema_rows_cover "$BJ" "") && ok "F: with nothing fetched, every declared field still draws (as empty) — $(jq '[.ui.profile.infos.schema.sections[].fields[]] | length' "$BJ") fields in $(jq '.ui.profile.infos.schema.sections | length' "$BJ") sections" || bad "F: fields without a row: $(echo $out)"
out=$(schema_rows_cover "$BJ" "$TMP/fixture.json") && ok "F: over the synthetic bundle every declared field draws" || bad "F: fields without a row: $(echo $out)"
VAULT_DIR=""
for v in "${CLOUD_VAULT:+$CLOUD_VAULT/C_A1-configs}" "$APP/../../cloud-vault/C_A1-configs" "$APP/../../../cloud-vault/C_A1-configs"; do [ -f "$v/schema.json" ] && VAULT_DIR="$v" && break; done
schema_drift() {   # $1 = build.json, $2 = C_A1-configs dir; prints the drift; 1 iff any
    python3 - "$1" "$2" <<'PYD'
import json, os, sys
mine = json.load(open(sys.argv[1]))["ui"]["profile"]["infos"]["schema"]
d = sys.argv[2]; vs = json.load(open(os.path.join(d, "schema.json")))
# emit.py's own rule (resolvers()): a resolver is any object carrying `kind` — no list of
# kinds here, so a resolver kind the vault adds (peer_devices, 11950b4) is a field, not a crash.
# #727 EVERY section directory, not only schema.json's list: schema.json's sections in
# its order, then each C_A1-configs/<dir>/sources.json it does not list yet (STAGED).
listed = [s["id"] for s in vs["sections"]]
staged = sorted(x for x in os.listdir(d) if os.path.isfile(os.path.join(d, x, "sources.json")) and x not in listed)
want = []
for s in vs["sections"] + [{"id": x, "label": x.capitalize(), "staged": True} for x in staged]:
    items = json.load(open(os.path.join(d, s["id"], "sources.json")))["items"]; fields = []
    def walk(o, p):
        if "kind" in o: fields.append(p); return
        for k, v in o.items():
            if not k.startswith("_"): walk(v, (p + " › " if p else "") + k)
    walk(items, ""); want.append({"id": s["id"], "label": s.get("label", s["id"]), "fields": fields, "staged": bool(s.get("staged"))})
bad = []
if mine.get("schema_version") != vs.get("schema_version"): bad.append("schema_version %s != vault %s" % (mine.get("schema_version"), vs.get("schema_version")))
if [x["id"] for x in mine["sections"]] != [x["id"] for x in want]: bad.append("sections %s != vault %s" % ([x["id"] for x in mine["sections"]], [x["id"] for x in want]))
for a, b in zip(mine["sections"], want):
    a = {"id": a["id"], "label": a.get("label"), "fields": a.get("fields"), "staged": bool(a.get("staged"))}
    if a != b: bad.append("%s: declared %s != vault %s" % (a["id"], a, b))
for x in bad: print(x)
sys.exit(1 if bad else 0)
PYD
}
if [ -n "$VAULT_DIR" ]; then
    out=$(schema_drift "$BJ" "$VAULT_DIR") && ok "F: the declared skeleton IS cloud-vault's schema.json + sources.json (no drift)" || bad "F: the skeleton drifted from the vault: $out"
    out=$(schema_rows_cover "$BJ" "$VAULT_DIR/profile-secrets.json") && ok "F: over the REAL vault export every declared field draws (paths only, no value printed)" || bad "F: fields without a row in the real export: $(echo $out)"
else
    echo "  UNVERIFIABLE: cloud-vault is not checked out beside this repo (set CLOUD_VAULT to point at one) — the skeleton's drift check did not run (the shape checks above did; InfoMaskTest pins the nine section ids)"
fi
echo "-- F-mutation: a field dropped from the skeleton, a section dropped, fields skipped, an early return, extras dropped --"
jq '.ui.profile.infos.schema.sections[2].fields |= .[1:]' "$BJ" > "$TMP/f1.json"
if cmp -s "$BJ" "$TMP/f1.json"; then bad "F-mutation: the field drop did not apply"
elif [ -n "$VAULT_DIR" ]; then schema_drift "$TMP/f1.json" "$VAULT_DIR" >/dev/null && bad "F-mutation: a dropped field was NOT caught by the drift check" || ok "F-mutation: a field dropped from the skeleton → drift RED"
else echo "  UNVERIFIABLE: F-mutation field-drop needs cloud-vault beside"; fi
jq '.ui.profile.infos.schema.sections[0].fields = []' "$BJ" > "$TMP/f2.json"
schema_rows_cover "$TMP/f2.json" "" >/dev/null && bad "F-mutation: a section with no fields was NOT caught" || ok "F-mutation: a section emptied of fields → RED"
fmut() { sed "$2" "$1" > "$TMP/f.kt"; cmp -s "$1" "$TMP/f.kt" && return 2
         if [ "$1" = "$IM" ]; then ! schema_code_ok "$TMP/f.kt" "$AT" >/dev/null; else ! schema_code_ok "$IM" "$TMP/f.kt" >/dev/null; fi; }
for m in "$IM|s/        for (field in section.fields) {/        for (field in section.fields.take(1)) {/" \
         "$IM|s/            if (unfilled(v)) out += Row(field, \"\", Kind.EMPTY) else walk(section.id, v, field, out)/            if (!unfilled(v)) walk(section.id, v, field, out)/" \
         "$IM|s/if (k !in tops) walk(section.id, o.opt(k), k, out)/if (false) walk(section.id, o.opt(k), k, out)/" \
         "$AT|s/        for (section in InfoMask.sectionsFor(InfoMask.schema, emptyList(), shown)) {/        for (section in InfoMask.schema) {/" \
         "$AT|s/        ResultLine(m)\$/        ResultLine(m)\n        if (shown == null) return@Column/"; do
    f="${m%%|*}"; e="${m#*|}"
    fmut "$f" "$e"; rc=$?
    case $rc in 0) ok "F-mutation: caught — $(basename "$f"): ${e:0:70}";; 2) bad "F-mutation: did not apply — $(basename "$f"): ${e:0:70}";; *) bad "F-mutation: NOT caught — $(basename "$f"): ${e:0:70}";; esac
done

# ── F2 · (#727) no vault section dropped; the declared apps listed ─────────
echo "== F2: every vault section renders in Profiles (the staged ones too), and the apps section lists each declared app =="
union_ok() {   # $1 = InfoMask.kt; prints the first broken rule
    local sf; sf=$(awk '/fun sectionsFor\(/{f=1} f{print} f&&/^        }$/{exit}' "$1" | codeof)
    grep -qF 'schema.forEach { out[it.id] = it }' <<<"$sf" || { echo "sectionsFor drops the declared skeleton"; return 1; }
    grep -qF 'fetched.forEach { (id, label) -> out.getOrPut(id) { SchemaSection(id, label, emptyList()) } }' <<<"$sf" || { echo "sectionsFor drops a section the fetch's schema names"; return 1; }
    grep -qF 'bundle?.keys()?.forEach { k ->' <<<"$sf" && grep -qF 'out.getOrPut(k) { SchemaSection(k, k, emptyList()) }' <<<"$sf" \
        || { echo "sectionsFor drops a top-level key the bundle carries"; return 1; }
    grep -qF 'return out.values.toList()' <<<"$sf" || { echo "sectionsFor does not return what it gathered"; return 1; }
    return 0
}
apps_ok() {   # $1 = AccountTabs.kt, $2 = build.json; prints the first broken rule
    local pt al route; pt=$(awk '/^fun ProfilesTab\(/{f=1} f{print} f&&/^}$/{exit}' "$1" | codeof); al=$(awk '/^private fun DeclaredApps\(/{f=1} f{print} f&&/^}$/{exit}' "$1" | codeof)
    grep -qF 'if (section.render == "apps") DeclaredApps(shown, section.route, openStore)' <<<"$pt" || { echo "Profiles does not draw the declared app list"; return 1; }
    grep -qF 'VaultCockpit.appsListed(bundle, id, fleet)' <<<"$al" || { echo "the list is not the vault's declared apps"; return 1; }
    grep -qF 'ctx.packageManager.getPackageInfo(a.pkg, 0)' <<<"$al" || { echo "installed-on-this-phone is not measured"; return 1; }
    grep -qF 'a.label, a.pkg, VaultCockpit.storeLabel(sources, a)' <<<"$al" || { echo "a row lacks its name, package or store"; return 1; }
    grep -qF 'openStore(route)' <<<"$al" || { echo "the list does not link into the Store"; return 1; }
    route=$(jq -r '[.ui.profile.infos.schema.sections[] | select(.render == "apps") | .route] | first // ""' "$2")
    [ -n "$route" ] || { echo "no schema section declares the apps list"; return 1; }
    jq -e --arg p "${route#page:config/}" '[.. | objects | select(.id? == $p)] | length > 0' "$2" >/dev/null || { echo "route $route names no declared page"; return 1; }
    return 0
}
msg=$(union_ok "$IM") && ok "F2: Profiles draws the skeleton ∪ the fetch's schema ∪ every top-level bundle key — no section is dropped" || bad "F2: $msg"
msg=$(apps_ok "$AT" "$BJ") && ok "F2: the apps section lists every declared app (name · package · store · installed here) and links to $(jq -r '[.ui.profile.infos.schema.sections[] | select(.render == "apps") | .route] | first' "$BJ")" || bad "F2: $msg"
for need in apps peers; do
    jq -e --arg i "$need" '.ui.profile.infos.schema.sections[] | select(.id == $i and .staged == true and (.fields | length > 0))' "$BJ" >/dev/null \
        && ok "F2: the staged vault section '$need' is in the skeleton" || bad "F2: the staged vault section '$need' is missing from the skeleton"
done
echo "-- F2-mutation: a source of sections dropped, the app list unwired, a staged section dropped --"
for e in 's/            bundle?.keys()?.forEach { k ->/            emptyList<String>().forEach { k ->/' \
         's/            fetched.forEach { (id, label) -> out.getOrPut(id) { SchemaSection(id, label, emptyList()) } }/            Unit/' \
         's/            schema.forEach { out\[it.id\] = it }/            Unit/'; do
    sed "$e" "$IM" > "$TMP/u.kt"
    if cmp -s "$IM" "$TMP/u.kt"; then bad "F2-mutation: did not apply — ${e:0:70}"
    else union_ok "$TMP/u.kt" >/dev/null && bad "F2-mutation: NOT caught — ${e:0:70}" || ok "F2-mutation: caught — ${e:0:70}"; fi
done
for e in 's/            if (section.render == "apps") DeclaredApps(shown, section.route, openStore)/            Unit/' \
         's/a.label, a.pkg, VaultCockpit.storeLabel(sources, a)/a.pkg, a.pkg, a.pkg/' \
         's/val here = apps.map { a -> runCatching { ctx.packageManager.getPackageInfo(a.pkg, 0) }.isSuccess }/val here = apps.map { true }/'; do
    sed "$e" "$AT" > "$TMP/a.kt"
    if cmp -s "$AT" "$TMP/a.kt"; then bad "F2-mutation: did not apply — ${e:0:70}"
    else apps_ok "$TMP/a.kt" "$BJ" >/dev/null && bad "F2-mutation: NOT caught — ${e:0:70}" || ok "F2-mutation: caught — ${e:0:70}"; fi
done
for jm in '(.ui.profile.infos.schema.sections[] | select(.id == "apps")) |= del(.render)' \
          '(.ui.profile.infos.schema.sections[] | select(.id == "apps")) .route = "page:config/no-such-page"'; do
    jq "$jm" "$BJ" > "$TMP/a.json"
    if cmp -s "$BJ" "$TMP/a.json"; then bad "F2-mutation: did not apply — $jm"
    else apps_ok "$AT" "$TMP/a.json" >/dev/null && bad "F2-mutation: NOT caught — $jm" || ok "F2-mutation: caught — $jm"; fi
done
# #766 the regression this file missed: the vault's electronics became one peer_devices
# resolver (cloud-vault 11950b4) while the skeleton kept the hand-listed devices; the drift
# check then CRASHED on the new kind instead of going red.
jq '(.ui.profile.infos.schema.sections[] | select(.id == "electronics")).fields = ["computers › surface › type", "phones › galaxy › type", "watches", "other_devices", "kde_connect"]' "$BJ" > "$TMP/e.json"
if cmp -s "$BJ" "$TMP/e.json"; then bad "F2-mutation: the stale electronics skeleton did not apply"
elif [ -n "$VAULT_DIR" ]; then
    out=$(schema_drift "$TMP/e.json" "$VAULT_DIR" 2>&1); rc=$?
    { [ $rc = 1 ] && grep -q '^electronics: declared' <<<"$out"; } && ok "F2-mutation: the pre-11950b4 electronics skeleton → drift RED, naming electronics (not a crash)" \
        || bad "F2-mutation: the stale electronics skeleton was NOT reported as drift (rc=$rc)"
fi
jq '.ui.profile.infos.schema.sections |= map(select(.id != "apps"))' "$BJ" > "$TMP/s.json"
if cmp -s "$BJ" "$TMP/s.json"; then bad "F2-mutation: the staged-section drop did not apply"
elif [ -n "$VAULT_DIR" ]; then schema_drift "$TMP/s.json" "$VAULT_DIR" >/dev/null && bad "F2-mutation: the apps section dropped (the #713 miss) was NOT caught by the drift check" || ok "F2-mutation: the apps section dropped from the skeleton (the #713 miss) → drift RED"
else echo "  UNVERIFIABLE: F2-mutation staged-section drop needs cloud-vault beside (InfoMaskTest pins the ids in CI)"; fi

# ── H · Runtime: per app, read live; Setup's index, wizard and cockpit gone ──
echo "== H: Runtime reads each declared app live, with its status; Cloud Constellation Setup's index, wizard and cockpit are gone =="
runtime_ok() {   # $1 = AccountRuntime.kt, $2 = build.json, $3 = AccountTabs.kt; prints the first broken rule
    local decl disp rt
    # An app declared `reports: false` exposes nothing to read, so it has no reader — only a status.
    decl=$(jq -r '.ui.vault_connect.cockpit.sections[] | select(.runtime.reports != false) | .apply' "$2" | sort | paste -sd' ' -)
    disp=$(awk '/    private fun readOne\(/{f=1} f&&/return when \(section.apply\) \{/{g=1;next} g&&/^            else ->/{exit} g' "$1" | grep -oE '^            "[a-z]+" ->' | grep -oE '[a-z]+' | sort | paste -sd' ' -)
    [ "$decl" = "$disp" ] || { echo "declared apps [$decl] != read [$disp]"; return 1; }
    local missing; missing=$(jq -r '[.ui.vault_connect.cockpit.sections[] | select((.runtime.served_by // "") == "") | .id] | join(",")' "$2")
    [ -z "$missing" ] || { echo "app(s) $missing declare no runtime served_by"; return 1; }
    rt=$(awk '/    private fun readOne\(/{f=1} f{print} f&&/^    }$/{exit}' "$1")
    grep -qF 'return base.copy(status = Status.NOT_INSTALLED, detail = rt.servedBy)' <<<"$rt" || { echo "an absent serving package is not 'not installed'"; return 1; }
    grep -qF 'if (!rt.reports) return base.copy(status = Status.NOT_REPORTING, detail = rt.servedBy)' <<<"$rt" || { echo "an app that exposes nothing is not 'not reporting'"; return 1; }
    grep -qF 'while (!client.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)' <<<"$rt" || { echo "the binder read does not wait (under a deadline) for a stopped app to wake"; return 1; }
    grep -qF 'readOnly = if (rt.writable) emptySet() else values.keys' <<<"$rt" || { echo "a non-writable app's fields can be pulled back"; return 1; }
    grep -qF 'for (section in VaultCockpit.layout.sections) {' <<<"$(awk '/^fun RuntimeTab\(/{f=1} f{print} f&&/^}$/{exit}' "$3")" \
        && grep -qF 'AccountTags.runtimeApp(section.id)' "$3" && grep -qF 'AccountTags.runtimeStatus(section.id)' "$3" || { echo "Runtime is not one tagged card per declared app"; return 1; }
    for s in reachable not_installed not_reporting; do grep -qF "R.string.account_status_$s" "$3" || { echo "no '$s' status"; return 1; }; done
    return 0
}
msg=$(runtime_ok "$AR" "$BJ" "$AT") && ok "H: every declared app ($(jq -r '[.ui.vault_connect.cockpit.sections[].id] | join(", ")' "$BJ")) has a live reader and a status card" || bad "H: $msg"
setup_gone() {   # $1 = ProfileFragment.kt, $2 = build.json; prints what survives
    local g; for g in 'private fun renderFleetIndex(' 'private fun renderImported(' 'private fun renderWizard(' 'private fun renderRepos(' 'private fun renderSetup(' 'private fun renderInfos(' 'setup-index:' 'renderDeviceSelector('; do
        grep -qF "$g" "$1" && { echo "$g survives"; return 1; }
    done
    [ -f "$PKG/Wizard.kt" ] && { echo "Wizard.kt survives"; return 1; }
    jq -e '.ui.profile.wizard' "$2" >/dev/null && { echo "ui.profile.wizard survives"; return 1; }
    local rr; rr=$(fnof "$1" renderRuntime | codeof)
    grep -qF 'RuntimeTab(AccountModel.get(ctx))' <<<"$rr" && grep -qF 'renderConfigApply(ctx, into)' <<<"$rr" || { echo "the Runtime column is not the per-app runtime + your config"; return 1; }
    return 0
}
msg=$(setup_gone "$PF" "$BJ") && ok "H: the Setup index, wizard, cockpit cards and repos are deleted; Runtime is per app, then the per-peer config" || bad "H: $msg"
grep -qF 'AccountModel.get(c).landServer(it, via)' <<<"$(fnof "$PF" landVault | codeof)" \
    && ok "H: every Connect landing stores the fetched file as S, with the way that fetched it" || bad "H: a Connect landing does not store S"
echo "-- H-mutation: a reader dropped, a served_by dropped, the wake-wait dropped, the old index back, the landing not stored --"
for e in 's/^            "drive" -> {/            "drive_x" -> {/' \
         's/                while (!client.isConnected() \&\& SystemClock.elapsedRealtime() < until) Thread.sleep(100)/                Unit/' \
         's/                    readOnly = if (rt.writable) emptySet() else values.keys)/                    readOnly = emptySet())/'; do
    sed "$e" "$AR" > "$TMP/h.kt"
    if cmp -s "$AR" "$TMP/h.kt"; then bad "H-mutation: did not apply — ${e:0:70}"
    else runtime_ok "$TMP/h.kt" "$BJ" "$AT" >/dev/null && bad "H-mutation: NOT caught — ${e:0:70}" || ok "H-mutation: caught — ${e:0:70}"; fi
done
jq '(.ui.vault_connect.cockpit.sections[] | select(.id == "ai")) |= del(.runtime)' "$BJ" > "$TMP/h.json"
cmp -s "$BJ" "$TMP/h.json" && bad "H-mutation: the served_by drop did not apply" \
    || { runtime_ok "$AR" "$TMP/h.json" "$AT" >/dev/null && bad "H-mutation: an app with no served_by was NOT caught" || ok "H-mutation: an app with no runtime served_by → RED"; }
jq '.ui.profile.wizard = {"steps": []}' "$BJ" > "$TMP/h2.json"
setup_gone "$PF" "$TMP/h2.json" >/dev/null && bad "H-mutation: the wizard declared again was NOT caught" || ok "H-mutation: the wizard declared again → RED"
sed 's/^    private fun renderRuntime(/    private fun renderFleetIndex(ctx: android.content.Context, into: LinearLayout) = Unit\n    private fun renderRuntime(/' "$PF" > "$TMP/h3.kt"
cmp -s "$PF" "$TMP/h3.kt" && bad "H-mutation: the index-back mutation did not apply" \
    || { setup_gone "$TMP/h3.kt" "$BJ" >/dev/null && bad "H-mutation: the Setup index back was NOT caught" || ok "H-mutation: the Setup index back → RED"; }
sed 's/AccountModel.get(c).landServer(it, via)/Unit/' "$PF" > "$TMP/h4.kt"
cmp -s "$PF" "$TMP/h4.kt" && bad "H-mutation: the landing mutation did not apply" \
    || { grep -qF 'AccountModel.get(c).landServer(it, via)' <<<"$(fnof "$TMP/h4.kt" landVault | codeof)" && bad "H-mutation: a landing that stores nothing was NOT caught" || ok "H-mutation: a landing that stores nothing → RED"; }

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
