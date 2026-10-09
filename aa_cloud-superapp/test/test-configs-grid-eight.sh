#!/usr/bin/env bash
# Tester: the Configs page grid is 8 icons per row, and a `separator` page is an INLINE
# character that takes no column slot. Static (no device, no build); the 360 dp arithmetic is
# GridColumnsTest.configsGridIsEightWideAndItsIconFitsAt360dp (JVM suite).
#   G1 build.json declares the config section grid_columns 8 (not the Cloud stepper)
#   G2 sectionGrid hands the section's count to TileGridFragment, which uses it over GridColumns.cloud
#   G3 the separator is a wrap_content TextView drawn like Data Apps' separatorCell, outside the slot count
#   G4 Network still reads C3, Peer Control, |, Firewall, DNS, Cloud Mesh, Apps Mesh, ADB Shell
#   G5 the Home Configs row skips the separator (no cell there either)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$APP/build.json" "$KT/TileGridFragment.kt" "$KT/LauncherNavController.kt" "$KT/Sections.kt" "$KT/GroupedTilesFragment.kt"; do
  [ -f "$f" ] || { echo "  ABORT: missing $f"; exit 2; }; done

echo "== G1: declared 8 =="
python3 - "$APP/build.json" <<'PY' && ok "config section grid_columns = 8" || bad "config section does not declare grid_columns 8"
import json, sys
s = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')
sys.exit(0 if s.get('grid_columns') == 8 else 1)
PY

echo "== G2: the count reaches the grid =="
grep -q 'columns = section.gridColumns' "$KT/LauncherNavController.kt" && ok "sectionGrid passes section.gridColumns" || bad "sectionGrid does not pass the section's columns"
grep -q 'o.optInt("grid_columns"' "$KT/Sections.kt" && ok "Sections parses grid_columns" || bad "Sections does not parse grid_columns"
grep -q 'args.getInt(ARG_COLS, 0).takeIf { it > 0 } ?: GridColumns.cloud' "$KT/TileGridFragment.kt" \
  && ok "TileGridFragment uses the declared count, else the Cloud grid's" || bad "TileGridFragment ignores the declared count"
grep -q 'cols, 0, 18)' "$KT/TileGridFragment.kt" && ok "icon is sized from THIS grid's column count" || bad "icon size not derived from the grid's columns"

echo "== G3: the separator is inline =="
sep="$(awk '/private fun separatorView/{f=1} f{print} f&&/^        }$/{exit}' "$KT/TileGridFragment.kt")"
echo "$sep" | grep -q 'WRAP_CONTENT' && ! echo "$sep" | grep -qE 'LayoutParams\(0,' && ok "separator is wrap_content wide (no weight, no slot)" || bad "separator is not wrap_content"
ref="$(awk '/private fun separatorCell/{f=1} f{print} f&&/^        }$/{exit}' "$KT/GroupedTilesFragment.kt")"
for tok in '0x66FFFFFF' 'TextAppearance_Material_Caption' 'Gravity.CENTER' 'IMPORTANT_FOR_ACCESSIBILITY_NO'; do
  echo "$ref" | grep -q "$tok" && echo "$sep" | grep -q "$tok" && ok "same as Data Apps: $tok" || bad "differs from Data Apps' separatorCell: $tok"
done
grep -q 'if (!sep) slots++' "$KT/TileGridFragment.kt" && grep -q 'if (!sep && slots == cols) break' "$KT/TileGridFragment.kt" \
  && ok "separators are not counted against the column slots" || bad "separators count as column slots"

echo "== G4: Network order =="
got="$(python3 - "$APP/build.json" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
print(','.join(p['label'] for p in pages if p.get('subgroup') == 'Network' and not p.get('hidden')))
PY
)"
[ "$got" = "C3,Peer Control,|,Firewall,DNS,Cloud Mesh,Apps Mesh,ADB Shell" ] && ok "Network = $got" || bad "Network is: $got"

echo "== G5: Home row =="
grep -q 'referenced.pages.filterNot { it.separator }' "$KT/Sections.kt" && ok "Home Configs row skips separator pages" || bad "Home row would draw the separator as a cell"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
