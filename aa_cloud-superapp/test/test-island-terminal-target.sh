#!/usr/bin/env bash
# Tester: the dynamic-island pill opens a FLEET terminal, resolved from the
# declaration, and names no upstream package id (#675).
#
# THE FAILURE THIS EXISTS FOR.
#
# ui.dynamic_island_action was `app:com.termux.nix` — the UPSTREAM Nix-on-Droid
# package. No fleet build installs it, so the second toolbar pill could only ever
# reach a third-party app or a Toast, and it had no route to Cloud MyTerminal at
# all. The same defect had already been fixed once, on the AGI Terminal tile in
# the #563 follow-up, and the fix there is the shape reused here: stop naming a
# literal package and name an `extapp:<id>` entry in ui.external_apps, which
# ShellActivity.launchExternalApp resolves fork → hub → alt and then OFFERS THE
# INSTALL for. Confusing the two namespaces is not hypothetical in this tree:
# #605 shipped `com.termux.nix` where `cld.termux.nix` was meant and the terminal
# died at login unable to find proot-static.
#
# WHY EVERY SUBJECT IS RESOLVED AND NOTHING IS MATCHED BY NAME.
#
# The dispatcher is reached through the BuildConfig field the gradle file bakes
# the declaration into, not through a function or class name, so renaming
# dispatchDynamicIslandAction cannot make this go quiet. T2 scopes its assertion
# to that one function's BODY with awk rather than grepping the file: `extapp` and
# launchExternalApp both appear a dozen times elsewhere in ShellActivity, so a
# whole-file grep would pass with the branch deleted.
#
# Nothing here touches a device.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null || { echo "ERROR: jq required" >&2; exit 2; }
command -v awk >/dev/null || { echo "ERROR: awk required" >&2; exit 2; }
[ -f "$BJ" ] || { echo "ERROR: declaration not found at $BJ" >&2; exit 2; }
[ -d "$SRC" ] || { echo "ERROR: app sources not found at $SRC" >&2; exit 2; }

echo "== T1: the island target is an extapp: id that resolves in ui.external_apps =="

ACTION="$(jq -r '.ui.dynamic_island_action // ""' "$BJ")"
if [ -z "$ACTION" ]; then
    bad "T1 ui.dynamic_island_action is absent or blank — the island has no declared target"
else
    SCHEME="${ACTION%%:*}"
    TARGET_ID="${ACTION#*:}"
    if [ "$SCHEME" != "extapp" ]; then
        bad "T1 ui.dynamic_island_action = '$ACTION' uses scheme '$SCHEME', not 'extapp'. A fleet app must be reached by its ui.external_apps id: 'app:<packageName>' pins one literal package and, when it is absent, can do nothing but apologise — whereas launchExternalApp walks fork/hub/alt and offers the install"
    elif [ -z "$TARGET_ID" ]; then
        bad "T1 ui.dynamic_island_action = '$ACTION' names the extapp scheme with an empty id"
    else
        # The id must have an entry, AND that entry must carry the two fields
        # launchExternalApp needs to offer an install. An entry with a hub
        # package but no install source degrades to a snackbar, which is the
        # silent-failure shape this ticket is about.
        ENTRY="$(jq -r --arg i "$TARGET_ID" \
            '[.ui.external_apps[] | select(.id == $i)] | first // empty' "$BJ")"
        if [ -z "$ENTRY" ]; then
            bad "T1 ui.dynamic_island_action = '$ACTION' but no ui.external_apps entry has id '$TARGET_ID' — the tap resolves to nothing"
        else
            HUB="$(printf '%s' "$ENTRY" | jq -r '.hub_package // ""')"
            APKURL="$(printf '%s' "$ENTRY" | jq -r '.install_apk_url // ""')"
            INSTPKG="$(printf '%s' "$ENTRY" | jq -r '.install_package // ""')"
            if [ -z "$HUB" ]; then
                bad "T1 ui.external_apps[$TARGET_ID] declares no hub_package, so there is no package for launchExternalApp to launch"
            elif [ -z "$APKURL" ] || [ -z "$INSTPKG" ]; then
                bad "T1 ui.external_apps[$TARGET_ID] has no install_apk_url/install_package, so an absent target ends in a snackbar instead of the offered install that is the whole reason for the extapp form"
            else
                ok "T1 island → extapp:$TARGET_ID (hub $HUB, install offer declared)"
            fi
        fi
    fi
fi

echo
echo "== T2: the extapp branch lives in the island dispatcher's own body =="

# Resolve the dispatcher through the BAKED FIELD, never by function name.
DISPATCHER="$(grep -rl 'BuildConfig\.UI_DYNAMIC_ISLAND_ACTION' --include='*.kt' "$SRC")"
if [ -z "$DISPATCHER" ]; then
    bad "T2 nothing reads BuildConfig.UI_DYNAMIC_ISLAND_ACTION — the declaration is baked and dropped on the floor"
elif [ "$(printf '%s\n' "$DISPATCHER" | wc -l)" -ne 1 ]; then
    bad "T2 $(printf '%s\n' "$DISPATCHER" | wc -l) files read BuildConfig.UI_DYNAMIC_ISLAND_ACTION — one binding, one reader"
else
    # The `when` block that switches on the island scheme, and ONLY that block.
    # Start at the BuildConfig read, brace-match the first `when (` after it.
    # Scoped like this because `extapp` and launchExternalApp are all over this
    # file: a whole-file grep is satisfied by the tile dispatcher and would stay
    # green with the island branch deleted.
    BODY="$(awk '
        /BuildConfig\.UI_DYNAMIC_ISLAND_ACTION/ { seen = 1 }
        seen && !inw && /when[[:space:]]*\(/   { inw = 1 }
        inw {
            print
            n = gsub(/\{/, "{") - gsub(/\}/, "}")
            depth += n
            if (opened || n > 0) { opened = 1; if (depth <= 0) exit }
        }
    ' "$DISPATCHER")"
    if [ -z "$BODY" ]; then
        bad "T2 could not isolate the when-block after the BuildConfig read in $(basename "$DISPATCHER") — the dispatcher shape changed; re-scope this assertion rather than widening it"
    else
        HAS_BRANCH=0
        HAS_CALL=0
        printf '%s\n' "$BODY" | grep -q '"extapp"[[:space:]]*->' && HAS_BRANCH=1
        printf '%s\n' "$BODY" | grep -q 'launchExternalApp' && HAS_CALL=1
        # The literal upstream ids must not be reachable from this body either —
        # a branch that hardcodes a package is the defect wearing the new scheme.
        LITERAL="$(printf '%s\n' "$BODY" | grep -c 'com\.termux')"
        if [ "$HAS_BRANCH" -eq 0 ]; then
            bad "T2 the island dispatcher has no \"extapp\" -> branch, so the declared extapp: target is a dead tap (exactly what 'notifications' became)"
        elif [ "$HAS_CALL" -eq 0 ]; then
            bad "T2 the island dispatcher's extapp branch does not reach launchExternalApp — without it there is no fork/hub/alt walk and no install offer"
        elif [ "$LITERAL" -ne 0 ]; then
            bad "T2 the island dispatcher body names a com.termux* package literally — the target must come from the declaration, not from Kotlin"
        else
            ok "T2 extapp branch present in the dispatcher body and delegates to launchExternalApp, with no package literal"
        fi
    fi
fi

echo
echo "== T3: no upstream terminal id survives in the island declaration =="

# Scoped to the island KEYS, not the file: build.json legitimately names
# com.termux / com.termux.nix elsewhere (phone_folders keywords and the Suite
# Phone tab curate the third-party builds on purpose, and ui._doc_phone_folders
# says so). The claim here is only that the ISLAND no longer binds one.
ISLAND_KEYS="$(jq -r '[.ui.dynamic_island_action, (.ui._vocab_dynamic_island_action // [] | join(" "))] | join(" ")' "$BJ")"
if printf '%s' "$ISLAND_KEYS" | grep -q 'com\.termux'; then
    bad "T3 the island declaration still names an upstream terminal package: $ISLAND_KEYS. No fleet build installs com.termux or com.termux.nix, so binding one is a tap that reaches a third-party app or nothing"
else
    ok "T3 island declaration names no com.termux* id ($ISLAND_KEYS)"
fi

echo
echo "── island terminal target: $PASS passed, $FAIL failed ──"
[ "$FAIL" -eq 0 ]
