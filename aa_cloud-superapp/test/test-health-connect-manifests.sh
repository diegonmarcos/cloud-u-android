#!/usr/bin/env bash
# Tester: Health Connect can actually grant steps to BOTH apps (SuperApp Health badge, Cloud Nav Steps page).
#
# WHY THIS EXISTS. Steps read ZERO while every code path "worked" because Health Connect silently refuses to list
# or grant an app unless its manifest has: (1) the READ_STEPS uses-permission, (2) the legacy rationale filter
# (androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE) on an activity, and (3) on Android 14+ an ACTIVITY-ALIAS
# guarded by android.permission.START_VIEW_PERMISSION_USAGE holding VIEW_PERMISSION_USAGE + category
# HEALTH_PERMISSIONS (the SuperApp had that filter on the activity itself, which Health Connect ignores).
# Cloud Nav also needs ACTIVITY_RECOGNITION for the on-device step-counter fallback. The badge's tap target
# (page:boards/my-pm) must be the Boards > MyHealth page.
#
# Static tester (no device, no build). Each check is then run against planted mutations of the real files; a
# mutation that leaves every check green fails this tester.
set -eu
APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"
export APP ROOT
python3 - <<'PY'
import json, os, re, sys
import xml.etree.ElementTree as ET

APP, ROOT = os.environ["APP"], os.environ["ROOT"]
A = "{http://schemas.android.com/apk/res/android}"
SA_M = f"{APP}/app/src/main/AndroidManifest.xml"
NAV_M = f"{ROOT}/ac_cloud-nav/app/src/main/AndroidManifest.xml"
NAV_BJ = f"{ROOT}/ac_cloud-nav/build.json"
SA_BJ = f"{APP}/build.json"
CLICK = f"{APP}/app/src/main/java/com/diegonmarcos/superapp/notificationcenter/BadgeClick.kt"
rd = lambda p: open(p, encoding="utf-8").read()

RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"
CAT = "android.intent.category.HEALTH_PERMISSIONS"
GUARD = "android.permission.START_VIEW_PERMISSION_USAGE"
STEPS = "android.permission.health.READ_STEPS"

def hc_problems(xml, need_activity_recognition):
    out = []
    try: root = ET.fromstring(xml)
    except ET.ParseError as e: return [f"manifest does not parse: {e}"]
    perms = {e.get(A + "name") for e in root.iter("uses-permission")}
    if STEPS not in perms: out.append("READ_STEPS is not declared")
    if need_activity_recognition and "android.permission.ACTIVITY_RECOGNITION" not in perms:
        out.append("ACTIVITY_RECOGNITION is not declared")
    def filters(node):
        for f in node.findall("intent-filter"):
            yield {a.get(A + "name") for a in f.findall("action")}, {c.get(A + "name") for c in f.findall("category")}
    app = root.find("application")
    acts = [a for a in app.findall("activity") if any(RATIONALE in acs for acs, _ in filters(a))]
    if not acts: out.append("no activity carries the ACTION_SHOW_PERMISSIONS_RATIONALE filter")
    elif acts[0].get(A + "exported") != "true": out.append("the rationale activity is not exported")
    aliases = [a for a in app.findall("activity-alias") if any(USAGE in acs and CAT in cats for acs, cats in filters(a))]
    if not aliases: out.append("no activity-alias carries VIEW_PERMISSION_USAGE + HEALTH_PERMISSIONS")
    else:
        al = aliases[0]
        if al.get(A + "permission") != GUARD: out.append("the usage alias is not guarded by START_VIEW_PERMISSION_USAGE")
        if al.get(A + "exported") != "true": out.append("the usage alias is not exported")
        if not al.get(A + "targetActivity"): out.append("the usage alias has no targetActivity")
    for a in app.findall("activity"):
        if any(USAGE in acs for acs, _ in filters(a)): out.append("VIEW_PERMISSION_USAGE sits on an activity, not an alias")
    return out

def link_problems(nav_bj, sa_bj, click_kt):
    out = []
    # The MyHealth badge opens the Boards page that reads the same data (it no longer routes into cloud-nav).
    want = "page:boards/my-pm"
    m = re.search(r'MYHEALTH\s*=\s*"([^"]+)"', click_kt)
    if not m or m.group(1) != want: out.append("BadgeClick.MYHEALTH is not the Boards > MyHealth page")
    try:
        prod = next(p for p in json.loads(sa_bj)["ui"]["notification_center"]["producers"] if p["id"] == "myhealth")
        if prod.get("click_target") != want: out.append("myhealth click_target is not the Boards > MyHealth page")
    except Exception as e: out.append(f"superapp build.json myhealth: {e}")
    return out

def run(sa_m, nav_m, nav_bj, sa_bj, click_kt):
    return ([f"SuperApp: {p}" for p in hc_problems(sa_m, False)] +
            [f"Nav: {p}" for p in hc_problems(nav_m, True)] + link_problems(nav_bj, sa_bj, click_kt))

REAL = dict(sa_m=rd(SA_M), nav_m=rd(NAV_M), nav_bj=rd(NAV_BJ), sa_bj=rd(SA_BJ), click_kt=rd(CLICK))
fails = 0
def ok(m): print("  PASS:", m)
def bad(m):
    global fails; fails += 1; print("  FAIL:", m)

print("== T1: the real files satisfy every check ==")
probs = run(**REAL)
if probs:
    for p in probs: bad(p)
else: ok("both manifests + the deep link are complete")

print("== T2: mutation-tested - each planted defect must be caught ==")
def sub(key, old, new, count=1):
    def f(d):
        assert old in d[key], f"mutation anchor missing in {key}: {old!r}"
        d = dict(d); d[key] = d[key].replace(old, new, count); return d
    return f
muts = {
    "SuperApp drops READ_STEPS": sub("sa_m", f'<uses-permission android:name="{STEPS}" />', ""),
    "SuperApp drops the rationale filter": sub("sa_m", f'<action android:name="{RATIONALE}" />', '<action android:name="androidx.health.SOMETHING_ELSE" />'),
    "SuperApp drops the usage alias filter": sub("sa_m", USAGE, "android.intent.action.SOMETHING_ELSE"),
    "SuperApp usage alias loses its START_VIEW_PERMISSION_USAGE guard": sub("sa_m", f'android:permission="{GUARD}"', ""),
    "SuperApp usage alias loses the HEALTH_PERMISSIONS category": sub("sa_m", CAT, "android.intent.category.DEFAULT"),
    "SuperApp usage alias is not exported": sub("sa_m", 'android:name="ViewPermissionUsageActivity"\n            android:exported="true"', 'android:name="ViewPermissionUsageActivity"\n            android:exported="false"'),
    "SuperApp puts VIEW_PERMISSION_USAGE back on the activity": sub("sa_m", "<activity-alias", f'<activity android:name=".Zzz"><intent-filter><action android:name="{USAGE}" /></intent-filter></activity>\n        <activity-alias'),
    "Nav drops READ_STEPS": sub("nav_m", f'<uses-permission android:name="{STEPS}" />', ""),
    "Nav drops ACTIVITY_RECOGNITION": sub("nav_m", "android.permission.ACTIVITY_RECOGNITION", "android.permission.CAMERA"),
    "Nav drops the rationale filter": sub("nav_m", RATIONALE, "androidx.health.SOMETHING_ELSE"),
    "Nav drops the usage alias": sub("nav_m", "<activity-alias", "<activity-aliasX"),
    "Nav usage alias loses its guard": sub("nav_m", f'android:permission="{GUARD}"', ""),
    "Badge tap target drifts from the deep link": sub("click_kt", '"page:boards/my-pm"', '"page:boards/msgs"'),
    "SuperApp build.json click_target drifts": sub("sa_bj", '"click_target": "page:boards/my-pm"', '"click_target": "extapp:cloud-nav"'),
}
for name, f in muts.items():
    try: m = f(REAL)
    except AssertionError as e: bad(f"mutation cannot be planted - {e}"); continue
    caught = run(**m)
    if caught: ok(f"caught: {name}")
    else: bad(f"NOT caught: {name}")

print(f"\n{len(muts)} mutations, {fails} failure(s)")
sys.exit(1 if fails else 0)
PY
