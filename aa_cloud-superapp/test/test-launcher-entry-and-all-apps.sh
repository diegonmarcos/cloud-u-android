#!/usr/bin/env bash
# Tester: the SuperApp is in Android's own app drawer AND in its own Cloud > All apps list.
#
# WHY. The SuperApp's icon was missing from Cloud > All apps. Root cause:
# PhoneAppsFragment.collectLaunchableAppsStatic dropped `pkg == ctx.packageName`, so the
# list of installed launchable apps never contained the app itself. The manifest side must
# also stay right: the HOME activity alone (CATEGORY_HOME) is not an app-drawer entry, a
# separate MAIN + LAUNCHER activity is. Static: no device, no build. Fails closed.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
MF="$APP/app/src/main/AndroidManifest.xml"
FR="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneAppsFragment.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
for f in "$MF" "$FR"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

check() { python3 -I - "$MF" "$FR" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
mf, fr = sys.argv[1:3]
A = '{http://schemas.android.com/apk/res/android}'
out = []
def res(ok, msg): out.append(("PASS" if ok else "FAIL") + ": " + msg)

app = ET.parse(mf).getroot().find('application')
home, launcher = [], []
for el in list(app.iter('activity')) + list(app.iter('activity-alias')):
    for f in el.findall('intent-filter'):
        acts = {a.get(A + 'name') for a in f.findall('action')}
        cats = {c.get(A + 'name') for c in f.findall('category')}
        if 'android.intent.action.MAIN' in acts:
            if 'android.intent.category.HOME' in cats: home.append(el.get(A + 'name'))
            if 'android.intent.category.LAUNCHER' in cats: launcher.append(el.get(A + 'name'))
res(len(home) >= 1, "a MAIN + HOME activity exists (the home-screen door): %r" % home)
res(len(launcher) >= 1, "a MAIN + LAUNCHER activity or alias exists (the app-drawer door): %r" % launcher)
res(not (set(home) & set(launcher)), "HOME and LAUNCHER are different components (split-screen needs its own task)")

src = open(fr, encoding='utf-8').read()
body = src[src.index('private fun collectLaunchableAppsStatic'):]
res(not re.search(r'pkg\s*==\s*ctx\.packageName', body) and not re.search(r'packageName\s*==\s*ctx\.packageName', body),
    "the All apps enumeration does not drop its own package")
res('listsPackage(' in body, "the All apps enumeration filters through PhoneAppsFragment.listsPackage (unit-tested)")
print("\n".join(out))
PY
}
echo "== Launcher entry + All apps lists the SuperApp itself =="
while IFS= read -r l; do
  case "$l" in PASS:*) ok "${l#PASS: }" ;; FAIL:*) bad "${l#FAIL: }" ;; *) bad "unparsed tester output: $l" ;; esac
done < <(check)
[ $((PASS+FAIL)) -gt 0 ] || bad "no checks ran"
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
