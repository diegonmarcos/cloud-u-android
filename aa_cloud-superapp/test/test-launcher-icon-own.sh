#!/usr/bin/env bash
# Tester: the SuperApp keeps its OWN launcher icon (big white "D" on black + cloud badge),
# and the fleet icon generator/guard is told to leave it alone, exactly like cloud-search.
#
# WHY. The unified cloud-badge icon pattern (1_cicd/src/scripts/cloud-android-launcher-icons.py)
# regenerates every fleet icon and the guard fails on drift. The owner wants the old D icon back
# for cloud-sa only, so aa_cloud-superapp must be in launcher-icons.json::exempt, and the res
# files must stay the D design with adaptive + monochrome layers. Static; fails closed.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
CFG="$ROOT/1_cicd/src/data/launcher-icons.json"
GEN="$ROOT/1_cicd/src/scripts/cloud-android-launcher-icons.py"
RES="$APP/app/src/main/res"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
for f in "$CFG" "$GEN" "$RES/mipmap-anydpi-v26/ic_launcher.xml"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

check() { python3 -I - "$CFG" "$GEN" "$RES" <<'PY'
import json, os, sys
import xml.etree.ElementTree as ET
cfg, gen, res = sys.argv[1:4]
A = '{http://schemas.android.com/apk/res/android}'
out = []
def r(ok, msg): out.append(("PASS" if ok else "FAIL") + ": " + msg)

c = json.load(open(cfg))
ex = c.get("exempt", {})
r("aa_cloud-superapp" in ex, "aa_cloud-superapp is exempt from the icon pattern")
r("ac_cloud-search" in ex, "ac_cloud-search stays exempt")
src = open(gen, encoding='utf-8').read()
r('if app in exempt:\n            continue' in src, "the generator skips exempt apps (so it never overwrites the D icon)")

D = "M36,30 L36,78 L56,78 C70.36,78 78,69.04 78,54 C78,38.96 70.36,30 56,30 Z M46,40 L56,40 C64.84,40 68,46.16 68,54 C68,61.84 64.84,68 56,68 L46,68 Z"
def paths(f): return [p.get(A + 'pathData') for p in ET.parse(os.path.join(res, f)).getroot().iter('path')]
fg = paths("drawable/ic_launcher_foreground.xml")
r(D in fg, "foreground draws the big D")
r(len(fg) == 2, "foreground = D + cloud badge (found %d paths)" % len(fg))
bg = open(os.path.join(res, "drawable/ic_launcher_background.xml"), encoding='utf-8').read()
r("#0A0A0A" in bg and "linearGradient" not in bg and "gradient" not in bg, "background is flat black, not a category gradient")
mono = paths("drawable/ic_launcher_monochrome.xml")
r(mono == [D], "monochrome layer is the D alone")
for n in ("ic_launcher", "ic_launcher_round"):
    root = ET.parse(os.path.join(res, "mipmap-anydpi-v26/%s.xml" % n)).getroot()
    got = {ch.tag: ch.get(A + 'drawable') for ch in root}
    r(got == {"background": "@drawable/ic_launcher_background", "foreground": "@drawable/ic_launcher_foreground",
              "monochrome": "@drawable/ic_launcher_monochrome"}, "%s keeps adaptive background + foreground + monochrome" % n)
print("\n".join(out))
PY
}
echo "== SuperApp own D launcher icon + generator exemption =="
while IFS= read -r l; do
  case "$l" in PASS:*) ok "${l#PASS: }" ;; FAIL:*) bad "${l#FAIL: }" ;; *) bad "unparsed tester output: $l" ;; esac
done < <(check)
[ $((PASS+FAIL)) -gt 0 ] || bad "no checks ran"
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
