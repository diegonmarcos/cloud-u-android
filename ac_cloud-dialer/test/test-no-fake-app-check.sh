#!/usr/bin/env bash
# Tester: Cloud Dialer carries NO "fake / non-original app" check and nothing that sends the
# user to the original app.
#
# THE BUG. commons (org.fossify:commons, a binary dependency we cannot edit) compiles a
# repackaging check into three places: BaseSplashActivity (checkAppSideloading), the sideloading
# helpers in ActivityKt, and - the one that kept surfacing - the Compose theme every commons dialog
# paints with (AppTheme -> FakeVersionCheck -> fakeVersionCheck). On a package that does not start
# with "org.fossify." it pops "you are using a fake version of the app ... get the original".
# Cloud Dialer is an intentional fork (com.diegonmarcos.comms.dialer), so that popup is always a
# false positive. The fix is in three layers and this tester pins each:
#   1. no source reaches the check (SplashActivity is not a BaseSplashActivity, no sideloading
#      helper is called, commons' About/FAQ/Licence/Contributors/Donation screens are not used
#      and are removed from the merged manifest);
#   2. the release build cuts the check out of commons (proguard-rules.pro assumenosideeffects /
#      assumevalues rules), because commons' dialogs still run it;
#   3. nothing user-visible points back to the original: no donate / upgrade / rate-us / store links,
#      and the three commons bools that gate those prompts are pinned in main/res.
# And the GPL attribution stays: the About screen must still credit Fossify Phone as upstream.
#
# MUTATION. A guard only ever seen passing proves nothing, so after the scan passes on the tree this
# tester puts each violation back, one at a time, into a throwaway copy and requires the scan to go
# red on every one of them (and green again on an unmutated copy).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
exec python3 -I - "$APP" <<'PY'
import os, re, shutil, sys, tempfile

APP = sys.argv[1]
KEEP = ["app/src/main", "app/proguard-rules.pro", "app/src/foss", "app/src/gplay", "app/src/core", "app/src/debug"]

# Code tokens that may not appear outside comments.
CODE = {
    "commons sideloading check": r"checkAppSideloading|isAppSideloaded|showSideloadingDialog|AppSideloadedDialog|appSideloadingStatus|SIDELOADING_(TRUE|FALSE|UNCHECKED)",
    "commons fake-version check": r"(?i)fakeVersionCheck",
    "commons splash (runs the check)": r"BaseSplashActivity",
    "commons screens with donate/store links": r"commons\.activities\.(About|Contributors|FAQ|License|Donation)Activity|startAboutActivity|startFAQActivity|startLicenseActivity|startContributorsActivity|startDonationActivity",
    "points to the original app / store": r"launchMoreAppsFromUsIntent|DonateDialog|RateStarsDialog|UpgradeToProDialog|PurchaseThankYouDialog|FeatureLockedDialog|isOrWasThankYouInstalled|addLockedLabelIfNeeded|showDonateOrUpgradeDialog",
    "store / original-app url": r"play\.google\.com/store|f-droid\.org|fossify\.org|org\.fossify\.thankyou|apps\.apple\.com",
    "check dialog string": r"sideloaded_app|fake_version|non_original|not_original",
}
# Resource files get the url / string-name rules only (a layout may legitimately say org.fossify.*).
RES = ("store / original-app url", "check dialog string")

def strip_comments(path, text):
    if path.endswith((".kt", ".java")):
        text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
        text = re.sub(r"//[^\n]*", "", text)
    elif path.endswith((".xml", ".pro")):
        if path.endswith(".xml"):
            text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
        else:
            text = re.sub(r"(?m)^\s*#.*$", "", text)
    return text

def files(root):
    for k in KEEP:
        top = os.path.join(root, k)
        if os.path.isfile(top):
            yield top
        for d, _, fs in os.walk(top):
            for f in sorted(fs):
                if f.endswith((".kt", ".java", ".xml", ".pro")):
                    yield os.path.join(d, f)

def read(root, rel):
    p = os.path.join(root, rel)
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else ""

def scan(root):
    """The violations in the tree at `root` (empty = clean)."""
    bad = []
    for p in files(root):
        rel = os.path.relpath(p, root)
        text = strip_comments(p, open(p, encoding="utf-8", errors="replace").read())
        for name, rx in CODE.items():
            if p.endswith(".xml") and "/res/" in p and name not in RES:
                continue
            if p.endswith(".pro"):
                continue
            if p.endswith("AndroidManifest.xml") and name == "commons screens with donate/store links":
                continue  # the manifest NAMES them, to remove them: checked below
            m = re.search(rx, text)
            if m:
                bad.append("%s: %s (`%s`)" % (rel, name, m.group(0)))
    # 1. SplashActivity is a plain activity that starts MainActivity.
    splash = strip_comments("x.kt", read(root, "app/src/main/kotlin/org/fossify/phone/activities/SplashActivity.kt"))
    if "class SplashActivity" not in splash or "MainActivity" not in splash:
        bad.append("SplashActivity.kt: missing or no longer starts MainActivity")
    # manifest: the commons screens that carry donate / upgrade links are removed from the merge.
    man = strip_comments("m.xml", read(root, "app/src/main/AndroidManifest.xml"))
    for a in ("About", "Contributors", "FAQ", "License", "Donation"):
        if not re.search(r'<activity\s+android:name="org\.fossify\.commons\.activities\.%sActivity"\s+tools:node="remove"\s*/>' % a, man):
            bad.append("AndroidManifest.xml: commons %sActivity is not removed from the merged manifest" % a)
    if 'android:name=".activities.AboutActivity"' not in man:
        bad.append("AndroidManifest.xml: Cloud Dialer's own AboutActivity is not declared")
    # 2. the release build cuts the check out of commons.
    pro = strip_comments("r.pro", read(root, "app/proguard-rules.pro"))
    need = [
        (r"-assumenosideeffects\s+class\s+org\.fossify\.commons\.compose\.extensions\.ActivityExtensionsKt\s*\{[^}]*void\s+fakeVersionCheck\(", "fakeVersionCheck is not cut by an -assumenosideeffects rule"),
        (r"-assumenosideeffects\s+class\s+org\.fossify\.commons\.extensions\.ActivityKt\s*\{[^}]*void\s+showSideloadingDialog\(", "showSideloadingDialog is not cut by an -assumenosideeffects rule"),
        (r"-assumevalues\s+class\s+org\.fossify\.commons\.extensions\.ActivityKt\s*\{[^}]*boolean\s+checkAppSideloading\([^)]*\)\s+return\s+false", "checkAppSideloading is not pinned to false"),
        (r"-assumevalues\s+class\s+org\.fossify\.commons\.extensions\.ActivityKt\s*\{[^}]*boolean\s+isAppSideloaded\([^)]*\)\s+return\s+false", "isAppSideloaded is not pinned to false"),
    ]
    for rx, msg in need:
        if not re.search(rx, pro, flags=re.S):
            bad.append("proguard-rules.pro: " + msg)
    # 3. the prompts' gates are pinned in main/res, and no flavor overrides them.
    bools = read(root, "app/src/main/res/values/bools.xml")
    for name, want in (("show_donate_in_about", "false"), ("hide_google_relations", "true"), ("pretend_thank_you_installed", "true")):
        if not re.search(r'<bool\s+name="%s">\s*%s\s*</bool>' % (name, want), strip_comments("b.xml", bools)):
            bad.append("res/values/bools.xml: %s must be %s" % (name, want))
    for flavor in ("foss", "gplay", "core", "debug"):
        fb = read(root, "app/src/%s/res/values/bools.xml" % flavor)
        if re.search(r'name="(show_donate_in_about|hide_google_relations|pretend_thank_you_installed)"', fb):
            bad.append("src/%s/res/values/bools.xml overrides a prompt gate" % flavor)
    # The GPL attribution stays: About credits Fossify Phone as upstream and names the licence.
    st = read(root, "app/src/main/res/values/strings.xml")
    about = read(root, "app/src/main/kotlin/org/fossify/phone/activities/AboutActivity.kt")
    up = re.search(r'name="about_upstream_body">([^<]*)<', st)
    lic = re.search(r'name="about_license_body">([^<]*)<', st)
    if not (up and "Fossify Phone" in up.group(1)):
        bad.append("strings.xml: the About screen no longer credits Fossify Phone as upstream (GPL attribution)")
    if not (lic and "General Public License" in lic.group(1)):
        bad.append("strings.xml: the About screen no longer names the GPL")
    if "about_upstream_body" not in about or "about_license_body" not in about:
        bad.append("AboutActivity.kt: no longer draws the upstream credit / licence")
    return bad

PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def no(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)

real = scan(APP)
if real:
    for b in real: no(b)
else:
    ok("no fake-app check, no original-app pointers, GPL credit kept")

# --- mutation: each violation, put back into a throwaway copy, must turn the scan red ----------------
def clone():
    tmp = tempfile.mkdtemp(prefix="dialer-fakecheck-")
    for k in KEEP:
        src = os.path.join(APP, k)
        dst = os.path.join(tmp, k)
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        elif os.path.isfile(src):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy(src, dst)
    return tmp

def edit(tmp, rel, fn):
    p = os.path.join(tmp, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    old = open(p, encoding="utf-8").read() if os.path.isfile(p) else ""
    new = fn(old)
    open(p, "w", encoding="utf-8").write(new)
    return old != new

K = "app/src/main/kotlin/org/fossify/phone/"
MUT = [
    ("a call to checkAppSideloading() returns", K + "activities/MainActivity.kt", lambda s: s + "\nfun x(a: android.app.Activity) = org.fossify.commons.extensions.checkAppSideloading(a)\n"),
    ("a call to fakeVersionCheck returns", K + "App.kt", lambda s: s + "\nval y = ::fakeVersionCheck\n"),
    ("SplashActivity extends BaseSplashActivity again", K + "activities/SplashActivity.kt", lambda s: s.replace("class SplashActivity : Activity()", "class SplashActivity : org.fossify.commons.activities.BaseSplashActivity()")),
    ("commons About screen is started again", K + "activities/MainActivity.kt", lambda s: s + "\nfun z(a: org.fossify.commons.activities.SimpleActivityX) { a.startAboutActivity(1, 2, \"v\", arrayListOf(), true) }\n"),
    ("a Play store link comes back", "app/src/main/res/values/strings.xml", lambda s: s.replace("</resources>", '<string name="get_it">https://play.google.com/store/apps/details?id=org.fossify.phone</string></resources>')),
    ("the sideloaded_app string comes back", "app/src/main/res/values/strings.xml", lambda s: s.replace("</resources>", '<string name="sideloaded_app">fake</string></resources>')),
    ("the fakeVersionCheck R8 rule is dropped", "app/proguard-rules.pro", lambda s: s.replace("void fakeVersionCheck(", "void somethingElse(")),
    ("checkAppSideloading is no longer pinned to false", "app/proguard-rules.pro", lambda s: s.replace("boolean checkAppSideloading(android.app.Activity) return false;", "")),
    ("the donate prompt gate is opened", "app/src/main/res/values/bools.xml", lambda s: s.replace('name="hide_google_relations">true', 'name="hide_google_relations">false')),
    ("a flavor re-opens the prompt gate", "app/src/gplay/res/values/bools.xml", lambda s: '<resources><bool name="hide_google_relations">false</bool></resources>'),
    ("commons DonationActivity is no longer removed", "app/src/main/AndroidManifest.xml", lambda s: s.replace('<activity android:name="org.fossify.commons.activities.DonationActivity" tools:node="remove" />', "")),
    ("the thank-you gate comes back", K + "adapters/ContactsAdapter.kt", lambda s: s + "\nfun g(a: android.app.Activity) = org.fossify.commons.dialogs.FeatureLockedDialog(a) { }\n"),
    ("the About screen drops the Fossify credit", "app/src/main/res/values/strings.xml", lambda s: re.sub(r'(name="about_upstream_body">)[^<]*', r"\1Cloud Dialer.", s)),
]
for label, rel, fn in MUT:
    tmp = clone()
    try:
        changed = edit(tmp, rel, fn)
        if not changed:
            no("mutation `%s` changed nothing (the tester's own anchor is stale)" % label)
        elif scan(tmp):
            ok("mutation caught: " + label)
        else:
            no("mutation NOT caught: " + label)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

tmp = clone()
try:
    if scan(tmp):
        no("an unmutated copy does not scan clean (the mutation harness is unsound)")
    else:
        ok("an unmutated copy scans clean")
finally:
    shutil.rmtree(tmp, ignore_errors=True)

print("== RESULT: %d passed, %d failed ==" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
