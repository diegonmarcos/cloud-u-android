#!/usr/bin/env bash
# #563 — the Store: ONE declaration of its identity, and a guard that tells the
# display word "Constellation" apart from the identity it no longer is.
#
# WHAT CHANGED. Configs ▸ Constellation was an action page (action:constellation)
# with a hand-written branch in ShellActivity, three copies of its label in
# build.json and a "Constellation AppStore" heading inside the fragment. It is
# now Configs ▸ Store, an ordinary tabbed page whose two tabs are Cloud
# Constellation (the fleet) and Phone Apps (every APK on the phone, #564).
#
# WHY A GUARD AND NOT A GREP FOR THE NEW NAME. The subpage is DELIBERATELY
# titled "Cloud Constellation", so "the word is gone" is the wrong test — it
# would either fail on the title the owner asked for or be loosened until it
# caught nothing. The line this holds is identity vs display: nothing that
# ROUTES (a page id, a tab id, a target, a Kotlin route literal, a class) says
# constellation, and the word survives only in a display label.
#
# NOTHING HERE IS WRITTEN TWICE. The store's tab ids are READ from the code that
# routes them (SectionPages: which page id builds StoreCloudFragment /
# StorePhoneFragment), its owner page and title from build.json, and every
# consumer is checked against those derived values. The only literals are the
# requirement itself: the page is titled "Store" and its fleet tab keeps the
# word Constellation.
#
# The fleet's own names — constellation-fleet.json, CONSTELLATION_DATA,
# ConstellationWorker, the notification channel id — are the FLEET's identity,
# not the store's, and are out of scope on purpose: renaming a signature
# permission or a persisted WorkManager class would break installed devices.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIBS="$ROOT/ab_cloud-libs-shared/libs"

python3 - "$APP/build.json" \
    "$APP/app/src/main/java" "$LIBS/appstore/src/main/java" "$LIBS/net/src/main/java" <<'PY'
import json, os, re, sys

build_path, *src_dirs = sys.argv[1:]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)

def read(p):
    with open(p, encoding="utf-8") as f: return f.read()

kt = {}
for d in src_dirs:
    for dp, _, fs in os.walk(d):
        for f in fs:
            if f.endswith(".kt"): kt[os.path.join(dp, f)] = read(os.path.join(dp, f))
if not kt: print("FATAL: no Kotlin sources under", src_dirs); sys.exit(2)

def code_lines(text):
    """Non-comment lines, with // tails and /* */ blocks removed — a comment
    ABOUT the old name is history, not a route."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return [re.sub(r"(?<![:\"])//.*$", "", l) for l in text.splitlines()]

def src(suffix):
    hits = [t for p, t in kt.items() if p.endswith(suffix)]
    if len(hits) != 1: print("FATAL: expected one", suffix, "got", len(hits)); sys.exit(2)
    return hits[0]

build = json.loads(read(build_path))
sections = build["ui"]["sections"]

print("== T1: the store's tab ids are whatever SectionPages routes to its two fragments ==")
routes = src("/launcher/SectionPages.kt")
def routed(cls):
    return re.findall(r'sectionId == "([^"]+)"\s*&&\s*pageId == "([^"]+)"\s*->\s*[\w.]*\b' + cls + r"\(\)", routes)
cloud, phone = routed("StoreCloudFragment"), routed("StorePhoneFragment")
if len(cloud) == 1 and len(phone) == 1 and cloud[0][0] == phone[0][0]:
    ok("one route each: %s/%s -> StoreCloudFragment, %s/%s -> StorePhoneFragment" % (cloud[0] + phone[0]))
else:
    bad("expected exactly one route to each store fragment in one section, got cloud=%s phone=%s" % (cloud, phone))
    print("RESULT: %d passed, %d failed" % (PASS, FAIL)); sys.exit(1)
SEC, CLOUD_TAB = cloud[0]; PHONE_TAB = phone[0][1]

print("== T2: ONE owner page declares both tabs — that entry IS the declaration ==")
sec = next((s for s in sections if s.get("id") == SEC), None)
pages = (sec or {}).get("pages", [])
owners = [p for p in pages if CLOUD_TAB in p.get("tabs", []) and PHONE_TAB in p.get("tabs", [])]
if len(owners) == 1: ok("%s/%s owns tabs %s" % (SEC, owners[0]["id"], owners[0]["tabs"]))
else: bad("expected one page in %s declaring tabs [%s, %s], got %d" % (SEC, CLOUD_TAB, PHONE_TAB, len(owners)))
owner = owners[0] if owners else {}
STORE_ID, STORE_LABEL = owner.get("id", ""), owner.get("label", "")
if owner.get("tabs", [None])[0] == CLOUD_TAB: ok("the fleet tab (%s) is the one the page lands on" % CLOUD_TAB)
else: bad("the fleet tab %s is not the first tab — the page would open on %s" % (CLOUD_TAB, owner.get("tabs")))
tab = {p["id"]: p for p in pages if p.get("id") in (CLOUD_TAB, PHONE_TAB)}
for t in (CLOUD_TAB, PHONE_TAB):
    if t in tab and tab[t].get("hidden") is True: ok("tab %s is declared, hidden from the Configs grid" % t)
    else: bad("tab %s is not a declared hidden page of %s" % (t, SEC))

print("== T3: display title vs identity ==")
if STORE_LABEL == "Store": ok('the page is titled "Store"')
else: bad('the store page is titled %r, not "Store"' % STORE_LABEL)
cloud_label = tab.get(CLOUD_TAB, {}).get("label", "")
if "Constellation" in cloud_label: ok("the fleet tab keeps the display word: %r" % cloud_label)
else: bad("the fleet tab lost its deliberate title — label is %r" % cloud_label)
if tab.get(PHONE_TAB, {}).get("label"): ok("the phone tab has a title: %r" % tab[PHONE_TAB]["label"])
else: bad("the phone tab has no label")
for ident in (STORE_ID, CLOUD_TAB, PHONE_TAB):
    if "constellation" in ident.lower(): bad("identity %r carries the old name" % ident)
    else: ok("identity %r is free of the old name" % ident)

print("== T4: nothing in build.json ROUTES by the old name (doc keys are history) ==")
OLD_TARGET = re.compile(r"^(action:constellation|page:[^/]+/constellation)(#.*)?$")
stale = []
def walk(o, path):
    if isinstance(o, dict):
        for k, v in o.items():
            if k.startswith("_"): continue
            if k in ("id", "tabs") and (v == "constellation" or (isinstance(v, list) and "constellation" in v)):
                stale.append(path + "." + k)
            walk(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o): walk(v, "%s[%d]" % (path, i))
    elif isinstance(o, str) and OLD_TARGET.match(o):
        stale.append(path + " = " + o)
walk(build["ui"], "ui")
if not stale: ok("no page/tab id or target routes to the old store")
else: bad("build.json still routes by the old name: " + "; ".join(stale[:8]))

print("== T5: every tile/page that routes INTO the store carries the store's title ==")
into = {"page:%s/%s" % (SEC, x) for x in (STORE_ID, CLOUD_TAB, PHONE_TAB)}
entries = []
def collect(o, path):
    if isinstance(o, dict):
        t = o.get("target") or o.get("action")
        if isinstance(t, str) and t.split("#")[0] in into and "label" in o: entries.append((path, o["label"]))
        for k, v in o.items():
            if not k.startswith("_"): collect(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o): collect(v, "%s[%d]" % (path, i))
collect(build["ui"], "ui")
if entries: ok("%d way(s) in declared: %s" % (len(entries), ", ".join(p for p, _ in entries)))
else: bad("no tile or page routes into the store — it is unreachable")
drift = [(p, l) for p, l in entries if l != STORE_LABEL]
if not drift: ok('every way in is labelled %r, the declaration\'s own title' % STORE_LABEL)
else: bad("label drifted from the declaration (%r): %s" % (STORE_LABEL, drift))

print("== T6: Kotlin routes use the DERIVED tab, and none uses the old one ==")
fleet_target = "page:%s/%s" % (SEC, CLOUD_TAB)
for suffix, needle, what in (
        ("/superapp/App.kt", '"shortcut_action" to "%s"' % fleet_target, "the update notification deep-links to the fleet tab"),
        ("/superapp/ShellActivity.kt", 'routeTarget("%s")' % fleet_target, "Update-All opens the fleet tab"),
        ("/apps/PhoneAppsFragment.kt", 'openSectionPage("%s", "%s"' % (SEC, CLOUD_TAB), "a lib tile opens the fleet tab")):
    if needle in "\n".join(code_lines(src(suffix))): ok(what)
    else: bad(what + " — expected %s in %s" % (needle, suffix))
OLD_CODE = re.compile(r'action:constellation|"constellation"\)|== "constellation"|ConstellationFragment')
old_hits = []
for p, t in kt.items():
    for n, line in enumerate(code_lines(t), 1):
        if OLD_CODE.search(line) and "CHANNEL" not in line: old_hits.append("%s:%d" % (os.path.relpath(p, os.path.dirname(build_path)), n))
if not old_hits: ok("no Kotlin route, class or dispatch names the old store")
else: bad("old store identity still in code: " + ", ".join(old_hits[:8]))

print('== T7: in Kotlin strings "Constellation" is only ever the fleet tab\'s title ==')
# A string that says Constellation anywhere else is a user-visible pointer to a
# page that no longer exists under that name ("Configs → Constellation", the
# old "Constellation AppStore" heading).
title_word = re.compile(r"(?<!Cloud )Constellation")
loose = []
for p, t in kt.items():
    for n, line in enumerate(code_lines(t), 1):
        for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
            if title_word.search(lit): loose.append("%s:%d %r" % (os.path.basename(p), n, lit[:60]))
if not loose: ok('every "Constellation" in a Kotlin string reads "Cloud Constellation"')
else: bad("stale store name in user-visible strings: " + "; ".join(loose[:6]))

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
