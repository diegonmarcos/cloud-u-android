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
RES="$APP/app/src/main/res"
for f in "$BJ" "$SHARED" "$PF" "$IM" "$CP" "$GS" "$RES/values/strings.xml"; do
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
PAGE = {"github_ssh_pat", "vault_file"}
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
lit=""; for k in $KT_AUTH_KINDS gh_auth_login; do grep -qF "\"$k\"" <<<"$(codeof "$PF")" && lit="$lit $k"; done
[ -z "$lit" ] && ok "A: no sign-in kind is a Kotlin literal in the fragment" || bad "A: kind literal(s) in Kotlin:$lit"
grep -q 'private const val KIND_GITHUB_SSH_PAT = "github_ssh_pat"' "$PF" && ok "A: the one page kind is named once" || bad "A: the page kind is not named once"
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
for m in 'c["lines"][1]["ways"][0].pop("note")' \
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
msg=$(oauth_free "$BJ" "$PF" "$IM" "$CP" "$GS") && ok "B: no client id, client secret, OAuth landing or web client on the Account surface" || bad "B: $msg"
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

# ── C · Infos: the fetched configs, masked ──────────────────────────────────
echo "== C: Infos draws the fetched configs through the declared mask; a secret never reaches a view =="
INF=$(fnof "$PF" renderInfos | codeof)
grep -qF 'val sections = VaultConnect.Imported.last' <<<"$INF" && grep -qF 'mask.rows(section.id, bundle.opt(section.id))' <<<"$INF" \
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
for v in "$APP/../../cloud-vault/C_A1-configs/profile-secrets.json" "$APP/../../../cloud-vault/C_A1-configs/profile-secrets.json"; do
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
grep -qF 'itemVerdict(row.state, observed)' <<<"$(fnof "$PF" renderRows)" && ok "D: every item row is worded by that function" || bad "D: renderRows does not word its items through itemVerdict"
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
DECL=$(jq -r '.ui.vault_connect.cockpit.sections[].id' "$BJ" | sort)
DISP=$(awk '/when \(section.id\) \{/{f=1;next} f&&/^ *\}/{f=0} f' "$PF" | grep -oE '^ *"[a-z]+"' | tr -d ' "' | sort)
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

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
