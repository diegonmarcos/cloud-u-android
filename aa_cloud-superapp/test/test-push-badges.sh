#!/usr/bin/env bash
# Tester: Configs ▸ Panel ▸ Push — the badge system (#515/#516/#517/#518).
#
# WHY THIS EXISTS. The #497 Push pane shipped green and still could not tell
# the owner that three of his badges were dead. It listed all eight producers
# from `ui.notification_center` — a channel inventory, not a badge list — and
# drew each one's build-time `enabled` flag, so on the day Quickmarks, Media
# and Alerts were all missing from the shade the page said everything was
# "Declared on". That was true and useless.
#
# The root cause was a restart asymmetry, and this tester's job is to keep it
# closed. Android kills a package's services on replace and does NOT send
# BOOT_COMPLETED for an update. `KdeStatusService` came back because somebody
# had hand-written its start call into App.onCreate; `FloatingNavService` —
# which owns Quickmarks, Media AND Alerts — was started only from user-driven
# places, all through `startIfPermitted`, which fails silently without the
# overlay permission. One fault, three badges gone, one left standing.
#
# So the assertions below are about the MECHANISM, not about a word in a file:
# every persistent badge must be covered by the declared restart path, and the
# pane must derive both its sections from the declaration rather than from any
# list a human keeps by hand.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin
# and the manifest are checked for the contract that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
NC="$APP/app/src/main/java/com/diegonmarcos/superapp/notificationcenter"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
PUSH="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/PushFragment.kt"
APPKT="$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt"
MEDIA="$APP/app/src/main/java/com/diegonmarcos/superapp/floatingnav/MediaProxy.kt"

echo "== T1: every badge is declared persistent and names an owning service =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
ps = json.load(open(sys.argv[1]))['ui']['notification_center']['producers']
badges = [p for p in ps if p.get('badge')]
if not badges:                                   print('no producer has badge=true')
else:
    bad = [p['id'] for p in badges if not p.get('persistent')]
    noservice = [p['id'] for p in badges if not p.get('service')]
    if bad:         print('badge but not persistent: %s' % bad)
    elif noservice: print('badge with no owning service: %s' % noservice)
    else:           print('OK')
PY
)" "a badge is persistent and has an owner that can be restarted"

echo "== T2: the three badges the owner named are declared badges =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
ps = json.load(open(sys.argv[1]))['ui']['notification_center']['producers']
ids = {p['id'] for p in ps if p.get('badge')}
want = {'kde_status', 'media_now_playing', 'floating_nav_quick_actions'}
missing = want - ids
print('OK' if not missing else 'not declared as badges: %s' % sorted(missing))
PY
)" "KDE Connect, Media Playing and Quickmarks are badges"

echo "== T3: the channel inventory is NOT rendered as badges =="
# phone_notification_listener is a listener SOURCE, launcher_icon_badge is the
# home-screen dot, screensaver_channel is foreground-service plumbing that
# exists only while the cover is up, recovery_advisory is setAutoCancel. None
# of them is a thing the owner flips in a shade badge. They stay DECLARED —
# the data is not deleted — but badge=false keeps them out of the pane.
check "$(python3 - "$BJ" <<'PY'
import json, sys
ps = json.load(open(sys.argv[1]))['ui']['notification_center']['producers']
byid = {p['id']: p for p in ps}
notbadges = ['phone_notification_listener', 'launcher_icon_badge',
             'screensaver_channel', 'recovery_advisory']
missing  = [i for i in notbadges if i not in byid]
wrong    = [i for i in notbadges if i in byid and byid[i].get('badge')]
noreason = [i for i in notbadges if i in byid and not byid[i].get('not_badge_reason')]
if missing:    print('producer deleted rather than classified: %s' % missing)
elif wrong:    print('still rendered as a badge: %s' % wrong)
elif noreason: print('classified badge=false with no stated reason: %s' % noreason)
else:          print('OK')
PY
)" "non-badge producers are kept but classified, with a reason"

echo "== T4: the restart path exists and is registered for BOTH events =="
check "$(python3 - "$MANIFEST" "$NC/BadgeRestartReceiver.kt" <<'PY'
import re, sys, os
manifest, receiver = sys.argv[1], sys.argv[2]
if not os.path.exists(receiver): print('BadgeRestartReceiver.kt does not exist'); raise SystemExit
m = open(manifest).read()
# the receiver block, whatever else the manifest holds
blocks = re.findall(r'<receiver\b.*?</receiver>', m, re.S)
mine = [b for b in blocks if 'BadgeRestartReceiver' in b]
if not mine:
    print('BadgeRestartReceiver is not registered in the manifest')
else:
    b = mine[0]
    need = ['android.intent.action.MY_PACKAGE_REPLACED', 'android.intent.action.BOOT_COMPLETED']
    miss = [a for a in need if a not in b]
    if miss:                          print('receiver does not filter: %s' % miss)
    elif 'android:exported="true"' not in b:
                                      print('system-delivered receiver must be exported')
    else:                             print('OK')
PY
)" "MY_PACKAGE_REPLACED + BOOT_COMPLETED reach BadgeRestartReceiver"

echo "== T5: the restart path is DERIVED, not a second hand-written start =="
# The fix for #515 is not a fourth start call beside the three that already
# exist. App.onCreate must ensure whatever the DECLARATION marks persistent —
# so a badge added to build.json tomorrow is covered with no Kotlin edit.
check "$(python3 - "$APPKT" "$NC/BadgeServices.kt" <<'PY'
import re, sys, os
appkt, svc = sys.argv[1], sys.argv[2]
if not os.path.exists(svc): print('BadgeServices.kt does not exist'); raise SystemExit
a = open(appkt).read()
code = '\n'.join(l for l in a.split('\n') if not l.strip().startswith('//'))
if re.search(r'KdeStatusService\s*\.\s*start\s*\(', code):
    print('App.kt still starts ONE service by name — that is the asymmetry')
elif not re.search(r'BadgeServices\s*\.\s*ensureAll\s*\(', code):
    print('App.kt does not ensure the declared badge services')
else:
    s = open(svc).read()
    if 'restartServices' not in s: print('BadgeServices does not resolve the declared restart set')
    else:                          print('OK')
PY
)" "App.onCreate ensures the declared set, not one named service"

echo "== T6: the Push pane derives both sections from the declaration =="
# A pane that names a badge in Kotlin is a pane that goes stale the next time
# the declaration changes — which is how the previous one ended up unable to
# describe its own subject.
check "$(python3 - "$PUSH" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
code = '\n'.join(l for l in src.split('\n')
                 if not l.strip().startswith('*') and not l.strip().startswith('//')
                 and not l.strip().startswith('/*'))
if 'BadgeDeclaration.badges' not in code:
    print('the pane does not derive its list from the declaration')
elif 'BadgeServices.status' not in code:
    print('the pane does not read LIVE state — a dead badge would read green')
else:
    # No badge id may be spoken in the pane's own code.
    hard = [i for i in ('kde_status', 'media_now_playing', 'floating_nav_quick_actions',
                        'infos_alerts', 'health_activity') if i in code]
    if hard: print('pane hardcodes badge ids: %s' % hard)
    else:    print('OK')
PY
)" "sections 1 and 2 are both derived, and section 1 shows live state"

echo "== T7: the pane can say a badge is NOT in the shade, and why =="
check "$(python3 - "$NC/BadgeServices.kt" <<'PY'
import sys, os
p = sys.argv[1]
if not os.path.exists(p): print('BadgeServices.kt does not exist'); raise SystemExit
s = open(p).read()
need = ['DEAD', 'BLOCKED', 'DISABLED', 'LIVE']
miss = [n for n in need if n not in s]
if miss:                         print('no state for: %s' % miss)
elif 'canDrawOverlays' not in s: print('a missing overlay grant would still be silent')
else:                            print('OK')
PY
)" "a dead or blocked badge reads dead here, with the reason"

echo "== T8: media persistence is declared, not derived from playback =="
# MediaProxy.kt used `.setOngoing(playing)`, so the media badge was ongoing
# only WHILE audio played and evaporated when it stopped. The owner calls it a
# persistent badge; persistence has to be a property of the badge.
check "$(python3 - "$MEDIA" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
code = '\n'.join(l for l in s.split('\n')
                 if not l.strip().startswith('*') and not l.strip().startswith('//'))
if re.search(r'setOngoing\s*\(\s*playing\s*\)', code):
    print('setOngoing(playing) — the badge still dies when the music stops')
elif 'persistent()' not in code:
    print('persistence is not read from the declaration')
elif 'postIdle' not in code:
    print('the badge is still cancelled outright when nothing is playing')
else:
    print('OK')
PY
)" "the media badge holds its place when playback stops"

echo "== T9: the Health badge is declared like every other badge =="
check "$(python3 - "$BJ" "$NC/HealthBadgeService.kt" <<'PY'
import json, sys, os
bj, svc = sys.argv[1], sys.argv[2]
ps = json.load(open(bj))['ui']['notification_center']['producers']
h = next((p for p in ps if p['id'] == 'health_activity'), None)
if h is None:                      print('no health_activity producer')
elif not h.get('badge'):           print('health_activity is not a badge')
elif not h.get('persistent'):      print('health_activity is not persistent')
elif 'health_connect' not in h.get('requires', []):
                                   print('health badge does not declare its grant')
elif not os.path.exists(svc):      print('HealthBadgeService.kt does not exist')
else:
    s = open(svc).read()
    # The honesty rule: a half that cannot be read says so. It must never be
    # estimated from steps and never rendered as 0.
    if 'readActivityToday' not in s: print('not sourced from Health Connect')
    elif 'unavailable' not in s:     print('no unavailable state — a missing grant would read as a number')
    else:                            print('OK')
PY
)" "Health comes through the same declaration and never fabricates a number"

echo "== T10: active kcal and distance are MEASURED, never derived =="
GW="$APP/../ab_cloud-libs-shared/libs/health/src/main/java/com/diegonmarcos/superapp/health/HealthConnectGateway.kt"
check "$(python3 - "$GW" <<'PY'
import sys, os
p = sys.argv[1]
if not os.path.exists(p): print('HealthConnectGateway.kt not found'); raise SystemExit
s = open(p).read()
if 'readActivityToday' not in s:
    print('no honest reader for the badge')
elif 'ActiveCaloriesBurnedRecord' not in s or 'DistanceRecord' not in s:
    print('not reading the measured record types')
elif 'grantedPermissions' not in s:
    print('does not check the grant — readSafe turns a missing grant into 0')
else:
    # No stride-length or kcal-per-step constant anywhere near this path.
    import re
    if re.search(r'stride|STRIDE|kcalPerStep|KCAL_PER_STEP', s):
        print('estimating from steps')
    else:
        print('OK')
PY
)" "both halves come from Health Connect records, with the grant checked"

echo
echo "  ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ]
