#!/usr/bin/env bash
# #1077 items 1, 7 and 8: what the keyboard shows as "every autocomplete field the fleet deploys" comes from ONE
# declaration, and the parts of it that live in other apps cannot drift unnoticed.
#
#   S1  Cloud Account's autofill store is read from libs:autofill's AutofillSot (columns(path)); no column name is
#       retyped in the keyboard's page, list or in AutofillSchemas.
#   S2  Cloud Vault's IdentityField / AutofillSaveItem and the add-identity extras (vault app) equal the names in
#       AutofillSchemas.Vault / IDENTITY_HANDOFF; so do Cloud Account's VaultIdentityHandoff extras. The vault app
#       cannot be compiled into the keyboard, so this is the guard that keeps the one declaration true.
#   S3  the Autocomplete page and the list are fed by AutofillSchemas / AutofillSot only.
#   S4  the list masks sensitive rows and asks for the screen lock; a sensitive copy is marked sensitive and never
#       reaches the clipboard history.
#   S5  the clipboard strip is painted with the shared strip background token (the white line).
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
L="$ROOT/ab_cloud-libs-shared/libs"
K="$L/keyboard/src/main/java/helium314/keyboard"
SCH="$K/latin/autofill/AutofillSchemas.kt"
VAULT="$ROOT/ac_cloud-vault/app/src/main/kotlin/com/x8bit/bitwarden/data/autofill"
ACCT="$ROOT/ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/autofill/VaultIdentityHandoff.kt"
FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
for f in "$SCH" "$K/settings/screens/AutocompleteScreen.kt" "$K/latin/autofill/AutocompleteItems.kt" "$K/keyboard/clipboard/ClipboardHistoryView.kt" \
         "$VAULT/cloud/IdentityField.kt" "$VAULT/cloud/AddIdentityRequest.kt" "$VAULT/model/AutofillSaveItem.kt" "$ACCT"; do
    [ -f "$f" ] || { printf 'FAIL   %s is missing — every assertion below would read an empty file and pass\n' "$f"; exit 1; }
done

echo "== keyboard Autocomplete: one declaration, nothing retyped =="

# ── S1 the account's columns come from AutofillSot ──────────────────────────
if grep -q 'AutofillSot.columns(path)' "$SCH"; then pass "S1 account tables read AutofillSot.columns"; else fail "S1 AutofillSchemas no longer reads AutofillSot.columns"; fi
if grep -nE '"(given_name|family_name|postal_code|street|house_number|bday)"' "$SCH" "$K/settings/screens/AutocompleteScreen.kt" "$K/latin/autofill/AutocompleteItems.kt" >/dev/null; then
    # bday is named once, as the sensitive column; every other column must not be retyped
    if grep -nE '"(given_name|family_name|postal_code|street|house_number)"' "$SCH" "$K/settings/screens/AutocompleteScreen.kt" "$K/latin/autofill/AutocompleteItems.kt"; then
        fail "S1 an account column is retyped outside AutofillSot"
    else pass "S1 no account column is retyped (only the sensitive bday)"; fi
else pass "S1 no account column is retyped"; fi

# ── S2 the vault's names equal the shared declaration ───────────────────────
python3 - "$SCH" "$VAULT/cloud/IdentityField.kt" "$VAULT/cloud/AddIdentityRequest.kt" "$VAULT/model/AutofillSaveItem.kt" "$ACCT" <<'PYEOF'
import re, sys
sch, idf, req, save, acct = (open(p, encoding='utf-8').read() for p in sys.argv[1:6])
def lst(name):
    m = re.search(r'val %s = listOf\((.*?)\)' % name, sch, re.S)
    assert m, f'{name} is gone from AutofillSchemas'
    return re.findall(r'"([^"]+)"', re.sub(r'//[^\n]*', '', m.group(1)))
bad = []
# IdentityField constants, in order
enum = re.search(r'enum class IdentityField\(.*?\) \{(.*?)\n\}', idf, re.S).group(1)
consts = re.findall(r'^\s{4}([A-Z][A-Z0-9_]*)\(isDocument', enum, re.M)
if consts != lst('IDENTITY_FIELDS'): bad.append(f'IdentityField {consts} != IDENTITY_FIELDS {lst("IDENTITY_FIELDS")}')
def params(cls):
    m = re.search(r'data class %s\((.*?)\)\s*:\s*AutofillSaveItem' % cls, save, re.S)
    assert m, f'AutofillSaveItem.{cls} is gone'
    return re.findall(r'val (\w+):', m.group(1))
for cls, name in (('Login', 'LOGIN'), ('Card', 'CARD'), ('Identity', 'IDENTITY_SAVE')):
    if params(cls) != lst(name): bad.append(f'AutofillSaveItem.{cls} {params(cls)} != {name} {lst(name)}')
extras = lambda src: re.findall(r'const val EXTRA_\w+(?::\s*String)?\s*=\s*"com\.diegonmarcos\.cloudvault\.extra\.(\w+)"', src)
want = lst('IDENTITY_HANDOFF')
vault_extras = [e for e in extras(req) if e != 'VERSION']
acct_extras = [e for e in extras(acct) if e != 'VERSION']
if vault_extras != want: bad.append(f'vault extras {vault_extras} != IDENTITY_HANDOFF {want}')
if acct_extras != want: bad.append(f'account extras {acct_extras} != IDENTITY_HANDOFF {want}')
if bad:
    print('\n'.join('    ' + b for b in bad)); sys.exit(1)
PYEOF
if [ $? = 0 ]; then pass "S2 vault IdentityField / AutofillSaveItem / add-identity extras and the account hand-off equal the shared declaration"
else fail "S2 the shared declaration drifted from the vault or the account"; fi

# ── S3 page and list read the one declaration ───────────────────────────────
if grep -q 'AutofillSchemas.all()' "$K/settings/screens/AutocompleteScreen.kt"; then pass "S3 the Autocomplete page lists AutofillSchemas.all()"; else fail "S3 the page does not read AutofillSchemas.all()"; fi
if grep -q 'AutofillSot.PROFILE_FIELDS' "$K/latin/autofill/AutocompleteItems.kt" && grep -q 'AutofillSchemas.vault()' "$K/latin/autofill/AutocompleteItems.kt"; then
    pass "S3 the list rows come from AutofillSot / AutofillSchemas"; else fail "S3 the list does not read the one declaration"; fi
if grep -q 'AutofillSotClient.profiles' "$K/keyboard/clipboard/ClipboardHistoryView.kt"; then pass "S3 the values are read from Cloud Account's provider"; else fail "S3 the list does not read the provider"; fi

# ── S4 sensitive rows ───────────────────────────────────────────────────────
if grep -q 'NEEDS_UNLOCK -> AutocompleteUnlockActivity.start' "$K/keyboard/clipboard/ClipboardHistoryView.kt" \
   && grep -q 'AutocompleteItems.shown(item, unlocked)' "$K/keyboard/clipboard/ClipboardHistoryView.kt"; then
    pass "S4 sensitive rows are drawn masked and ask for the screen lock"; else fail "S4 sensitive rows are not masked / gated"; fi
if grep -q 'EXTRA_IS_SENSITIVE' "$K/keyboard/clipboard/ClipboardHistoryView.kt" && grep -q 'getClipSensitivity(description) == true) return' "$K/latin/ClipboardHistoryManager.kt"; then
    pass "S4 a sensitive copy is marked and never enters the clipboard history"; else fail "S4 a sensitive copy can land in the clipboard history"; fi
if grep -q 'AutocompleteUnlock.lock()' "$K/keyboard/clipboard/ClipboardHistoryView.kt"; then pass "S4 closing the panel locks again"; else fail "S4 the unlock outlives the panel"; fi

# ── S5 the clipboard strip background ───────────────────────────────────────
if grep -q 'clipboardStrip.setBackgroundColor(stripBackground)' "$K/keyboard/clipboard/ClipboardHistoryView.kt" \
   && grep -q 'stateColor(ColorType.STRIP_BACKGROUND)' "$K/keyboard/clipboard/ClipboardHistoryView.kt" \
   && grep -q '(clipboardStrip.parent as? View)?.setBackgroundColor(stripBackground)' "$K/keyboard/clipboard/ClipboardHistoryView.kt"; then
    pass "S5 the clipboard strip and its scroller are painted with STRIP_BACKGROUND, like the typing strip"
else fail "S5 the clipboard toolbar line is not painted with the strip background token"; fi
if grep -q 'colors.stateColor(ColorType.STRIP_BACKGROUND)' "$K/latin/suggestions/SuggestionStripView.kt"; then pass "S5 and the typing strip uses the same token"; else fail "S5 the typing strip left the token"; fi

echo
[ "$FAILURES" = 0 ] && echo "== keyboard Autocomplete: all assertions hold ==" || echo "== keyboard Autocomplete: $FAILURES FAILED =="
[ "$FAILURES" = 0 ]
