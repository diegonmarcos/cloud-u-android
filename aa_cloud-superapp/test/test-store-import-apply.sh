#!/usr/bin/env bash
# #570 — the phone's app list, declared in the vault, applied through the Store.
# "our configs json should have our phone/apps list from the ones installed in
# the S21+; cloud-store also manages the installation of upstream official
# APKs; using cloud-account get this list, apply it in cloud-store and download
# all missing phone apps." One grep-contract per promise; the install behaviour
# itself is StorePhoneAppsTest / StoreResolverTest's.
#
# T1 THE VAULT DECLARES IT. cloud-vault C_A1-configs: `apps` is the LAST schema.json
#    section, apps/sources.json points apps.devices.galaxy.inventory at
#    apps/galaxy-apps.json with a cloud-vault json resolver, and that file is a
#    #565 inventory (kind cloud-sa.app-inventory, schema 1, every row carrying the
#    six AppInventory keys). Nothing in it is a pending marker any more. Skipped
#    (UNVERIFIABLE) when cloud-vault is not checked out beside this repo.
# T2 ACCOUNT → STORE. Fleet ▸ Apps carries "Apply list to Store": it serialises
#    VaultCockpit.appsDeclared through the ONE inventory writer (AppInventory.toJson)
#    into StoreImport.pending and opens the declared route; the Phone page consumes
#    it on resume through the same import path a picked file takes; both Store hosts
#    (Cloud Store's OPEN Intent, SuperApp's handoff) carry it across processes under
#    StoreImport.EXTRA_IMPORT. Labels are R.strings in every locale.
# T3 INSTALL ALL MISSING walks the plan through BatchInstall (fleet rung for
#    plan.ours, the resolver ladder for plan.direct) — plan.store and plan.manual
#    are never targets (Play-only stays a hand-off) — and says how many it skipped.
# T4 NEVER UNINSTALLS, ALWAYS IDEMPOTENT: no delete/uninstall intent or
#    PackageInstaller.uninstall anywhere on the import path; the plan is diffed
#    against the installed set so a second apply has nothing to do.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIB="$ROOT/ab_cloud-libs-shared/libs"
VAULT=""
for v in "${CLOUD_VAULT:+$CLOUD_VAULT/C_A1-configs}" "$ROOT/../cloud-vault/C_A1-configs" "$ROOT/../cloud-me_vault/C_A1-configs"; do
  [ -n "$v" ] && [ -f "$v/schema.json" ] && VAULT="$v" && break
done

python3 - "$APP" "$LIB" "$ROOT" "$VAULT" <<'PY'
import json, re, sys, os
app, lib, root, vault = sys.argv[1:]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def rd(p): return open(p, encoding="utf-8").read()

print("== T1: the vault declares the S21+ app list ==")
if not vault:
    print("  UNVERIFIABLE: cloud-vault is not checked out beside this repo (set CLOUD_VAULT)")
else:
    sch = json.load(open(os.path.join(vault, "schema.json")))
    ids = [s["id"] for s in sch["sections"]]
    (ok if ids and ids[-1] == "apps" else bad)("schema.json lists `apps` as the LAST section (%s)" % ids[-1:])
    src = json.load(open(os.path.join(vault, "apps", "sources.json")))["items"]
    inv = src.get("devices", {}).get("galaxy", {}).get("inventory", {})
    (ok if inv.get("kind") == "json" and inv.get("repo") == "cloud-vault" and inv.get("path") == "C_A1-configs/apps/galaxy-apps.json" and inv.get("pointer") == []
     else bad)("apps.devices.galaxy.inventory is a cloud-vault json resolver at C_A1-configs/apps/galaxy-apps.json")
    def pend(v):
        if isinstance(v, dict): return v.get("kind") == "pending" or any(pend(x) for x in v.values())
        if isinstance(v, list): return any(pend(x) for x in v)
        return False
    (bad if pend(src["devices"]["galaxy"]) else ok)("the galaxy device carries no pending marker")
    g = json.load(open(os.path.join(vault, "apps", "galaxy-apps.json")))
    keys = {"package", "version_name", "version_code", "origin_store", "ours", "category"}
    rows = g.get("apps", [])
    (ok if g.get("kind") == "cloud-sa.app-inventory" and g.get("schema") == 1 and rows and all(keys <= set(r) for r in rows)
     else bad)("galaxy-apps.json is a schema-1 cloud-sa.app-inventory, %d rows with the six AppInventory keys" % len(rows))
    (ok if any(r["ours"] for r in rows) and any(not r["ours"] for r in rows) else bad)("it holds both fleet (ours) and foreign apps")
    bj = json.load(open(os.path.join(app, "build.json")))
    sec = next(s for s in bj["ui"]["profile"]["infos"]["schema"]["sections"] if s["id"] == "apps")
    (ok if "devices › galaxy › inventory" in sec["fields"] and not sec.get("staged") else bad)("build.json's apps section names devices › galaxy › inventory and is no longer staged")

print("== T2: Account → Store hand-off ==")
tabs = rd(os.path.join(lib, "account/src/main/java/com/diegonmarcos/superapp/profile/AccountTabs.kt"))
m = re.search(r"StoreImport\.pending\s*=\s*\n?\s*com\.diegonmarcos\.superapp\.appstore\.AppInventory\.toJson\(VaultCockpit\.appsDeclared\(bundle, id, fleet\)\)\s*\n\s*openStore\(route\)", tabs)
(ok if m else bad)("Fleet ▸ Apps: StoreImport.pending = AppInventory.toJson(appsDeclared(device)) then openStore(route)")
(ok if "R.string.infos_apps_apply_store" in tabs else bad)("the button label is R.string.infos_apps_apply_store")
for loc in ("values", "values-es"):
    s = rd(os.path.join(lib, "account/src/main/res", loc, "strings.xml"))
    (ok if 'name="infos_apps_apply_store"' in s else bad)("infos_apps_apply_store exists in %s" % loc)
imp = rd(os.path.join(lib, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreImport.kt"))
(ok if re.search(r"fun takePending\(\): String\? = pending\.also \{ pending = null \}", imp) else bad)("StoreImport.takePending reads the hand-off ONCE")
phone = rd(os.path.join(lib, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/StorePhoneFragment.kt"))
(ok if re.search(r"override fun onResume\(\) \{[^}]*StoreImport\.takePending\(\)\?\.let \{ importText\(it\) \}", phone, re.S) else bad)("the Phone page consumes it on resume through importText")
(ok if re.search(r"private fun importFrom\(uri: Uri\)[\s\S]*?importText\(it\)", phone) and "AppInventory.plan(wanted" in phone.split("private fun importText")[1] else bad)("a picked file takes the SAME importText → AppInventory.plan → StoreImport.show path")
store = rd(os.path.join(root, "ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt"))
acct = rd(os.path.join(root, "ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt"))
hand = rd(os.path.join(app, "app/src/main/java/com/diegonmarcos/superapp/apps/CloudStoreHandoff.kt"))
(ok if "getStringExtra(StoreImport.EXTRA_IMPORT)?.let { StoreImport.pending = it }" in store else bad)("Cloud Store reads StoreImport.EXTRA_IMPORT off its OPEN Intent into StoreImport.pending")
(ok if "putExtra(StoreImport.EXTRA_IMPORT, StoreImport.takePending())" in acct else bad)("Cloud Account's open() carries the hand-off on the OPEN Intent")
(ok if "StoreImport.EXTRA_IMPORT, com.diegonmarcos.superapp.appstore.StoreImport.takePending()" in hand else bad)("SuperApp's CloudStoreHandoff.open carries it too")

print("== T3: Install all missing = the resolver ladder, Play-only skipped ==")
body = imp.split("fun installMissing")[1] if "fun installMissing" in imp else ""
(ok if body else bad)("StoreImport.installMissing exists")
(ok if "BatchInstall.run(" in body and "BatchInstall.engine(cfg)" in body else bad)("it runs ONE BatchInstall pass with the real engine (FleetInstall / ExternalInstall ladder)")
(ok if "plan.ours" in body and "plan.direct" in body and "plan.store" not in body and "plan.manual" not in body else bad)("targets are plan.ours + plan.direct only — plan.store / plan.manual (Play-only) are never targets")
(ok if re.search(r"plan\.direct\.map \{ e -> SourceResolver\.resolve\(cfg, e\.pkg\)", body) else bad)("external targets come from SourceResolver.resolve (vendor → F-Droid → Play ladder)")
(ok if "plan.store.size + plan.manual.size" in imp and "store_import_install_missing" in imp else bad)("the button counts the skipped need-Play apps")
s = rd(os.path.join(lib, "appstore/src/main/res/values/strings.xml"))
(ok if 'name="store_import_install_missing"' in s else bad)("store_import_install_missing exists")
ext = rd(os.path.join(lib, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/ExternalInstall.kt"))
(ok if "if (app.direct.isEmpty())" in ext and "store_phone_why_play_only" in ext else bad)("ExternalInstall still refuses a Play-only ladder with the needs-Play message")

print("== T4: never uninstalls, idempotent ==")
forbidden = re.compile(r"ACTION_DELETE|ACTION_UNINSTALL_PACKAGE|\.uninstall\(|deletePackage|DELETE_PACKAGES")
for name, text in (("StoreImport", imp), ("StorePhoneFragment", phone), ("AccountTabs", tabs),
                   ("BatchInstall", rd(os.path.join(lib, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/BatchInstall.kt"))),
                   ("ExternalInstall", ext), ("FleetInstall", rd(os.path.join(lib, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/FleetInstall.kt"))),
                   ("AppInventory", rd(os.path.join(lib, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/AppInventory.kt")))):
    (bad if forbidden.search(text) else ok)("%s removes nothing" % name)
inv = rd(os.path.join(lib, "appstore/src/main/java/com/diegonmarcos/superapp/appstore/AppInventory.kt"))
(ok if "e.pkg in installed -> have += e" in inv else bad)("the plan diffs against the installed set first — a second apply finds nothing to install")

# mutation: the T3 target check must go RED when plan.store becomes a target
mut = body.replace("plan.ours", "plan.store")
(ok if "plan.store" in mut and "plan.store" not in body else bad)("T3-mutation: a plan.store target would be caught")
print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
