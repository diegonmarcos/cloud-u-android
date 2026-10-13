#!/usr/bin/env bash
# Tester: the Calc tile can never silently fall back to a placeholder or a flat
# rectangle.
#
# WHY. Sections.iconResFor resolves by reflective getIdentifier and answers the
# generic ic_link_tile for a name that matches nothing: no build error, no log.
# And the tile paints every glyph with ONE flat tint, so a calculator drawn as
# a body plus separate dark fills for the display and keys collapses into a
# plain rounded rectangle (the bug this guards). The art must therefore (1)
# exist under the name the tile declares, (2) carry the display and keys as
# holes in the body, not as extra fills, and (3) be the same art Cloud Calc
# ships as its launcher icon, so there is one source.
# Static: no device, no build. Fails closed.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
BJ="$APP/build.json"
TILE="$APP/app/src/main/res/drawable/ic_calc.xml"
SRC="$ROOT/ac_cloud-calc/app/src/main/res/drawable/ic_launcher_foreground.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
for f in "$BJ" "$TILE" "$SRC"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

check() { python3 -I - "$BJ" "$TILE" "$SRC" <<'PY'
import json, re, sys
import xml.etree.ElementTree as ET
bj, tile, src = sys.argv[1:4]
A = '{http://schemas.android.com/apk/res/android}'
out = []
def res(ok, msg): out.append(("PASS" if ok else "FAIL") + ": " + msg)

d = json.load(open(bj))
icons = []
def walk(o):
    if isinstance(o, dict):
        if o.get('id') == 'calc' and str(o.get('target', '')).startswith('extapp:'):
            icons.append(o.get('icon'))
        for v in o.values(): walk(v)
    elif isinstance(o, list):
        for v in o: walk(v)
walk(d.get('ui') or {})
res(icons == ['ic_calc'], "the calc tile declares icon ic_calc (got %r)" % icons)

def paths(f):
    return [p for p in ET.parse(f).getroot().iter('path')]
def subpaths(pd):
    return len(re.findall(r'[Mm]', pd))
for name, f in (("tile ic_calc.xml", tile), ("launcher ic_launcher_foreground.xml", src)):
    ps = paths(f)
    res(len(ps) == 1, "%s draws ONE path, so nothing can flatten into the body (found %d)" % (name, len(ps)))
    if ps:
        p = ps[0]
        pd = p.get(A + 'pathData') or ''
        res(p.get(A + 'fillType') == 'evenOdd', "%s uses evenOdd, so the sub-shapes are holes" % name)
        n = subpaths(pd)
        res(n == 11, "%s has body + display + 9 keys = 11 sub-shapes (found %d)" % (name, n))
        fc = p.get(A + 'fillColor') or ''
        res(bool(fc) and not fc.startswith('#00'), "%s has a visible fill" % name)

pt, ps_ = paths(tile), paths(src)
if pt and ps_:
    res(pt[0].get(A + 'pathData') == ps_[0].get(A + 'pathData'),
        "tile pathData is byte-identical to the launcher foreground (one source)")
print("\n".join(out))
PY
}
echo "== Calc icon: exists, is a real calculator under a flat tint, shares the launcher source =="
while IFS= read -r l; do
  case "$l" in PASS:*) ok "${l#PASS: }" ;; FAIL:*) bad "${l#FAIL: }" ;; *) bad "unparsed tester output: $l" ;; esac
done < <(check)
[ $((PASS+FAIL)) -gt 0 ] || bad "no checks ran"
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
