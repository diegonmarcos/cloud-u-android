#!/usr/bin/env bash
# Tester: the edge-menu overlay (libs:launcher-onehand) posts NO notification.
#
# WHY THIS EXISTS. The owner reported, twice, a persistent "Edge menus on"
# notification/badge. The first fix (b314237a) only made it MINIMAL: new channel,
# IMPORTANCE_MIN, VISIBILITY_SECRET, setOngoing(false) - but the service still
# called startForeground, and Android REQUIRES a visible notification for a
# foreground service (plus the FGS task-manager entry), so the line stayed. The
# overlay needs only "Display over other apps"; the process is already kept alive
# by the app. The service is therefore a plain started service now.
#
# Static tester: no device, no build. Each check runs over the lib's main sources
# with comments stripped, so prose that mentions the banned calls does not trip it.
#   T1  no startForeground / startForegroundService / FOREGROUND_SERVICE type in
#       the lib (sources AND manifest)
#   T2  no Notification.Builder / NotificationCompat / NotificationChannel( /
#       createNotificationChannel / notify( anywhere in the lib
#   T3  the controller starts the service with plain startService
#   T4  upgrade cleanup: both legacy channels are deleted and 7750 cancelled
#   T5  MUTATION: the scanner must flag each reintroduction (otherwise T1/T2 are
#       a detector that cannot fire).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
LIB="$APP/../ab_cloud-libs-shared/libs/launcher-onehand"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

[ -d "$LIB/src/main" ] || { echo "  FAIL: $LIB/src/main missing"; exit 1; }

# scan <libdir> <kind>: print one line per violation. kind = fgs | notif
scan() {
python3 - "$1" "$2" <<'PYEOF'
import os, re, sys
root, kind = sys.argv[1], sys.argv[2]
pats = {
 "fgs":   r'startForeground(Service)?\b|FOREGROUND_SERVICE|foregroundServiceType|ServiceInfo\.FOREGROUND',
 "notif": r'Notification\.Builder|NotificationCompat|(?<!delete)NotificationChannel\s*\(|createNotificationChannel|\.notify\s*\(|\bnotify\s*\(',
}
rx = re.compile(pats[kind])
def strip(t, xml):
    if xml: return re.sub(r'<!--.*?-->', lambda m: '\n' * m.group(0).count('\n'), t, flags=re.S)
    t = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), t, flags=re.S)
    return re.sub(r'//[^\n]*', '', t)
n = 0
for d, _, fs in os.walk(os.path.join(root, "src", "main")):
    for f in fs:
        if not f.endswith((".kt", ".java", ".xml")): continue
        p = os.path.join(d, f)
        for i, line in enumerate(strip(open(p, encoding="utf-8").read(), f.endswith(".xml")).split("\n"), 1):
            if rx.search(line):
                print("%s:%d: %s" % (os.path.relpath(p, root), i, line.strip())); n += 1
sys.exit(1 if n else 0)
PYEOF
}

echo "== T1/T2: real tree"
OUT="$(scan "$LIB" fgs)" && ok "no foreground-service call, permission or type in launcher-onehand" \
  || bad "foreground service present: $OUT"
OUT="$(scan "$LIB" notif)" && ok "no notification built, posted or channel created in launcher-onehand" \
  || bad "notification code present: $OUT"

echo "== T3: controller start"
CTL="$(cat "$LIB/src/main/java/com/diegonmarcos/superapp/onehand/OneHandController.kt")"
grep -q 'ctx\.startService(' <<<"$CTL" \
  && ok "OneHandController starts the overlay with startService" \
  || bad "OneHandController does not call ctx.startService(...)"

echo "== T4: upgrade cleanup"
for needle in 'deleteNotificationChannel' '"onehand_overlay"' '"onehand_overlay_min"' 'nm.cancel(LEGACY_NOTIF_ID)' 'LEGACY_NOTIF_ID = 7750'; do
  grep -qF -- "$needle" <<<"$CTL" && ok "retireLegacyNotification has $needle" || bad "missing $needle in OneHandController"
done
BOOT="$(cat "$LIB/src/main/java/com/diegonmarcos/superapp/onehand/OneHandController.kt")"
grep -q 'fun startIfEnabled' <<<"$BOOT" && awk '/fun startIfEnabled/{f=1} f&&/retireLegacyNotification\(ctx\)/{print "y"; exit} f&&/^    }/{exit}' <<<"$BOOT" | grep -q y \
  && ok "startIfEnabled (app start + boot + package-replaced) retires the legacy notification first" \
  || bad "startIfEnabled does not call retireLegacyNotification"
SVC="$(cat "$LIB/src/main/java/com/diegonmarcos/superapp/onehand/OneHandOverlayService.kt")"
grep -q 'retireLegacyNotification(this)' <<<"$SVC" \
  && ok "service onCreate retires the legacy notification" || bad "service onCreate does not retire it"

echo "== T5: mutations (the scanner must catch each reintroduction)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/edge-silent.XXXXXX")" || { bad "mktemp failed"; WORK=""; }
if [ -n "$WORK" ]; then
  SVCREL=src/main/java/com/diegonmarcos/superapp/onehand/OneHandOverlayService.kt
  CTLREL=src/main/java/com/diegonmarcos/superapp/onehand/OneHandController.kt
  mutate() { # name kind file sed-expr
    rm -rf "$WORK/lib"; mkdir -p "$WORK/lib"; cp -r "$LIB/src" "$WORK/lib/src"
    sed -i "$4" "$WORK/lib/$3"
    if cmp -s "$LIB/$3" "$WORK/lib/$3"; then bad "mutation '$1' changed nothing (sed pattern stale)"; return; fi
    if scan "$WORK/lib" "$2" >/dev/null; then bad "mutation '$1' NOT detected"; else ok "mutation '$1' detected"; fi
  }
  mutate "startForeground in onCreate" fgs "$SVCREL" 's|^\(        instance = this\)$|\1\n        startForeground(7750, android.app.Notification())|'
  mutate "startForegroundService in controller" fgs "$CTLREL" 's|ctx\.startService(i)|ctx.startForegroundService(i)|'
  mutate "FGS type in manifest" fgs src/main/AndroidManifest.xml 's|android:exported="false" />|android:exported="false" android:foregroundServiceType="specialUse" />|'
  mutate "FGS permission in manifest" fgs src/main/AndroidManifest.xml 's|<application>|<uses-permission android:name="android.permission.FOREGROUND_SERVICE" /><application>|'
  mutate "notification builder in service" notif "$SVCREL" 's|^\(        instance = this\)$|\1\n        android.app.Notification.Builder(this, "x").build()|'
  mutate "notify() in service" notif "$SVCREL" 's|^\(        instance = this\)$|\1\n        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(1, null)|'
  mutate "channel created in controller" notif "$CTLREL" 's|nm.cancel(LEGACY_NOTIF_ID)|nm.createNotificationChannel(null)|'
  rm -rf "$WORK/lib"; rmdir "$WORK"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
