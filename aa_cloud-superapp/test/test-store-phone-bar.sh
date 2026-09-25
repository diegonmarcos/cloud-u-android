#!/usr/bin/env bash
# #565 — Store ▸ Phone Apps: one shared top bar, export/import, i18n'd, and one
# installer→store map. The rendered bar, the exported JSON and the import plan
# are asserted on resolved values by app/src/test/.../apps/StorePhoneAppsTest.kt;
# this tester holds the source-level contracts that a Robolectric run cannot see.
#
# T1 ONE BAR (#228). The bar's device controls live in exactly one file of the
#    store library and both Store fragments draw it. A second copy of any of
#    them is the drift the brief forbids.
# T2 i18n. Every R.string the store library names exists in its values/
#    (English is the fleet base, #298/#299), and the new screens draw no
#    hardcoded user text.
# T3 ONE MAP (#564 owns it: assets/appstore-install-sources.json). Export and
#    import CONSUME it through PhoneAppActions — the same installer read, the
#    same ours rule, the same store lookup the row buttons use — and no other
#    file in the store declares installers or deeplinks.
# T4 EXPORT/IMPORT go through the system document pickers, and the only installs
#    the import can start are the fleet's own path and (#571) the resolver's
#    declared direct ladder — never a foreign store's app through a store.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIB="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main"

python3 - "$APP" "$LIB" <<'PY'
import json, os, re, sys, glob
app, lib = sys.argv[1:]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())

kt_dir = os.path.join(lib, "java/com/diegonmarcos/superapp/appstore")
kt = {os.path.basename(p): code(p) for p in glob.glob(kt_dir + "/*.kt")}
if not {"StoreBar.kt", "StoreCloudFragment.kt", "StorePhoneFragment.kt"} <= kt.keys():
    print("FATAL: store sources missing"); sys.exit(2)

print("== T1: one Store bar, drawn by both tabs ==")
for frag in ("StoreCloudFragment.kt", "StorePhoneFragment.kt"):
    if re.search(r"StoreBar\.render\(this\b", kt[frag]): ok(frag + " draws StoreBar")
    else: bad(frag + " does not draw the shared StoreBar")
for needle in ("AutoUpdatePrefs.setEnabled(", "AutoUpdatePrefs.setRequireUnmetered(",
               "PackageVerifier.setScanning(", "WirelessDebugging.set(", "ACTION_APPLICATION_DEVELOPMENT_SETTINGS"):
    owners = sorted(f for f, t in kt.items() if needle in t)
    if owners == ["StoreBar.kt"]: ok(needle + " lives only in StoreBar")
    else: bad(needle + " is in %s — a second copy of the bar" % owners)
phone = kt["StorePhoneFragment.kt"]
m = re.search(r"StoreBar\.Verbs\((.*?)\)\)", phone, re.S)
# #571: the verbs are REAL on Phone Apps now — this store installs fleet apps
# through the release path and external apps through their declared ladder —
# so the bar must receive both lambdas and no disabled reason (test-store-resolver.sh
# owns the rest of that contract).
if m and re.search(r"installAll\s*=\s*\{", m.group(1)) and re.search(r"updateAll\s*=\s*\{", m.group(1)) \
        and not re.search(r"disabledReason", m.group(1)):
    ok("Phone Apps passes real Install all / Update all verbs through the shared bar")
else: bad("Phone Apps disables or omits the batch verbs the #571 resolver makes real")

print("== T2: every store string is a resource ==")
en = os.path.join(lib, "res/values/strings.xml")
en_k = set(re.findall(r'<string name="([^"]+)"', read(en))) if os.path.exists(en) else set()
used = set()
for t in kt.values(): used |= set(re.findall(r"R\.string\.(\w+)", t))
if used and used <= en_k: ok("%d R.string refs all declared in values/" % len(used))
else: bad("R.string refs missing from values/: %s" % sorted(used - en_k))
lit = [(f, m) for f in ("StoreBar.kt", "StoreImport.kt")
       for m in re.findall(r'(?:Toast\.makeText\([^,]+,|setTitle\(|text\(ctx,)\s*"([^"]+)"', kt.get(f, ""))]
if not lit: ok("the bar and the import screen draw no hardcoded user text")
else: bad("hardcoded user text: %s" % lit[:4])

print("== T3: export/import consume the one installer->store map ==")
inv, imp_ = kt.get("AppInventory.kt", ""), kt.get("StoreImport.kt", "")
for needle, what in (("PhoneAppActions.installerOf(ctx, pkg)", "origin_store is the row buttons' own installer read"),
                     ("PhoneAppActions.isOurs(", "ours is the row buttons' own rule"),
                     ("PhoneAppActions.storePage(sources, e.origin, e.pkg)", "the import's store link is the map's own lookup"),
                     ("PhoneAppActions.fleetByPackage(", "fleet membership comes from the manifest, alt ids included")):
    if needle in inv: ok(what)
    else: bad("AppInventory: " + what + " - missing " + needle)
if "PhoneAppActions.sources(app)" in phone: ok("the import reads the map shipped in assets")
else: bad("the import does not read PhoneAppActions.sources")
src = json.loads(read(os.path.join(lib, "assets/appstore-install-sources.json")))
installers = sorted(src["sources"])
dup = ["%s: %s" % (f, k) for f, t in kt.items() for k in installers if '"%s"' % k in t]
dup += [f for f, t in kt.items() if '"deeplink"' in t and f != "PhoneAppActions.kt"]
maps = [os.path.basename(p) for p in glob.glob(os.path.join(lib, "assets/*.json"))]
if installers and not dup and maps == ["appstore-install-sources.json"]:
    ok("one map (%s), read only by PhoneAppActions" % ", ".join(installers))
else: bad("a second installer map or a hardcoded installer: %s %s" % (dup, maps))

print("== T4: export/import use the system pickers; import installs only the fleet ==")
if "ActivityResultContracts.CreateDocument(" in phone and "ActivityResultContracts.OpenDocument()" in phone:
    ok("export = ACTION_CREATE_DOCUMENT, import = ACTION_OPEN_DOCUMENT")
else: bad("export/import do not go through the document pickers")
imp = kt.get("StoreImport.kt", "")
installs = re.findall(r"Fleet\.install\w*\(|ExternalInstall\.run\(|PackageInstaller|ACTION_INSTALL_PACKAGE|ACTION_DELETE", imp)
# #571: two installs and only two — the fleet path over plan.ours, and the
# resolver's ExternalInstall over plan.direct. Nothing installs a plan.store app.
if sorted(installs) == ["ExternalInstall.run(", "Fleet.installAll("] and "plan.ours" in imp and "plan.direct" in imp:
    ok("the import installs only through Fleet.installAll (plan.ours) and ExternalInstall (plan.direct)")
else: bad("the import reaches install paths %s" % installs)

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
