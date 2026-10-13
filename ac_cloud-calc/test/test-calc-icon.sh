#!/usr/bin/env bash
# Tester: Cloud Calc's launcher icon and the Cloud SuperApp Calc tile are ONE
# drawing. ic_launcher_foreground.xml is the source; the tile's ic_calc.xml
# copies its pathData. If they drift, the tile stops matching the app. The
# launcher icon is also one evenOdd path (display and keys cut out of the body),
# the shape that survives the tile's flat tint. The tile-side half is
# aa_cloud-superapp/test/test-calc-icon.sh. Static; fails closed.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
SRC="$APP/app/src/main/res/drawable/ic_launcher_foreground.xml"
TILE="$ROOT/aa_cloud-superapp/app/src/main/res/drawable/ic_calc.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
for f in "$SRC" "$TILE"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done
OUT="$(python3 -I - "$SRC" "$TILE" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
src, tile = sys.argv[1:3]
A = '{http://schemas.android.com/apk/res/android}'
def paths(f): return list(ET.parse(f).getroot().iter('path'))
ps, pt = paths(src), paths(tile)
print(("PASS" if len(ps) == 1 else "FAIL") + ": launcher foreground draws ONE path (found %d)" % len(ps))
if ps:
    p = ps[0]; pd = p.get(A + 'pathData') or ''
    print(("PASS" if p.get(A + 'fillType') == 'evenOdd' else "FAIL") + ": launcher foreground uses evenOdd")
    n = len(re.findall(r'[Mm]', pd))
    print(("PASS" if n == 11 else "FAIL") + ": launcher foreground has body + display + 9 keys = 11 sub-shapes (found %d)" % n)
if ps and pt:
    same = pt[0].get(A + 'pathData') == ps[0].get(A + 'pathData')
    print(("PASS" if same else "FAIL") + ": tile pathData is byte-identical to the launcher foreground")
else:
    print("FAIL: tile has no path")
PY
)"
while IFS= read -r l; do
  case "$l" in PASS:*) ok "${l#PASS: }" ;; FAIL:*) bad "${l#FAIL: }" ;; *) bad "unparsed tester output: $l" ;; esac
done <<<"$OUT"
[ $((PASS+FAIL)) -gt 0 ] || bad "no checks ran"
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
