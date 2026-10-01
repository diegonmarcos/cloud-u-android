#!/usr/bin/env bash
# Tester (#695): Configs ▸ Account is exactly THREE tabs — Connect, Infos,
# Cloud Constellation Setup — and each keeps its promise.
#
# The tab shape itself (ids, order, labels, their mutations) is T11 of
# test-profile-credentials-never-sync.sh; the journey is test-profile-journey.sh;
# InfoMaskTest / SetupItemsTest run the rules on the JVM. This file pins what
# those cannot:
#   A  CONNECT is three declared LINES, in this order: Authelia → Gitea (WebAuth |
#      Bearer), GitHub (WebAuth | SSH / PAT), Import File (pick the decrypted vault
#      export, #711); every way is dispatched on its kind alone — libs:auth's kinds
#      generically, the page's own kinds by name — and a kind with no handler
#      carries the declared reason it cannot start, never a button.
#   A2 IMPORT FILE is no second importer: the picked bytes go through
#      ImportConfigsFragment.classify (the Configs ▸ Import classifier) and the
#      export lands through landVault (every sign-in's landing); every other
#      verdict is refused in red with a reason, in both locales.
#   B  NO GitHub OAuth app on this surface: no client id, client secret, OAuth
#      landing or OAuthWeb/web_client in the Account's declaration or sources
#      (the fleet uses GitHub CLI's own sign-in; it registers no app of its own).
#   C  INFOS is the fetched configs through the declared mask: a masked row
#      carries only a length, the rule fails closed, and the rule — run by a
#      port of InfoMask — masks every secret of a synthetic bundle (always) and
#      of the real vault export (when cloud-vault sits beside this repo).
#   D  SETUP says applied / not applied / why for every item: one verdict
#      function, a mandatory reason, the unreadable keyboard "not verifiable",
#      every declared mail account an item.
#   E  the GitHub token (PAT) is used once: never stored, logged or in a URL.
#   G  (#713) CONNECT ENDS AT THE VAULT EXPORT — nothing renders into the Connect
#      column after renderVault — every way of every line is the SAME pill, and
#      GitHub WebAuth is present and wired: gh's own sign-in in the gh engine
#      (GhEngine, engines.gh), the page in cloud-browser, the token used once.
#   F  (#713) INFOS IS THE WHOLE SCHEMA: the declared skeleton (ui.profile.infos.schema)
#      matches cloud-vault's schema.json + sources.json when the vault sits beside;
#      a port of InfoMask.schemaRows gives EVERY declared field a row (filled or
#      empty) over a synthetic bundle (always) and the real export (when beside).
#   F2 (#727) NO VAULT SECTION IS DROPPED: the skeleton is EVERY C_A1-configs/<dir>/
#      sources.json (schema.json's sections, then the STAGED ones — apps, peers — that
#      #713 missed by reading schema.json alone); Infos draws skeleton ∪ the fetch's
#      schema ∪ every top-level bundle key; the apps section lists each declared app
#      (name, package, store, installed here) and links into Store ▸ Phone Apps.
#   H  (#713) SETUP IS BY APP: the Fleet Setup index renders FIRST, one row per
#      mapped app, repainted from that app's own section; every cockpit section
#      declares its applier and the mapping includes keyboard, mail, drive and mesh.
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
RES="$APP/app/src/main/res"
for f in "$BJ" "$SHARED" "$PF" "$IM" "$CP" "$GS" "$GE" "$RES/values/strings.xml"; do
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
    grep -qF 'VaultFile.Verdict.Bundle -> landVault(status, v.bundle)' <<<"$iv" || { echo "the export does not land through landVault"; return 1; }
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
for m in 'pf|s/is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle -> landVault(status, v.bundle)/is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle -> { VaultConnect.Imported.bundle = v.bundle }/' \
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
sed 's/private fun showGithubPatDialog() {/private fun showGithubPatDialog() { val c = OAuthWeb.parse(null)/' "$PF" > "$TMP/b3.kt"
cmp -s "$PF" "$TMP/b3.kt" && bad "B-mutation: the OAuthWeb mutation did not apply" \
    || { oauth_free "$BJ" "$TMP/b3.kt" >/dev/null && bad "B-mutation: OAuthWeb named in the fragment was NOT caught" || ok "B-mutation: OAuthWeb named in the fragment is caught"; }
sed 's/        const val LOGIN_POLL = "loginPoll"/        const val LOGIN_POLL = "loginPoll"\n        const val GH_CLIENT_ID = "Iv1.planted"; val clientId = GH_CLIENT_ID/' "$GE" > "$TMP/b4.kt"
cmp -s "$GE" "$TMP/b4.kt" && bad "B-mutation: the GhEngine client-id mutation did not apply" \
    || { oauth_free "$BJ" "$PF" "$TMP/b4.kt" >/dev/null && bad "B-mutation: a client id in GhEngine was NOT caught" || ok "B-mutation: a client id planted in GhEngine is caught"; }

# ── C · Infos: the fetched configs, masked ──────────────────────────────────
echo "== C: Infos draws the fetched configs through the declared mask; a secret never reaches a view =="
INF=$(fnof "$PF" renderInfos | codeof)
grep -qF 'val sections = VaultConnect.Imported.last' <<<"$INF" && grep -qF 'mask.schemaRows(section, bundle?.opt(section.id))' <<<"$INF" \
    && ok "C: every Infos row is a row of the fetched bundle, through the mask" || bad "C: Infos is not drawn from the fetched bundle through the mask"
RAW=$(fnof "$PF" renderRaw | codeof)
grep -qF 'InfoMask.declared.rows(' <<<"$RAW" && ok "C: the cockpit's raw remainder goes through the SAME mask" || bad "C: the raw remainder bypasses the mask"
masked_row_blank() { grep -qF 'Row(path, "", Kind.MASKED, text.length)' "$1"; }
masked_row_blank "$IM" && ok "C: a masked row carries its length and an EMPTY text — the secret never leaves InfoMask" || bad "C: a masked row still carries its text"
draws_no_secret() { grep -qF 'row.text' <<<"$(grep -E 'Kind\.MASKED ->' <<<"$(fnof "$1" infoValue)")" && return 1; return 0; }
draws_no_secret "$PF" && ok "C: the view draws a masked row from its size alone" || bad "C: the view draws a masked row's text"
grep -qF 'pathRes.any { it == null } || valueRes.any { it == null }' "$IM" && grep -qF '(pathRes.isEmpty() && valueRes.isEmpty())' "$IM" \
    && ok "C: the rule fails closed (no pattern, or one that does not compile, masks every leaf)" || bad "C: the mask does not fail closed"
grep -qF 'importedValue(' <<<"$(codeof "$PF")" && bad "C: the unmasked importedValue view survives" || ok "C: no unmasked value view is left on the page"
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
echo "-- C-mutation: a path pattern dropped, a masked row given its text, a view that draws it --"
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
sed 's/InfoMask.Kind.MASKED -> { text = getString(R.string.infos_row_masked, row.size)/InfoMask.Kind.MASKED -> { text = row.text + getString(R.string.infos_row_masked, row.size)/' "$PF" > "$TMP/c4.kt"
cmp -s "$PF" "$TMP/c4.kt" && bad "C-mutation: the view mutation did not apply" \
    || { draws_no_secret "$TMP/c4.kt" && bad "C-mutation: a view drawing a masked row's text was NOT caught" || ok "C-mutation: a view drawing a masked row's text → RED"; }

# ── D · Setup: applied / not applied / why, per item ───────────────────────
echo "== D: every Setup item says applied, or not applied and why =="
verdict_ok() {   # $1 = ProfileFragment.kt; 0 iff ONE function turns a state into words, with a reason for every not-applied
    local f; f=$(fnof "$1" itemVerdict | codeof)
    [ -n "$f" ] || return 1
    grep -qF 'state == VaultCockpit.State.MATCH -> getString(R.string.setup_item_applied)' <<<"$f" || return 1
    grep -qF 'state == VaultCockpit.State.DIFFERS -> getString(R.string.setup_item_not_applied, getString(R.string.setup_why_differs))' <<<"$f" || return 1
    grep -qF 'state == VaultCockpit.State.ABSENT -> getString(R.string.setup_item_not_applied, getString(R.string.setup_why_absent))' <<<"$f" || return 1
    grep -qF 'else -> getString(R.string.setup_item_not_applied, getString(R.string.setup_why_pending))' <<<"$f" || return 1
    grep -qF '!observed -> getString(R.string.setup_item_unverifiable)' <<<"$f" || return 1
    [ "$(grep -c 'setup_item_applied' <<<"$(codeof "$1")")" = 1 ] || return 1   # "applied" is said in exactly one place
    return 0
}
verdict_ok "$PF" && ok "D: one verdict function; MATCH alone reads applied; every other state reads not applied WITH its reason" || bad "D: the item verdicts are not the one mapping"
grep -qF 'itemVerdict(row.state, observed && row.observed)' <<<"$(fnof "$PF" renderRows)" && ok "D: every item row is worded by that function" || bad "D: renderRows does not word its items through itemVerdict"
for loc in "$RES"/values*/strings.xml; do
    grep -qE 'name="setup_item_not_applied">[^<]*%1\$s' "$loc" && ok "D: ${loc#$APP/}: 'not applied' carries its reason (%1\$s)" || bad "D: ${loc#$APP/}: 'not applied' has no slot for the reason"
    for s in setup_item_applied setup_item_unverifiable setup_why_differs setup_why_absent setup_why_pending setup_mail_cloud_mail; do
        grep -q "name=\"$s\"" "$loc" || bad "D: $s missing from ${loc#$APP/}"
    done
done
grep -qF 'renderRows(ctx, card.body, it, section.observed)' "$PF" && jq -e '.ui.vault_connect.cockpit.sections[] | select(.id == "keyboard") | .observed == false' "$BJ" >/dev/null \
    && ok "D: the keyboard (declared unobservable) reads 'not verifiable', not a guessed tick" || bad "D: the keyboard's items are not worded as unverifiable"
MAIL=$(fnof "$PF" renderMail | codeof)
grep -qF 'VaultCockpit.mailAccounts(bundle,' <<<"$MAIL" && grep -qF 'VaultCockpit.mailAccountRows(accounts, jmap.email)' <<<"$MAIL" && grep -qF 'R.string.setup_mail_cloud_mail' <<<"$MAIL" \
    && ok "D: every declared mail account is an item, and what cloud-mail cannot take is said" || bad "D: the mail item does not list every declared account"
DECL=$(jq -r '.ui.vault_connect.cockpit.sections[].apply' "$BJ" | sort)
DISP=$(awk '/when \(section.apply\) \{/{f=1;next} f&&/^ *\}/{f=0} f' "$PF" | grep -oE '^ *"[a-z]+"' | tr -d ' "' | sort)
[ "$DECL" = "$DISP" ] && ok "D: every declared Setup section has its renderer ($(echo $DECL))" || bad "D: declared [$(echo $DECL)] != dispatched [$(echo $DISP)]"
jq -e '.ui.vault_connect.cockpit.sections[] | select(.id == "about") | .fields | length > 0' "$BJ" >/dev/null \
    && ok "D: the contact card's fields are declared, not typed" || bad "D: the about section declares no fields"
echo "-- D-mutation: a DIFFERS read as applied, a reason slot dropped, the keyboard ticked --"
sed 's/state == VaultCockpit.State.DIFFERS -> getString(R.string.setup_item_not_applied, getString(R.string.setup_why_differs)) to RED/state == VaultCockpit.State.DIFFERS -> getString(R.string.setup_item_applied) to GREEN/' "$PF" > "$TMP/d1.kt"
cmp -s "$PF" "$TMP/d1.kt" && bad "D-mutation: the DIFFERS mutation did not apply" \
    || { verdict_ok "$TMP/d1.kt" && bad "D-mutation: DIFFERS read as applied was NOT caught" || ok "D-mutation: DIFFERS read as applied → RED"; }
sed 's/name="setup_item_not_applied">✗ not applied — %1\$s/name="setup_item_not_applied">✗ not applied/' "$RES/values/strings.xml" > "$TMP/d2.xml"
cmp -s "$RES/values/strings.xml" "$TMP/d2.xml" && bad "D-mutation: the reason-slot mutation did not apply" \
    || { grep -qE 'name="setup_item_not_applied">[^<]*%1\$s' "$TMP/d2.xml" && bad "D-mutation: a reasonless 'not applied' was NOT caught" || ok "D-mutation: a reasonless 'not applied' → RED"; }
sed 's/renderRows(ctx, card.body, it, section.observed)/renderRows(ctx, card.body, it)/' "$PF" > "$TMP/d3.kt"
cmp -s "$PF" "$TMP/d3.kt" && bad "D-mutation: the keyboard mutation did not apply" \
    || { grep -qF 'renderRows(ctx, card.body, it, section.observed)' "$TMP/d3.kt" && bad "D-mutation: a keyboard worded like a readable section was NOT caught" || ok "D-mutation: the keyboard's rows worded as readable → RED"; }

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

# ── G · Connect: nothing after the vault export; one pill design; GitHub WebAuth wired ──
echo "== G: Connect ends at the vault export, every way is the same pill, GitHub WebAuth is gh's own sign-in =="
connect_tail_ok() {   # $1 = ProfileFragment.kt; prints the first broken rule
    local blk
    blk=$(awk '/── CONNECT: sign in, fetch/{f=1} f{print} f&&/── INFOS:/{exit}' "$1" | codeof | grep -E '^ *render[A-Za-z]*\(')
    [ "$(echo $blk)" = "renderJourney(ctx, connect) renderVault(ctx, connect)" ] || { echo "Connect renders [$(echo $blk)], not exactly journey then vault"; return 1; }
    # No other call anywhere hands the Connect column to a renderer.
    [ "$(codeof "$1" | grep -cE '\(ctx, connect\)')" = 2 ] || { echo "something else renders into the Connect column"; return 1; }
    return 0
}
msg=$(connect_tail_ok "$PF") && ok "G: Connect renders the journey, then the vault export, and nothing after it" || bad "G: $msg"
for gone in 'private fun renderTokens(' 'private fun renderDevicePick(' 'TOKENS_TEXT'; do
    grep -qF "$gone" "$PF" && bad "G: the stale Connect tail survives ($gone)" || ok "G: the stale Connect tail is deleted ($gone)"
done
grep -qF 'renderDeviceSelector(ctx, hero.slot, devices)' "$PF" && ok "G: the device pick lives on the Setup hero, where it applies" || bad "G: the device pick has no home after leaving Connect"
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
    grep -qF 'fetchVaultFileWithToken(token, hint)' <<<"$gs" && grep -qF 'landVault(status, o.body)' <<<"$gs" \
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
echo "-- G-mutation: a read-out after the vault export, a plain button on a line, WebAuth unwired, the page in any browser, the token stored --"
gmut() {   # $1 = which check, $2 = sed expr on PF; 0 iff the check goes red; 2 iff nothing changed
    sed "$2" "$PF" > "$TMP/g.kt"; cmp -s "$PF" "$TMP/g.kt" && return 2
    case $1 in tail) ! connect_tail_ok "$TMP/g.kt" >/dev/null;; pills) ! pills_ok "$TMP/g.kt" >/dev/null;; gh) ! gh_ok "$TMP/g.kt" "$GE" "$BJ" >/dev/null;; esac
}
for m in 'tail|s/^        renderVault(ctx, connect)$/        renderVault(ctx, connect)\n        renderRepos(ctx, connect)/' \
         'tail|s/^        renderVault(ctx, connect)$/        renderRepos(ctx, connect)\n        renderVault(ctx, connect)/' \
         'pills|s/            cell.addView(wayPill(ctx, way) { ghSignIn(way, status) })/            cell.addView(pickButton(ctx, way.label) { ghSignIn(way, status) })/' \
         'pills|s/^            cell.addView(wayPill(ctx, way) {$/            cell.addView(pickButton(ctx, way.label) {/' \
         'gh|s/            cell.addView(wayPill(ctx, way) { ghSignIn(way, status) })/            cell.addView(caption(ctx, way.note))/' \
         'gh|s/.setPackage(browser))/)/' \
         'gh|s/            if (token == null) { show(status, RED, "✗ $failure"); return@launch }/            if (token == null) { show(status, RED, "✗ $failure"); return@launch }; ConfigsPrefs(ctx).putSecret("git", "github_token", token)/' \
         'gh|s/                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> landVault(status, o.body)/                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> Unit/'; do
    w="${m%%|*}"; e="${m#*|}"
    gmut "$w" "$e"; rc=$?
    case $rc in 0) ok "G-mutation: caught — $w: ${e:0:80}";; 2) bad "G-mutation: did not apply — $w: ${e:0:80}";; *) bad "G-mutation: NOT caught — $w: ${e:0:80}";; esac
done
jq '(.ui.profile.connect.lines[1].ways) |= map(select(.kind != "gh_auth_login"))' "$BJ" > "$TMP/g1.json"
cmp -s "$BJ" "$TMP/g1.json" && bad "G-mutation: dropping GitHub WebAuth did not apply" \
    || { gh_ok "$PF" "$GE" "$TMP/g1.json" >/dev/null && bad "G-mutation: GitHub WebAuth dropped was NOT caught" || ok "G-mutation: GitHub WebAuth dropped from the GitHub line → RED"; }
lines_ok "$TMP/g1.json" "$SHARED" >/dev/null && bad "G-mutation: A did not see the WebAuth way go" || ok "G-mutation: A also goes red when WebAuth is dropped"

# ── F · Infos: every field of the declared schema ───────────────────────────
echo "== F: Infos renders the WHOLE schema — every declared section and field, filled or empty =="
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
schema_code_ok() {   # $1 = InfoMask.kt, $2 = ProfileFragment.kt
    local sr inf; sr=$(awk '/fun schemaRows\(/{f=1} f{print} f&&/^    }$/{exit}' "$1" | codeof); inf=$(fnof "$2" renderInfos | codeof)
    grep -qF 'for (field in section.fields) {' <<<"$sr" || { echo "schemaRows does not walk every declared field"; return 1; }
    grep -qF 'if (unfilled(v)) out += Row(field, "", Kind.EMPTY) else walk(section.id, v, field, out)' <<<"$sr" || { echo "an unfilled field is not an EMPTY row"; return 1; }
    grep -qF 'if (k !in tops) walk(section.id, o.opt(k), k, out)' <<<"$sr" || { echo "keys beyond the schema are dropped"; return 1; }
    grep -qF 'val schema = InfoMask.schema' <<<"$inf" && grep -qF 'val all = InfoMask.sectionsFor(schema, sections.orEmpty().map { it.id to it.label }, bundle)' <<<"$inf" && grep -qF 'for (section in all) {' <<<"$inf" \
        || { echo "renderInfos does not iterate the declared schema"; return 1; }
    grep -q 'return$' <<<"$inf" && { echo "renderInfos returns early, before the schema"; return 1; }
    grep -qF 'InfoMask.Kind.EMPTY -> { text = getString(R.string.infos_row_empty)' "$2" || { echo "an EMPTY row is not drawn as empty"; return 1; }
    return 0
}
msg=$(schema_code_ok "$IM" "$PF") && ok "F: renderInfos iterates the declared schema; every field is a row, filled or empty; extras still drawn" || bad "F: $msg"
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
R = {"text", "json", "sops", "files", "tree", "literal", "pending"}
# #727 EVERY section directory, not only schema.json's list: schema.json's sections in
# its order, then each C_A1-configs/<dir>/sources.json it does not list yet (STAGED).
listed = [s["id"] for s in vs["sections"]]
staged = sorted(x for x in os.listdir(d) if os.path.isfile(os.path.join(d, x, "sources.json")) and x not in listed)
want = []
for s in vs["sections"] + [{"id": x, "label": x.capitalize(), "staged": True} for x in staged]:
    items = json.load(open(os.path.join(d, s["id"], "sources.json")))["items"]; fields = []
    def walk(o, p):
        if isinstance(o, dict) and o.get("kind") in R: fields.append(p); return
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
         if [ "$1" = "$IM" ]; then ! schema_code_ok "$TMP/f.kt" "$PF" >/dev/null; else ! schema_code_ok "$IM" "$TMP/f.kt" >/dev/null; fi; }
for m in "$IM|s/        for (field in section.fields) {/        for (field in section.fields.take(1)) {/" \
         "$IM|s/            if (unfilled(v)) out += Row(field, \"\", Kind.EMPTY) else walk(section.id, v, field, out)/            if (!unfilled(v)) walk(section.id, v, field, out)/" \
         "$IM|s/if (k !in tops) walk(section.id, o.opt(k), k, out)/if (false) walk(section.id, o.opt(k), k, out)/" \
         "$PF|s/        val all = InfoMask.sectionsFor(schema, sections.orEmpty().map { it.id to it.label }, bundle)/        val all = schema/" \
         "$PF|s/            into.addView(pickButton(ctx, tabLabel(connectTab)) { strip?.getTabAt(connectTab)?.select() })/&\n            return/"; do
    f="${m%%|*}"; e="${m#*|}"
    fmut "$f" "$e"; rc=$?
    case $rc in 0) ok "F-mutation: caught — $(basename "$f"): ${e:0:70}";; 2) bad "F-mutation: did not apply — $(basename "$f"): ${e:0:70}";; *) bad "F-mutation: NOT caught — $(basename "$f"): ${e:0:70}";; esac
done

# ── F2 · (#727) no vault section dropped; the declared apps listed ─────────
echo "== F2: every vault section renders in Infos (the staged ones too), and the apps section lists each declared app =="
union_ok() {   # $1 = InfoMask.kt; prints the first broken rule
    local sf; sf=$(awk '/fun sectionsFor\(/{f=1} f{print} f&&/^        }$/{exit}' "$1" | codeof)
    grep -qF 'schema.forEach { out[it.id] = it }' <<<"$sf" || { echo "sectionsFor drops the declared skeleton"; return 1; }
    grep -qF 'fetched.forEach { (id, label) -> out.getOrPut(id) { SchemaSection(id, label, emptyList()) } }' <<<"$sf" || { echo "sectionsFor drops a section the fetch's schema names"; return 1; }
    grep -qF 'bundle?.keys()?.forEach { k ->' <<<"$sf" && grep -qF 'out.getOrPut(k) { SchemaSection(k, k, emptyList()) }' <<<"$sf" \
        || { echo "sectionsFor drops a top-level key the bundle carries"; return 1; }
    grep -qF 'return out.values.toList()' <<<"$sf" || { echo "sectionsFor does not return what it gathered"; return 1; }
    return 0
}
apps_ok() {   # $1 = ProfileFragment.kt, $2 = build.json; prints the first broken rule
    local inf al route; inf=$(fnof "$1" renderInfos | codeof); al=$(fnof "$1" renderAppList | codeof)
    grep -qF 'if (section.render == "apps") renderAppList(ctx, card.body, bundle, section.route)' <<<"$inf" || { echo "Infos does not draw the declared app list"; return 1; }
    grep -qF 'VaultCockpit.appsListed(bundle, id, fleet)' <<<"$al" || { echo "the list is not the vault's declared apps"; return 1; }
    grep -qF 'pm.getPackageInfo(a.pkg, 0)' <<<"$al" || { echo "installed-on-this-phone is not measured"; return 1; }
    grep -qF 'a.label, a.pkg, VaultCockpit.storeLabel(sources, a)' <<<"$al" || { echo "a row lacks its name, package or store"; return 1; }
    grep -qF 'openWizardRoute(route)' <<<"$al" || { echo "the list does not link into the Store"; return 1; }
    route=$(jq -r '[.ui.profile.infos.schema.sections[] | select(.render == "apps") | .route] | first // ""' "$2")
    [ -n "$route" ] || { echo "no schema section declares the apps list"; return 1; }
    jq -e --arg p "${route#page:config/}" '[.. | objects | select(.id? == $p)] | length > 0' "$2" >/dev/null || { echo "route $route names no declared page"; return 1; }
    return 0
}
msg=$(union_ok "$IM") && ok "F2: Infos draws the skeleton ∪ the fetch's schema ∪ every top-level bundle key — no section is dropped" || bad "F2: $msg"
msg=$(apps_ok "$PF" "$BJ") && ok "F2: the apps section lists every declared app (name · package · store · installed here) and links to $(jq -r '[.ui.profile.infos.schema.sections[] | select(.render == "apps") | .route] | first' "$BJ")" || bad "F2: $msg"
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
for e in 's/            if (section.render == "apps") renderAppList(ctx, card.body, bundle, section.route)/            Unit/' \
         's/a.label, a.pkg, VaultCockpit.storeLabel(sources, a)/a.pkg, a.pkg, a.pkg/' \
         's/val here = apps.map { a -> runCatching { pm.getPackageInfo(a.pkg, 0) }.isSuccess }/val here = apps.map { true }/'; do
    sed "$e" "$PF" > "$TMP/a.kt"
    if cmp -s "$PF" "$TMP/a.kt"; then bad "F2-mutation: did not apply — ${e:0:70}"
    else apps_ok "$TMP/a.kt" "$BJ" >/dev/null && bad "F2-mutation: NOT caught — ${e:0:70}" || ok "F2-mutation: caught — ${e:0:70}"; fi
done
for jm in '(.ui.profile.infos.schema.sections[] | select(.id == "apps")) |= del(.render)' \
          '(.ui.profile.infos.schema.sections[] | select(.id == "apps")) .route = "page:config/no-such-page"'; do
    jq "$jm" "$BJ" > "$TMP/a.json"
    if cmp -s "$BJ" "$TMP/a.json"; then bad "F2-mutation: did not apply — $jm"
    else apps_ok "$PF" "$TMP/a.json" >/dev/null && bad "F2-mutation: NOT caught — $jm" || ok "F2-mutation: caught — $jm"; fi
done
jq '.ui.profile.infos.schema.sections |= map(select(.id != "apps"))' "$BJ" > "$TMP/s.json"
if cmp -s "$BJ" "$TMP/s.json"; then bad "F2-mutation: the staged-section drop did not apply"
elif [ -n "$VAULT_DIR" ]; then schema_drift "$TMP/s.json" "$VAULT_DIR" >/dev/null && bad "F2-mutation: the apps section dropped (the #713 miss) was NOT caught by the drift check" || ok "F2-mutation: the apps section dropped from the skeleton (the #713 miss) → drift RED"
else echo "  UNVERIFIABLE: F2-mutation staged-section drop needs cloud-vault beside (InfoMaskTest pins the ids in CI)"; fi

# ── H · Setup: the index first, then app by app ────────────────────────────
echo "== H: Setup opens on the Fleet Setup index (one row per mapped app), then one section per app =="
index_ok() {   # $1 = ProfileFragment.kt, $2 = build.json; prints the first broken rule
    local rs fi pc
    rs=$(fnof "$1" renderSetup | codeof | grep -E '^ *render[A-Za-z]*\(' | head -1)
    [ "$(echo $rs)" = "renderFleetIndex(ctx, into)" ] || { echo "Setup's first table is [$(echo $rs)], not the Fleet Setup index"; return 1; }
    fi=$(fnof "$1" renderFleetIndex | codeof); pc=$(fnof "$1" paintCard | codeof)
    grep -qF 'for (section in VaultCockpit.layout.sections) {' <<<"$fi" && grep -qF 'tag = "setup-index:${section.id}"' <<<"$fi" \
        || { echo "the index is not one tagged row per declared app"; return 1; }
    grep -qF 'indexRows[card.tag]?.apply {' <<<"$pc" && grep -qF 'StatusLight.colour(context, state)' <<<"$pc" \
        || { echo "an index row is not repainted from its own section's light"; return 1; }
    grep -qF 'indexCards[section.id] = card' "$1" || { echo "an index row cannot open its section"; return 1; }
    grep -qF 'renderWizard(ctx, into)' <<<"$(fnof "$1" renderSetup)" || { echo "the setup steps are gone"; return 1; }
    local missing; missing=$(jq -r '[.ui.vault_connect.cockpit.sections[] | select((.apply // "") == "") | .id] | join(",")' "$2")
    [ -z "$missing" ] || { echo "cockpit section(s) $missing declare no applier"; return 1; }
    for need in keyboard mail drive mesh apps; do
        jq -e --arg a "$need" '.ui.vault_connect.cockpit.sections[] | select(.apply == $a and (.vault | length > 0))' "$2" >/dev/null \
            || { echo "no app section applies $need"; return 1; }
    done
    jq -e '.ui.vault_connect.cockpit.sections[] | select(.apply == "apps") | .label | test("Store")' "$2" >/dev/null \
        || { echo "the apps row of the index does not read as the Store"; return 1; }
    local ra; ra=$(fnof "$1" renderApps | codeof)
    grep -qF 'com.diegonmarcos.superapp.appstore.AppInventory.plan(' <<<"$ra" && grep -qF 'com.diegonmarcos.superapp.appstore.StoreImport.show(this, plan)' <<<"$ra" \
        || { echo "the Store / apps row does not hand the missing apps to the Store's import plan"; return 1; }
    return 0
}
msg=$(index_ok "$PF" "$BJ") && ok "H: the index is Setup's first table, a row per mapped app ($(jq -r '[.ui.vault_connect.cockpit.sections[].id] | join(", ")' "$BJ")), painted from each section" || bad "H: $msg"
grep -qF 'VaultCockpit.ownerEmail(bundle, VaultCockpit.layout)' <<<"$(fnof "$PF" renderMail)" \
    && ok "H: mail lists every declared account even when nobody signed in (the vault's own address names the domain)" || bad "H: mail goes empty after a file import"
grep -qF 'Row("repo · "' "$CP" && grep -qF 'State.PENDING, observed = false)' "$CP" && ! grep -qF '"declared list; cloud-drive reads it", if (repos != null) State.MATCH' "$CP" \
    && ok "H: drive lists every declared repo as its own item, never ticked for being declared" || bad "H: the drive repo list is still one ticked row"
echo "-- H-mutation: the index moved below the sections, a row not repainted, an applier dropped, the keyboard unmapped --"
hm() { sed "$1" "$PF" > "$TMP/h.kt"; cmp -s "$PF" "$TMP/h.kt" && return 2; ! index_ok "$TMP/h.kt" "$BJ" >/dev/null; }
for e in 's/^        renderFleetIndex(ctx, into)$/        renderConfigApply(ctx, into)\n        renderFleetIndex(ctx, into)/' \
         's/        indexRows\[card.tag\]?.apply {/        indexRows["none"]?.apply {/' \
         's/            indexCards\[section.id\] = card/            Unit/' \
         's/if (isAdded) com.diegonmarcos.superapp.appstore.StoreImport.show(this, plan)/if (isAdded) Unit/'; do
    hm "$e"; rc=$?
    case $rc in 0) ok "H-mutation: caught — ${e:0:80}";; 2) bad "H-mutation: did not apply — ${e:0:80}";; *) bad "H-mutation: NOT caught — ${e:0:80}";; esac
done
for jm in '(.ui.vault_connect.cockpit.sections[] | select(.id == "drive")) |= del(.apply)' \
          '.ui.vault_connect.cockpit.sections |= map(select(.id != "keyboard"))' \
          '.ui.vault_connect.cockpit.sections |= map(select(.id != "mail"))' \
          '.ui.vault_connect.cockpit.sections |= map(select(.id != "apps"))' \
          '(.ui.vault_connect.cockpit.sections[] | select(.id == "apps")) .label = "Apps"'; do
    jq "$jm" "$BJ" > "$TMP/h.json"
    if cmp -s "$BJ" "$TMP/h.json"; then bad "H-mutation: did not apply — $jm"
    else index_ok "$PF" "$TMP/h.json" >/dev/null && bad "H-mutation: NOT caught — $jm" || ok "H-mutation: caught — $jm"; fi
done

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
