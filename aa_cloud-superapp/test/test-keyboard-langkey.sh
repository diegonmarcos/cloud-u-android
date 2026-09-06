#!/usr/bin/env bash
# The toolbar "Switch language/layout" key must be the globe key's twin, not a
# re-implementation: it emits KeyCode.LANGUAGE_SWITCH (tap) and
# KeyCode.SYSTEM_INPUT_METHOD_PICKER (long-press), so it inherits the
# "Language switch key behavior" pref (internal / input_method / both) and the
# same picker dialog. This runner cannot build, so the proof is static:
#
#   T1  ToolbarKey.LANGUAGE_SWITCH declared, tap -> KeyCode.LANGUAGE_SWITCH,
#       long-press -> KeyCode.SYSTEM_INPUT_METHOD_PICKER
#   T2  both codes are dispatched by InputLogic to the same LatinIME entry points
#       the keyboard globe uses (switchToNextSubtype / showInputPickerDialog)
#   T3  icon mapped in all 3 KeyboardIconsSet maps, each with the SAME drawable
#       that map gives the keyboard globe (NAME_LANGUAGE_SWITCH_KEY)
#   T4  label string exists (ToolbarKey.name.lowercase -> getStringResourceOrName)
#   T5  NOT in the default first-row list (opt-in only, per brief)
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
K="$APP/../ab_cloud-libs-shared/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
TU="$J/latin/utils/ToolbarUtils.kt"
IS="$J/keyboard/internal/KeyboardIconsSet.kt"
IL="$J/latin/inputlogic/InputLogic.java"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }

echo "== keyboard LANGUAGE_SWITCH toolbar key: globe-key twin =="

# T1 registration + codes
has "$TU" '^    LANGUAGE_SWITCH' "T1 ToolbarKey.LANGUAGE_SWITCH declared"
has "$TU" 'LANGUAGE_SWITCH -> KeyCode.LANGUAGE_SWITCH' "T1 tap -> KeyCode.LANGUAGE_SWITCH"
has "$TU" 'LANGUAGE_SWITCH -> KeyCode.SYSTEM_INPUT_METHOD_PICKER' "T1 long-press -> KeyCode.SYSTEM_INPUT_METHOD_PICKER"

# T2 same dispatch as the keyboard globe (no new KeyCode, no new handler)
awk '/case KeyCode.LANGUAGE_SWITCH:/{f=1} f&&/handleLanguageSwitchKey\(\)/{print "hit"; exit}' "$IL" | grep -q hit \
  && ok "T2 InputLogic: LANGUAGE_SWITCH -> handleLanguageSwitchKey" || bad "T2 LANGUAGE_SWITCH dispatch"
has "$IL" 'mLatinIME.switchToNextSubtype()' "T2 handleLanguageSwitchKey -> LatinIME.switchToNextSubtype (cycle per pref)"
awk '/case KeyCode.SYSTEM_INPUT_METHOD_PICKER:/{f=1} f&&/showInputPickerDialog\(\)/{print "hit"; exit}' "$IL" | grep -q hit \
  && ok "T2 InputLogic: SYSTEM_INPUT_METHOD_PICKER -> showInputPickerDialog" || bad "T2 picker dispatch"
has "$J/keyboard/PointerTracker.java" 'code == KeyCode.LANGUAGE_SWITCH' "T2 keyboard globe long-press path exists (CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER)"
has "$J/keyboard/KeyboardActionListenerImpl.kt" 'latinIME.showInputPickerDialog()' "T2 globe long-press ends in the same showInputPickerDialog"
grep -q 'const val LANGUAGE_SWITCH_TOOLBAR\|const val TOOLBAR_LANGUAGE' "$J/keyboard/internal/keyboard_parser/floris/KeyCode.kt" \
  && bad "T2 a parallel KeyCode was added — must reuse LANGUAGE_SWITCH" || ok "T2 no parallel KeyCode (reuses the globe's)"

# T3 icons: same drawable per theme map as the keyboard globe
n=$(grep -c 'ToolbarKey.LANGUAGE_SWITCH -> R.drawable.sym_keyboard_language_switch' "$IS")
[ "$n" = 3 ] && ok "T3 icon mapped in all 3 KeyboardIconsSet maps" || bad "T3 icon maps: $n/3"
python3 - "$IS" <<'EOF' && ok "T3 each map's toolbar globe == that map's keyboard globe drawable" || bad "T3 toolbar/keyboard globe drawables differ within a map"
import re, sys
s = open(sys.argv[1]).read()
kb = re.findall(r'NAME_LANGUAGE_SWITCH_KEY to\s+R\.drawable\.(\w+)', s)
tb = re.findall(r'ToolbarKey\.LANGUAGE_SWITCH -> R\.drawable\.(\w+)', s)
assert len(kb) == 3 and kb == tb, (kb, tb)
EOF
for d in sym_keyboard_language_switch sym_keyboard_language_switch_lxx; do
  [ -f "$K/res/drawable/$d.xml" ] && ok "T3 drawable $d exists" || bad "T3 drawable $d missing"
done

# T4 label
has "$K/res/values/strings.xml" 'name="language_switch" tools:keep' "T4 toolbar label 'language_switch' kept for getStringResourceOrName"

# T5 opt-in, not default
grep -E 'val default = listOf\(.*LANGUAGE_SWITCH' "$TU" >/dev/null \
  && bad "T5 LANGUAGE_SWITCH is in the default first-row list (brief: availability only)" \
  || ok "T5 LANGUAGE_SWITCH not default-visible (lands in 'others' as disabled)"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
