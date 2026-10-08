#!/usr/bin/env bash
# Tester (#781): NO PROFILES FIELD WITHOUT AN APP — the field map is the schema.
#
# BUG: Runtime showed "6/7 apps" and a field or two per app while Profiles listed ~50 fields.
# Nothing said which app uses which field, so Runtime could not say what it was missing and a
# field could sit in the vault forever with nothing on the phone reading it — invisible either way.
#
# FIX: build.json::ui.vault_connect.cockpit.vault_fields maps EVERY Profiles field
# (ui.profile.infos.schema, `section › field`) to the app(s) that use it at runtime and whether
# they hold it live (`held`); a field no phone app uses carries `apps: []` and a `why`.
# AccountRuntime counts each app's declared / reported / missing from it.
#
#   V1  the map's keys ARE the schema's fields, both ways
#   V2  every app id is a declared cockpit section; held fields sit in that app's `vault`
#   V3  `apps: []` or `held: false` must say why
#   V4  every item the cloud-vault sources declare (C_A1-configs/<section>/sources.json, when the
#       vault is checked out beside this repo) is a schema field — "the vault schema"
#   V5  every app that reports something holds at least one field
#   V6  (#810) every HELD field is one its app's reader reads, or carries an `unread_why`
#       (a field no reader reaches and no reason names would sit as "not read" forever)
#   V7  (#810) `unread_why` is a non-empty reason, and only on a field an app holds
#   M   each rule, broken on purpose, goes RED
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

VAULT=""
for c in "$APP/../../cloud-vault" "$APP/../../../cloud-vault"; do
    [ -d "$c/C_A1-configs" ] && VAULT="$c/C_A1-configs" && break
done

# check <build.json> [vault C_A1-configs dir] — prints the first broken rule, exit 1; silent exit 0.
check() {
    python3 - "$1" "${2:-}" <<'PY'
import json, os, sys
bj = json.load(open(sys.argv[1])); vault = sys.argv[2]
SEP = " › "
schema = [s["id"] + SEP + f for s in bj["ui"]["profile"]["infos"]["schema"]["sections"] for f in s["fields"]]
cockpit = bj["ui"]["vault_connect"]["cockpit"]
vf = {k: v for k, v in cockpit.get("vault_fields", {}).items() if not k.startswith("_")}
sections = {s["id"]: s for s in cockpit["sections"]}
def die(m): print(m); sys.exit(1)
if not schema: die("V1: the schema declares no field")
miss = [f for f in schema if f not in vf]
if miss: die("V1: Profiles field(s) with no app mapping: " + ", ".join(miss))
extra = [f for f in vf if f not in schema]
if extra: die("V1: mapped field(s) Profiles does not show: " + ", ".join(extra))
for path, e in vf.items():
    apps, held = e.get("apps", []), e.get("held", True)
    for a in apps:
        if a not in sections: die(f"V2: {path} names undeclared app '{a}'")
        if held and path.split(SEP)[0] not in sections[a].get("vault", []):
            die(f"V2: {path} is held by '{a}', whose vault sections are {sections[a].get('vault')}")
    if (not apps or not held) and not str(e.get("why", "")).strip():
        die(f"V3: {path} is held by no app and gives no why")
if vault:
    for sec in sorted({f.split(SEP)[0] for f in schema}):
        src = os.path.join(vault, sec, "sources.json")
        if not os.path.isfile(src): continue
        for item in json.load(open(src)).get("items", {}):
            if not any(f == sec + SEP + item or f.startswith(sec + SEP + item + SEP) for f in schema):
                die(f"V4: the vault declares {sec}{SEP}{item}, which no Profiles field (and so no app mapping) covers")
# V6: what each reader reads unconditionally (AccountRuntime.readOne), by the section's `apply`.
def readable(s):
    a, rt = s.get("apply"), s.get("runtime", {})
    if a == "about": return {"about" + SEP + "profile"} if s.get("fields") else set()
    if a in ("drive",): return {"git" + SEP + "github_token", "git" + SEP + "ssh_private_key"}   # VaultCockpit.DRIVE_SECRETS
    if a == "cloud-drive": return {p for p, e in vf.items() if s["id"] in e.get("apps", []) and e.get("held", True)}
    if a == "mail": return {"mail" + SEP + k for k in ("endpoints", "accounts", "passwords")}
    if a == "ai": return {"ai" + SEP + "tokens" + SEP + k for k in cockpit.get("ai_tokens", {})}
    if a == "keyboard": return {"autocomplete" + SEP + "manifest"} | {"autocomplete" + SEP + k for k in rt.get("lists", {})}
    return set()   # mesh: per device (picked or from the live tunnel) — every field needs its reason
for path, e in vf.items():
    why = e.get("unread_why")
    if why is not None and not str(why).strip(): die(f"V7: {path} has an empty unread_why")
    if why is not None and not (e.get("apps") and e.get("held", True)): die(f"V7: {path} carries unread_why but no app holds it")
    if not (e.get("apps") and e.get("held", True)) or why: continue
    for a in e["apps"]:
        if not any(path == r or path.startswith(r + SEP) or r.startswith(path + SEP) for r in readable(sections[a])):
            die(f"V6: {path} is held by '{a}', whose reader never reads it, and gives no unread_why")
for sid, s in sections.items():
    if s.get("runtime", {}).get("reports", True) is False or s.get("runtime", {}).get("fields", True) is False: continue
    if not any(sid in e.get("apps", []) and e.get("held", True) for e in vf.values()):
        die(f"V5: app '{sid}' reports fields but holds none in the map")
PY
}

echo "== V1-V7: the field map against the schema${VAULT:+ and the vault sources ($VAULT)} =="
msg=$(check "$BJ" "$VAULT") && ok "V1-V5: $(jq '[.ui.profile.infos.schema.sections[].fields[]] | length' "$BJ") Profiles fields, each mapped to its app(s) or a why" || bad "$msg"
[ -n "$VAULT" ] || echo "  (cloud-vault not beside this repo — V4 not checked here)"
held=$(jq '[.ui.vault_connect.cockpit.vault_fields | to_entries[] | select(.key | startswith("_") | not) | select(.value.held != false and (.value.apps | length) > 0)] | length' "$BJ")
none=$(jq '[.ui.vault_connect.cockpit.vault_fields | to_entries[] | select(.key | startswith("_") | not) | select(.value.held == false or (.value.apps | length) == 0)] | length' "$BJ")
ok "map: $held fields held live by an app, $none input-only or used by no phone app (each with its why)"

echo "== the app reads the map (Kotlin) =="
PKG="$APP/../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"
grep -qF 'o.optJSONObject("vault_fields")' "$PKG/VaultCockpit.kt" && ok "VaultCockpit parses vault_fields" || bad "vault_fields is not parsed"
grep -qF 'VaultCockpit.layout.vaultFields)' "$PKG/AccountRuntime.kt" && ok "every reading is counted against it" || bad "AccountRuntime ignores vault_fields"
grep -qF 'e.optString("unread_why")' "$PKG/VaultCockpit.kt" && ok "#810 VaultCockpit parses unread_why" || bad "unread_why is not parsed"
grep -qF 'partition { vf[it]?.unreadWhy?.isNotBlank() == true }' "$PKG/AccountRuntime.kt" && ok "#810 a field with an unread_why is justified, not unread" || bad "AccountRuntime ignores unread_why"
grep -qF 'unreadOf(fields, values.keys, r.json)' "$PKG/AccountFleet.kt" && ok "#810 fleet store files the export walked are justified when empty" || bad "AccountFleet does not use unreadOf"
grep -qF '.put("justified", r.justified.size)' "$PKG/AccountRuntime.kt" && ok "#810 /api/account/runtime counts justified per app (totals sum it)" || bad "justified is not counted"
grep -qF '"unmapped", unmapped()' "$PKG/AccountDebugApi.kt" && ok "/api/account/runtime lists the unheld fields with their why" || bad "the debug API does not list unheld fields"

echo "== the keyboard serves its lists (no longer 'not reporting') =="
LIBS="$APP/../ab_cloud-libs-shared/libs"
AIDL="$LIBS/text-tools/src/main/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl"
# An AIDL method's transaction code is its position: methods are only ever APPENDED, or every
# client built before them would call the wrong method on a newer keyboard. clipboardLists came
# next-to-last's turn first; its write half importClipboardLists was appended after it.
tail2=$(grep -E '^\s+(String\[\]|String|List<String>) [a-zA-Z]+\(' "$AIDL" | tail -2 | tr -s ' ')
[ "$tail2" = "$(printf ' String clipboardLists();\n String[] importClipboardLists(in String json);')" ] \
    && ok "ITextTools ends clipboardLists, importClipboardLists (appended, transaction codes unchanged)" || bad "AIDL tail is not clipboardLists then importClipboardLists: $tail2"
grep -qF 'override fun importClipboardLists(json: String?): Array<String>' "$LIBS/keyboard/src/main/java/com/diegonmarcos/superapp/texttools/TextToolsService.kt" && ok "the keyboard's TextToolsService takes lists in" || bad "TextToolsService does not implement importClipboardLists"
grep -qF 'importJson(org.json.JSONObject().put("tabs", tabs).put("files", files), context)' "$LIBS/keyboard/src/main/java/helium314/keyboard/latin/database/ClipboardDao.kt" \
    && ok "Import JSON from a folder and the Account apply are ONE parser (importFromDir delegates to importJson)" || bad "importFromDir no longer delegates to importJson — two parsers"
grep -qF 'override fun clipboardLists(): String? =' "$LIBS/keyboard/src/main/java/com/diegonmarcos/superapp/texttools/TextToolsService.kt" && ok "the keyboard's TextToolsService serves it" || bad "TextToolsService does not implement clipboardLists"
grep -qF 'val export = exportJson()' "$LIBS/keyboard/src/main/java/helium314/keyboard/latin/database/ClipboardDao.kt" \
    && ok "Export JSON and the runtime read are ONE format (exportToDir writes exportJson)" || bad "exportToDir no longer writes exportJson — two formats"

echo "== M: every rule, broken on purpose, goes RED =="
mutate() {   # $1 = label, $2 = jq filter
    jq "$2" "$BJ" > "$TMP/m.json" || { bad "M: $1 — jq failed"; return; }
    cmp -s "$BJ" "$TMP/m.json" && { bad "M: $1 — mutation did not apply"; return; }
    check "$TMP/m.json" "$VAULT" >/dev/null && bad "M: $1 — NOT caught" || ok "M: $1 → RED"
}
mutate "a new Profiles field with no app"     '(.ui.profile.infos.schema.sections[] | select(.id == "git")).fields += ["new_secret"]'
mutate "a mapping dropped"                    '.ui.vault_connect.cockpit.vault_fields |= del(.["git › github_token"])'
mutate "a mapping for a field nobody shows"   '.ui.vault_connect.cockpit.vault_fields["git › ghost"] = {"apps": ["drive"], "held": true}'
mutate "an undeclared app"                    '.ui.vault_connect.cockpit.vault_fields["git › github_token"].apps = ["nope"]'
mutate "held by an app outside its sections"  '.ui.vault_connect.cockpit.vault_fields["git › github_token"].apps = ["mail"]'
mutate "unused with no why"                   '.ui.vault_connect.cockpit.vault_fields["git › repos"] |= del(.why)'
mutate "a peer's profiles, no unread_why"     '.ui.vault_connect.cockpit.vault_fields["peers › samsung-a37 › profiles"] |= del(.unread_why)'
mutate "mesh profiles, no unread_why"         '.ui.vault_connect.cockpit.vault_fields["mesh › profiles"] |= del(.unread_why)'
mutate "a held field no reader reads"         '.ui.vault_connect.cockpit.vault_fields["about › addresses"] = {"apps": ["about"], "held": true}'
mutate "an empty unread_why"                  '.ui.vault_connect.cockpit.vault_fields["mesh › profiles"].unread_why = " "'
mutate "unread_why on an unheld field"        '.ui.vault_connect.cockpit.vault_fields["git › repos"].unread_why = "x"'
mutate "an AI token the reader never maps"    '.ui.vault_connect.cockpit.ai_tokens = {}'
mutate "a reporting app holding nothing"      '.ui.vault_connect.cockpit.vault_fields |= with_entries(if (.value.apps | index("keyboard")) then .value.apps = [] | .value.why = "x" else . end)'
if [ -n "$VAULT" ]; then
    mkdir -p "$TMP/vault"; cp -r "$VAULT/." "$TMP/vault/"
    python3 - "$TMP/vault/git/sources.json" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["items"]["deploy_key"] = {"kind": "literal", "value": "x"}; json.dump(d, open(p, "w"))
PY
    check "$BJ" "$TMP/vault" >/dev/null && bad "M: a vault item no Profiles field covers — NOT caught" || ok "M: a vault item no Profiles field covers → RED"
fi

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
