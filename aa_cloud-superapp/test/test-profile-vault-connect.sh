#!/usr/bin/env bash
# Tester (#566/#569): Configs ▸ Profile ▸ Connect ▸ Vault configs + Imported tab.
#
# The behaviour (wire format, section grouping, failure hints) is executed by
# app/src/test/.../profile/VaultConnectTest.kt. This file pins what a JVM test
# cannot see:
#   T1  every build.json::ui.vault_connect key reaches BuildConfig, and the
#       Kotlin reads each BuildConfig field it bakes (data, not literals)
#   T2  every R.string.vault_* the Kotlin uses exists in EVERY locale file
#   T3  the Imported tab is in the strip, and its index is read off the list
#   T4  DISPLAY ONLY: nothing on the vault path writes prefs or applies config
#   T5  the one-time code is never stored
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
GR="$APP/app/build.gradle"
PF="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/ProfileFragment.kt"
VC="$APP/app/src/main/java/com/diegonmarcos/superapp/profile/VaultConnect.kt"
RES="$APP/app/src/main/res"

echo "== T1: build.json::ui.vault_connect → BuildConfig → Kotlin =="
KEYS=$(jq -r '.ui.vault_connect | keys[]' "$BJ" 2>/dev/null)
[ -n "$KEYS" ] && ok "T1: ui.vault_connect declares $(echo $KEYS | wc -w) keys" \
               || bad "T1: build.json has no ui.vault_connect"
for k in $KEYS; do
    v=$(jq -r --arg k "$k" '.ui.vault_connect[$k]' "$BJ")
    [ -n "$v" ] && [ "$v" != null ] || bad "T1: ui.vault_connect.$k is empty"
    grep -qE "vaultConnect\.$k\b" "$GR" && ok "T1: gradle reads ui.vault_connect.$k" \
                                     || bad "T1: gradle never reads ui.vault_connect.$k"
done
FIELDS=$(grep -oE '"UI_VAULT_CONNECT_[A-Z_]+"' "$GR" | tr -d '"' | sort -u)
[ "$(echo "$FIELDS" | grep -c .)" = "$(echo $KEYS | wc -w)" ] \
    && ok "T1: one BuildConfig field per declared key" \
    || bad "T1: BuildConfig fields ($(echo $FIELDS)) do not match the declared keys ($(echo $KEYS))"
for f in $FIELDS; do
    grep -q "BuildConfig.$f" "$PF" && ok "T1: ProfileFragment reads $f" \
                                   || bad "T1: $f is baked but never read"
done

echo "== T2: every vault string exists in every locale =="
USED=$(grep -ohE 'R\.string\.vault_[a-z_]+' "$PF" "$VC" | sed 's/R\.string\.//' | sort -u)
[ -n "$USED" ] && ok "T2: $(echo "$USED" | wc -l) vault strings used" || bad "T2: no R.string.vault_* used — labels are literals"
for loc in "$RES"/values*/strings.xml; do
    for s in $USED; do
        grep -q "name=\"$s\"" "$loc" || bad "T2: $s missing from ${loc#$APP/}"
    done
done
[ "$FAIL" = 0 ] && ok "T2: all present in $(ls "$RES"/values*/strings.xml | wc -l) locale files"

echo "== T3: the Imported tab =="
grep -q 'Tab(getString(R.string.vault_tab_imported), imported)' "$PF" \
    && ok "T3: Imported is a tab with its own column" || bad "T3: no Imported tab in the strip"
grep -q 'importedTab = tabs.indexOfFirst { it.column === imported }' "$PF" \
    && ok "T3: its index is read off the tab list" || bad "T3: the Imported index is not derived from the list"
grep -q 'renderImported(ctx, imported)' "$PF" \
    && ok "T3: the column is rendered" || bad "T3: the Imported column is never filled"

echo "== T4: display only =="
# The vault path is VaultConnect.kt plus the vault* / renderImported / importedValue
# functions in the fragment. None may write prefs or run the config apply step.
VAULT_FNS=$(awk '/private fun (vault[A-Za-z]*|showVaultFailure|renderImported|importedValue)\(/{f=1} f{print} /^    }$/{f=0}' "$PF")
[ -n "$VAULT_FNS" ] && ok "T4: found the vault functions" || bad "T4: vault functions not found"
for pat in 'ConfigAutoImport' '.edit()' 'putString' 'setAutheliaCredential' 'ProfilePrefs' 'writeText'; do
    if grep -qF "$pat" "$VC" || echo "$VAULT_FNS" | grep -qF "$pat"; then
        bad "T4: the vault path touches $pat — it must only display"
    else
        ok "T4: no $pat on the vault path"
    fi
done

echo "== T5: the code is not stored =="
echo "$VAULT_FNS" | grep -q 'box.setText("")' && ok "T5: the code box is emptied once sent" \
                                               || bad "T5: the code stays on screen after use"
echo "$VAULT_FNS" | grep -qE 'Prefs\(.*\)\.[a-z]+ *= *code' && bad "T5: the code is written to prefs" \
                                                          || ok "T5: the code is never assigned into prefs"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
