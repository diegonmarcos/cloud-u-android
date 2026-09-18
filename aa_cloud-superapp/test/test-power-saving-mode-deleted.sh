#!/usr/bin/env bash
# Tester: the cloud-power-saving launcher mode is GONE, not hidden.
#
# #322/#326/#327/#329 built this mode (its own theme entry, its own home
# pane + design vocabulary, a Configs ▸ Actions tile, a one-hand star action,
# a "Battery Hunger" system-lever table under Configs ▸ Launcher ▸ Theme, and
# the system-lever engine that backed all three). The owner reversed that
# decision — DELETE THIS MODE — and this asserts the reversal stuck.
#
# THE VACUITY TRAP THIS FILE EXISTS TO AVOID: a check that only asks "does the
# powersaving/ folder exist" passes the instant the folder is deleted, while a
# dangling reference in the Configs Actions grid, the one-hand star, the Theme
# tab or the mode declaration sails through untouched — exactly the shape of
# defect a half-finished deletion ships. So T2 asserts on the REFERENCES
# (identifiers a re-introduction would have to touch), not on the folder.
#
# Scoped to app/ + build.json + data/ — the shipped surface — not test/,
# so this file's own header (which has to NAME the mode to explain what it
# is asserting is gone) cannot fail itself.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
RES="$APP/app/src/main/res"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== T1: the mode's own files are gone =="
for f in \
  "$SRC/launcher/themes/powersaving/PowerSavingFragment.kt" \
  "$SRC/launcher/themes/powersaving/PowerSavingChrome.kt" \
  "$SRC/launcher/themes/powersaving/PowerSavingDesign.kt" \
  "$SRC/system/PowerLevers.kt" \
  "$SRC/settings/PowerSavingAppsPrefs.kt"
do
  [ ! -e "$f" ] && ok "gone: ${f#$APP/}" || bad "still present: ${f#$APP/}"
done
[ ! -d "$SRC/launcher/themes/powersaving" ] \
  && ok "the powersaving/ theme folder itself is gone" \
  || bad "launcher/themes/powersaving/ still exists"

echo "== T2: no REFERENCE to the mode survives anywhere in the shipped app =="
# The identifiers a re-introduction of the mode would have to touch — the
# theme id/enum entry, its class names, its style/colour tokens, its action
# id, and every string resource minted for it. rg over app/ + build.json +
# data/, never over test/ (a tester is allowed to document history).
PATTERNS='CloudPowerSaving|cloud_power_saving|PowerSavingFragment|PowerSavingChrome|PowerSavingDesign|PowerSavingAppsPrefs|PowerLevers|Theme\.Superapp\.PowerSaving|theme_powersave_|power_saving_'
HITS="$(rg -n --hidden -g '!*/build/*' "$PATTERNS" "$APP/app" "$BJ" "$APP/data" 2>/dev/null)"
if [ -z "$HITS" ]; then
  ok "no code, resource or build.json reference to the mode's identifiers"
else
  bad "dangling reference(s) survive:"
  echo "$HITS" | sed 's/^/    /'
fi

echo "== T3: the specific call sites #435 named are actually clean =="
# Configs ▸ Actions / one-hand star (#323): the power_saving is_action entry.
if [ -f "$BJ" ]; then
  rg -q '"id": "power_saving"' "$BJ" \
    && bad "Configs Actions / one-hand star still declares a power_saving action" \
    || ok "no power_saving action entry in Configs Actions / the one-hand star"
fi
# Theme tab (#327): the "Battery Hunger" (singular) system-lever section.
CFG="$SRC/settings/LauncherConfigFragment.kt"
if [ -f "$CFG" ]; then
  rg -q 'fun batteryHungerSection' "$CFG" \
    && bad "LauncherConfigFragment still renders a Battery Hunger section" \
    || ok "no Battery Hunger section function in LauncherConfigFragment"
fi
# The mode declaration (#329): LauncherTheme enum + build.json launcher_themes.
LTP="$SRC/settings/LauncherThemePrefs.kt"
if [ -f "$LTP" ]; then
  rg -q 'CloudPowerSaving' "$LTP" \
    && bad "LauncherTheme enum still declares CloudPowerSaving" \
    || ok "LauncherTheme enum has no CloudPowerSaving entry"
fi
if [ -f "$BJ" ]; then
  python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
ids = [t["id"] for t in ui["launcher_themes"]]
print("FAIL" if "cloud_power_saving" in ids else "OK", "|", ",".join(ids))
PY
fi | { read -r verdict rest;
  if [ "$verdict" = "OK" ]; then ok "build.json launcher_themes has no cloud_power_saving entry ($rest)";
  else bad "build.json launcher_themes still declares cloud_power_saving ($rest)"; fi; }
# ShellActivity (shared chrome, #435's "do NOT delete that file, remove only
# its power-saving branches"): the file must still exist, still handle
# Minimalist Black, and no longer branch on CloudPowerSaving.
SHELL_ACT="$SRC/ShellActivity.kt"
if [ -f "$SHELL_ACT" ]; then
  ok "ShellActivity.kt (shared chrome) still exists"
  rg -q 'LauncherTheme\.CloudMinimalistBlack' "$SHELL_ACT" \
    && ok "ShellActivity still handles Minimalist Black's chrome" \
    || bad "ShellActivity lost its Minimalist Black branch — an unrelated mode broke"
  rg -q 'actionType == "power_saving"' "$SHELL_ACT" \
    && bad "ShellActivity still dispatches the power_saving action" \
    || ok "ShellActivity has no power_saving action handler"
else
  bad "ShellActivity.kt is gone — it is SHARED chrome, it must not be deleted"
fi
# LauncherNavController.goHome() (#435): no CloudPowerSaving home-pane branch.
NAV="$SRC/launcher/LauncherNavController.kt"
if [ -f "$NAV" ]; then
  rg -q 'PowerSavingFragment' "$NAV" \
    && bad "LauncherNavController still routes a theme to PowerSavingFragment" \
    || ok "LauncherNavController has no PowerSavingFragment route"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
