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

MQ="$NC/MarketsQuotes.kt"
MSVC="$NC/MarketsBadgeService.kt"

echo "== T11: the Markets badge is declared, with its instruments as DATA =="
# The ticket is "add the Markets badge", and the contract this badge system was
# built on is that adding one is a build.json edit. So the four instruments,
# their captions, their precision and the page each opens have to be IN the
# declaration — not a list in Kotlin that the declaration merely gestures at.
check "$(python3 - "$BJ" "$MSVC" <<'PY'
import json, sys, os
bj, svc = sys.argv[1], sys.argv[2]
ps = json.load(open(bj))['ui']['notification_center']['producers']
m = next((p for p in ps if p['id'] == 'markets_prices'), None)
if m is None:                     print('no markets_prices producer'); raise SystemExit
if not m.get('badge'):            print('markets_prices is not a badge'); raise SystemExit
if not m.get('persistent'):       print('markets_prices is not persistent'); raise SystemExit
if not os.path.exists(svc):       print('MarketsBadgeService.kt does not exist'); raise SystemExit
ins = m.get('instruments') or []
want = ['BRL=X', 'EURUSD=X', 'CL=F', 'GC=F']
got = [i.get('symbol') for i in ins]
if got != want:
    print('declared instruments are %s, not the four asked for %s' % (got, want)); raise SystemExit
# The BRL direction. Yahoo's BRL=X is the USD->BRL cross (reais per dollar,
# ~5.x). A caption reading "BRL/USD" over that number is wrong by a factor of
# ~27 and looks just as plausible as the right one, so it is asserted.
brl = next(i for i in ins if i['symbol'] == 'BRL=X')
if brl.get('label') != 'USD/BRL':
    print('BRL=X is captioned %r - it is the USD->BRL cross and must read USD/BRL' % brl.get('label'))
elif any(not i.get('label') for i in ins):
    print('an instrument has no caption')
elif any(not str(i.get('url', '')).startswith('https://finance.yahoo.com/quote/') for i in ins):
    print('an instrument has no Yahoo ticker page to open')
else:
    # And the Kotlin must read them rather than carry its own copy.
    s = open(svc).read()
    code = '\n'.join(l for l in s.split('\n')
                     if not l.strip().startswith('*') and not l.strip().startswith('//')
                     and not l.strip().startswith('/*'))
    hard = [sym for sym in want if sym in code]
    if hard:    print('the service hardcodes symbols the declaration already carries: %s' % hard)
    elif 'instruments' not in code:
                print('the service never reads the declared instruments')
    else:       print('OK')
PY
)" "Markets is one declaration entry, and its four instruments live there"

echo "== T12: a quote that failed can NEVER be rendered as a price =="
# THE DEFECT SHAPE THIS FLEET KEEPS PAYING FOR: a fetch fails, and the badge
# goes on showing the last good number as though it were live. The mechanism
# that prevents it is that there is no cache to redraw from and that the render
# path dashes on any non-OK status. Both are asserted here; the JVM test
# (MarketsBadgeTest) proves the rendered TEXT carries no digit.
check "$(python3 - "$MQ" "$MSVC" <<'PY'
import re, sys, os
mq, msvc = sys.argv[1], sys.argv[2]
for p in (mq, msvc):
    if not os.path.exists(p):
        print('%s does not exist' % os.path.basename(p)); raise SystemExit
def code(p):
    return '\n'.join(l for l in open(p).read().split('\n')
                     if not l.strip().startswith('*') and not l.strip().startswith('//')
                     and not l.strip().startswith('/*'))
q, s = code(mq), code(msvc)
if 'UNAVAILABLE' not in q:
    print('no unavailable status - a failed fetch has nowhere to land')
elif 'DASH' not in q:
    print('no dash rendering - a failed row would print something numeric')
elif not re.search(r'status\s*!=\s*Status\.OK', q):
    print('row() does not gate on the status, so a failed quote can still format a price')
# A cache is precisely the mechanism that redraws a dead price as a live one.
elif re.search(r'SharedPreferences|getSharedPreferences', q + s):
    print('a price cache exists - that is how stale becomes "live"')
# The per-cycle map must be REPLACED, not merged: a merge leaves last cycle's
# price in place for a symbol that failed this cycle.
elif 'quotes.clear()' not in s:
    print('quotes are merged rather than replaced, so a failed symbol keeps its old price')
elif 'regularMarketTime' not in q:
    print('the quote carries no timestamp of its own, so staleness cannot be told')
else:
    print('OK')
PY
)" "there is no cache, the row dashes on failure, and each cycle replaces the last"

echo "== T13: v8 unauthenticated, tap stays in the app, polling is declared =="
# Three things that each turn into a silent failure in the owner's pocket:
#   v7 needs a cookie+crumb and answers 401 in production while a mocked test
#   stays green; an ACTION_VIEW would make this the seventh leak #156 removed;
#   and a hardcoded interval is how a public endpoint gets an IP rate-limited.
check "$(python3 - "$MQ" "$MSVC" "$BJ" <<'PY'
import json, re, sys
mq, msvc, bj = sys.argv[1], sys.argv[2], sys.argv[3]
def code(p):
    return '\n'.join(l for l in open(p).read().split('\n')
                     if not l.strip().startswith('*') and not l.strip().startswith('//')
                     and not l.strip().startswith('/*'))
q, s = code(mq), code(msvc)
if 'v8/finance/chart' not in q:
    print('not using the v8 chart endpoint')
elif 'v7/finance/quote' in q:
    print('v7 is in the fetch path - it needs a cookie+crumb and 401s unauthenticated')
elif 'ACTION_VIEW' in s:
    print('the badge leaves the app via ACTION_VIEW - #156 removed six of those')
elif 'shortcut_action' not in s:
    print('the tap does not route through the in-app shortcut_action handler')
elif 'isPowerSaveMode' not in s:
    print('power saving is not honoured - the badge would poll flat out on a dying battery')
else:
    m = next((p for p in json.load(open(bj))['ui']['notification_center']['producers']
              if p['id'] == 'markets_prices'), None)
    opt = None if m is None else \
        next((c for c in m.get('customization', []) if c['key'] == 'refresh_minutes'), None)
    if m is None:
        # T11 says this louder, but a tester that raises prints NOTHING and
        # reports "FAIL: — ", which reads like a broken tester rather than a
        # missing badge. Every path here ends in a sentence.
        print('no markets_prices producer, so there is no cadence to check')
    elif opt is None:
        print('refresh_minutes is not declared, so the interval lives in Kotlin')
    elif min(int(o) for o in opt['options']) < 15:
        print('a sub-15-minute poll is offered: %s' % opt['options'])
    elif not re.search(r'refresh_minutes', s):
        print('the service ignores the declared cadence')
    else:
        print('OK')
PY
)" "the proven endpoint, the in-app browser, and a declared non-aggressive cadence"

# ── #535 — the badge the pane renders is the badge the producer POSTED ──
# The pane now finds each badge's live notification by its declared channel
# (BadgeServices.live), so that channel stops being decoration: it has to be
# the one the owner really posts on. #515 already paid once for a declaration
# naming a producer that was not the real one. These read the owner sources
# the declaration points at, never a list typed here.
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
OWNERS_PY='
import json, os, re, sys
bj, src = sys.argv[1], sys.argv[2]
ps = [p for p in json.load(open(bj))["ui"]["notification_center"]["producers"] if p.get("badge")]
def code(path):
    return "\n".join(l for l in open(path).read().split("\n")
                     if not l.strip().startswith(("*", "//", "/*")))
def svc_path(fqcn):
    rel = fqcn.split("com.diegonmarcos.superapp.", 1)[-1].replace(".", "/") + ".kt"
    return os.path.join(src, rel)
files = {}
for p in ps:
    for f in (os.path.join(src, p.get("owner", "")), svc_path(p.get("service", ""))):
        if os.path.isfile(f): files[f] = code(f)
'

echo "== T14: every badge's declared channel is the channel its owner posts on =="
check "$(python3 -c "$OWNERS_PY"'
bad = []
for p in ps:
    f = os.path.join(src, p.get("owner", ""))
    if not os.path.isfile(f): bad.append("%s: owner %s missing" % (p["id"], p.get("owner"))); continue
    chans = set(re.findall(r"CHANNEL_ID\s*=\s*\"([^\"]+)\"", files[f]))
    if p.get("channel") not in chans:
        bad.append("%s declares %r, %s posts on %s" % (p["id"], p.get("channel"), p["owner"], sorted(chans)))
print("; ".join(bad) or "OK")
' "$BJ" "$SRC")" "the pane's channel join key names the real producer"

echo "== T15: no two badges share a notification id =="
# Weather shipped with Markets' 7714: each post REPLACED the other badge.
check "$(python3 -c "$OWNERS_PY"'
seen, bad = {}, []
for f, c in files.items():
    for name, v in re.findall(r"\b(NOTIF_\w+)\s*=\s*(0x[0-9A-Fa-f_]+|\d[\d_]*)\b", c):
        n = int(v.replace("_", ""), 0)
        who = "%s.%s" % (os.path.basename(f), name)
        if n in seen: bad.append("%s == %s (%d)" % (who, seen[n], n))
        else: seen[n] = who
if not seen: print("found no notification ids at all — the scan is blind")
else: print("; ".join(bad) or "OK")
' "$BJ" "$SRC")" "each badge owns its own slot in the shade"

echo "== T16: every ongoing badge comes back when swiped away =="
# Android 14 lets the user dismiss ongoing (even FGS) notifications. With no
# delete intent the badge is gone while its service runs on.
check "$(python3 -c "$OWNERS_PY"'
bad = []
for f, c in files.items():
    on, di = len(re.findall(r"\.setOngoing\(", c)), len(re.findall(r"setDeleteIntent\(", c))
    if di < on: bad.append("%s: %d setOngoing, %d setDeleteIntent" % (os.path.basename(f), on, di))
print("; ".join(bad) or "OK")
' "$BJ" "$SRC")" "a dismissed persistent badge is re-posted"

echo "== T17: 'Keep it pinned' is READ at post time by every badge that pins itself =="
# The Push pane offered this switch on all seven badges and only MediaProxy read
# it: the other six hard-coded setOngoing(true) / FLAG_NO_CLEAR, so the switch
# was decoration. A literal true is the defect; the argument must be derived.
check "$(python3 -c "$OWNERS_PY"'
bad = []
for f, c in files.items():
    n = os.path.basename(f)
    for a in re.findall(r"\.setOngoing\(([^)]*)\)", c):
        if a.strip() in ("true", "false"): bad.append("%s: setOngoing(%s) is not read from the declaration" % (n, a))
    for l in c.split("\n"):
        if "FLAG_NO_CLEAR" in l and "pinned" not in l and "persistent" not in l:
            bad.append("%s: FLAG_NO_CLEAR set unconditionally: %s" % (n, l.strip()))
    if "setOngoing(" in c and not re.search(r"BadgeServices\.pinned\(|isPersistent\(|persistent\(\)", c):
        bad.append("%s: pins itself but never asks BadgeServices.pinned/isPersistent" % n)
print("; ".join(bad) or "OK")
' "$BJ" "$SRC")" "no producer pins itself with a hard-coded true"

echo "== T18: the pane has NO badge renderer of its own, and can launch each/all =="
# One renderer: the platform's template for the posted Notification, which is
# what the shade inflates. Reading title/text out of the Notification and
# drawing them here is a second renderer that drifts from the first.
check "$(python3 - "$PUSH" "$NC/BadgeServices.kt" <<'PY'
import re, sys
def code(p):
    return "\n".join(l for l in open(p).read().split("\n") if not l.strip().startswith(("*", "//", "/*")))
push, svc = code(sys.argv[1]), code(sys.argv[2])
if re.search(r"EXTRA_(TITLE|TEXT|BIG_TEXT|SUB_TEXT)", push):
    print("PushFragment reads notification text itself - that is a second badge renderer")
elif "BadgeServices.view(" not in push:
    print("PushFragment does not render through BadgeServices.view")
elif not re.search(r"recoverBuilder\(.*\)", svc) or "createBigContentView" not in svc or ".apply(ctx" not in svc:
    print("BadgeServices.view does not inflate the platform's RemoteViews for the Notification")
elif "BadgeServices.launchAll(" not in push or "BadgeServices.launch(" not in push:
    print("the pane has no launch-each / launch-all")
else:
    print("OK")
PY
)" "the pane inflates the notification's own view; Launch and Launch all exist"

echo
echo "  ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ]
