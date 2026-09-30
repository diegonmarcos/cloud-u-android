#!/usr/bin/env bash
# #563 — both Store tabs group by the CENTRAL classification, Cloud ▸ Apps gets
# the All Apps section Phone ▸ Apps has, and the store's carry-over features
# (#496 #334 #140 #88 #274/#99) survived the move.
#
# NO SECOND MAPPING TABLE. #170, #102 and #405 each deleted a second copy of a
# package→group mapping. So T1 derives every section title and folder label
# from build.json (ui.phone_sections / ui.phone_folders) and fails if ANY of
# them is written into the store library or into the host adapter — the only
# way a shelf name may reach the store is by being read from the taxonomy.
#
# The Kotlin classification itself (what StoreShelves.of RETURNS for cloud-drive
# and WhatsApp) is asserted on the resolved value by the Robolectric test
# app/src/test/.../apps/StoreShelvesTest.kt; T3 here only pins the DATA the
# brief's two examples rest on, so a build.json edit that moves them is caught
# before a build.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
STORE="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"

python3 - "$APP/build.json" "$STORE" "$KT" <<'PY'
import json, os, re, sys
build_path, store_dir, kt_dir = sys.argv[1:]
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

ui = json.loads(read(build_path))["ui"]
sections = [s for s in ui["phone_sections"] if isinstance(s, dict)]
folders = [f for f in ui["phone_folders"] if isinstance(f, dict)]
store_files = {f: code(os.path.join(store_dir, f)) for f in os.listdir(store_dir) if f.endswith(".kt")}
cloud = store_files.get("StoreCloudFragment.kt", "")
phone = store_files.get("StorePhoneFragment.kt", "")
shelves_kt = code(os.path.join(kt_dir, "apps/StoreShelves.kt"))
if not (cloud and phone and shelves_kt): print("FATAL: store sources missing"); sys.exit(2)

print("== T1: the store holds NO taxonomy of its own ==")
names = sorted({s["title"] for s in sections} | {f["label"] for f in folders}
               | {f["label"].lstrip("_-+.=@>*") for f in folders if f["label"][:1] in "_-+.=@>*"} - {""})
literals = []
for fname, text in list(store_files.items()) + [("StoreShelves.kt", shelves_kt)]:
    for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', text):
        if lit in names: literals.append("%s: %r" % (fname, lit))
if not literals: ok("none of the %d taxonomy names is written in the store or its adapter" % len(names))
else: bad("a taxonomy name is hardcoded (a second mapping): " + "; ".join(literals[:6]))
leak = [f for f, t in store_files.items() if re.search(r"phone_folders|phone_sections|match_keywords", t)]
if not leak: ok("the store library reads no taxonomy data of its own")
else: bad("the store library reads taxonomy data directly: %s" % leak)

print("== T2: both tabs group through ONE host classifier, and it is the central one ==")
for name, text in (("StoreCloudFragment", cloud), ("StorePhoneFragment", phone)):
    if "AppStoreHost.classify(" in text: ok(name + " groups by AppStoreHost.classify")
    else: bad(name + " does not ask the host classifier — it is grouping by something else")
app_kt = code(os.path.join(kt_dir, "App.kt"))
if re.search(r"\bclassify\s*=\s*com\.diegonmarcos\.superapp\.apps\.StoreShelves::of\b", app_kt):
    ok("the host wires classify to StoreShelves.of")
else: bad("App.kt does not wire AppStoreHost.classify — both tabs would render flat")
for needle, what in (("PhoneTaxonomy.folderIdOf(", "folder comes from PhoneTaxonomy"),
                     ("PhoneSections.loadFromBuildConfig()", "section comes from ui.phone_sections"),
                     ("folder.label.startsWith(it.prefix)", "folder joins its section by the All Apps prefix rule")):
    if needle in shelves_kt: ok(what)
    else: bad("StoreShelves: " + what + " — missing " + needle)

print("== T3: the brief's two examples, on the data ==")
def section_of(folder):
    return next((s["title"] for s in sections if folder["label"].startswith(s["prefix"])), None)
drive = next((e for e in ui["external_apps"] if e.get("id") == "cloud-drive"), None)
f = next((x for x in folders if drive and x["id"] == drive.get("folder")), None)
if f and "Data Apps" in (section_of(f) or ""): ok("cloud-drive -> %s / %s" % (section_of(f), f["label"]))
else: bad("cloud-drive does not classify into a Data Apps section (folder=%s)" % (f and f["label"]))
wa = [x for x in sorted(folders, key=lambda x: (x.get("order", "99"), x["id"]))
      if "pkg:com.whatsapp" in [k.lower() for k in x.get("match_keywords", [])]]
if wa and "Inboxes & AI" in (section_of(wa[0]) or "") and wa[0]["label"].endswith("Chat"):
    ok("WhatsApp -> %s / %s" % (section_of(wa[0]), wa[0]["label"]))
else: bad("WhatsApp is not Tools · Inboxes & AI / Chat (got %s)" % (wa and wa[0]["label"]))

print("== T4: Cloud ▸ Apps has Phone ▸ Apps' All Apps, mirrored over ONE package set ==")
grouped = code(os.path.join(kt_dir, "launcher/GroupedTilesFragment.kt"))
suite = code(os.path.join(kt_dir, "apps/SuitePhoneAppsFragment.kt"))
phone_frag = code(os.path.join(kt_dir, "apps/PhoneAppsFragment.kt"))
cloud_call = re.search(r"PhoneAppsFragment\.renderAllApps\(ctx,\s*col,\s*only\s*=\s*Sections\.constellationPackages\(ctx\.packageName\)\)", grouped)
# The below-the-fold block is the LAST `if (sectionId == "cloud") {` — the
# only one that is not the Quickmarks/Actions header work.
cloud_branch = grouped.rfind('if (sectionId == "cloud") {')
if cloud_call and cloud_branch != -1 and cloud_call.start() > cloud_branch:
    ok("Cloud ▸ Apps renders All Apps ONLY over the fleet's packages, in the cloud branch")
else: bad("Cloud ▸ Apps does not render the All Apps grid over Sections.constellationPackages")
if re.search(r"val ourApps = Sections\.constellationPackages\(ctx\.packageName\)", suite) and \
   "PhoneAppsFragment.renderAllApps(ctx, root, ourApps)" in suite:
    ok("Phone ▸ Apps excludes that same set — the two grids partition one list")
else: bad("Phone ▸ Apps no longer excludes Sections.constellationPackages — the mirror is broken")
if "(only == null || it.packageName in only)" in phone_frag and "exclude.isEmpty() && only == null" in phone_frag:
    ok("renderAllApps honours `only` and never caches a filtered grouping")
else: bad("renderAllApps ignores `only`, or writes a filtered result into the shared cache")

print("== T5: carry-over survived the move into the Cloud tab ==")
for needle, what in (
        ("ApkDetailSheet.show(requireActivity(), app, states[app.id])", "#496 detail sheet"),
        ("Fleet.downgradePolicy = Fleet.DowngradePolicy { app, candidateCode, installedCode ->", "#496 downgrade confirm armed while visible"),
        ("Fleet.downgradePolicy = Fleet.DowngradePolicy { _, _, _ -> false }", "#496 refuse-by-default restored on leave"),
        ("{ it.label.lowercase() }", "#334 A-Z within a run"),
        ("Updater.cancelNow(requireContext())", "#140 Cancel"),
        ("UpdateProgress.addObserver(progressObserver)", "#88 live progress"),
        ("StoreBar.render(this, headerControls", "#565 the Cloud tab draws the shared Store bar")):
    if needle in cloud: ok(what)
    else: bad(what + " — missing from StoreCloudFragment")
# #565: the header moved into the ONE bar both Store tabs draw.
bar = store_files.get("StoreBar.kt", "")
for needle, what in (
        ("AutoUpdatePrefs.setEnabled(ctx, !autoOn)", "#274/#99 auto-update toggle"),
        ("ConstellationWorker.start(ctx)", "#274/#99 auto-update reschedules the fleet worker")):
    if needle in bar: ok(what)
    else: bad(what + " — missing from StoreBar")

print("== T6: Phone Apps buttons come from ONE capability derivation and ONE install path (#564) ==")
# #564 replaced #563's Open + App info with Update | Open | Stop | Remove |
# App info | {origin store}. WHICH of them work is decided on the device and
# asserted on the resolved value by app/src/test/.../apps/StorePhoneActionsTest.kt;
# this pins the wiring that test cannot see from inside one call.
if re.search(r'btn\(ctx, "', phone): bad("Phone Apps hardcodes a button label - its buttons must come from PhoneAppActions.of")
elif "PhoneAppActions.of(" in phone: ok("Phone Apps draws each row from PhoneAppActions.of")
else: bad("Phone Apps does not draw its buttons from PhoneAppActions.of")
# #625 split Fleet.install into Fleet.download + Fleet.commit so a batch can
# fetch everything before installing anything. The one-path rule did not change,
# only the two calls it is made of: FleetInstall.kt is still the only file in the
# store that reaches the fleet ENGINE, and BatchInstall composes FleetInstall's
# two halves rather than calling Fleet itself.
installers = sorted(f for f, t in store_files.items() if re.search(r"\bFleet\.commit\(", t))
if installers == ["ExternalInstall.kt", "FleetInstall.kt"]:
    ok("the installer is entered from FleetInstall.kt (fleet) and ExternalInstall.kt (#571 ladder) alone")
else: bad("Fleet.commit is called from %s - the store has more than one install path" % installers)
for name, text in (("StoreCloudFragment", cloud), ("StorePhoneFragment", phone)):
    # #571 moved Phone Apps' call into installOne(app, r) -> FleetInstall.run(app, fleetApp):
    # the argument names changed, the one path did not. Match the call, not its locals.
    if re.search(r"\bFleetInstall\.run\(", text): ok(name + " updates through FleetInstall.run")
    else: bad(name + " does not update through FleetInstall.run")
assets = os.path.join(store_dir, "../../../../../assets/appstore-install-sources.json")
src = json.loads(read(assets))
keys = sorted(src["sources"])
if not keys: bad("the install-source map declares no store")
named = ["%s: %s" % (f, k) for f, t in store_files.items() for k in keys if '"%s"' % k in t]
if keys and not named: ok("no installer package from the map (%s) is written into the store's code" % ", ".join(keys))
else: bad("an installer package is hardcoded outside the one map (#102): %s" % named)

print("== T7: #642 the COMMITS and CI-CD feeds are a READER over ONE declaration ==")
# Same rule as the install-source map above, applied to the feeds: the store may
# hold no repository, endpoint, JSON field or status word of its own. Every
# assertion is derived FROM the declaration, so it grows with the file - a third
# feed is covered the moment it is declared, and a test that only ever sees
# today's two is exactly the hollow shape this file already avoids for stores.
feeds_asset = os.path.join(store_dir, "../../../../../assets/appstore-feeds.json")
if not os.path.exists(feeds_asset):
    bad("appstore-feeds.json is missing — the feed tabs cannot be declared")
else:
    decl = json.loads(read(feeds_asset))
    feeds = decl.get("feeds", [])
    viewer = store_files.get("FeedViewer.kt", "")
    if not viewer: bad("FeedViewer.kt is missing — nothing renders the declared feeds")
    # (a) The declaration is usable: every feed needs the id/label/url a tab is
    #     built from. A feed short of one draws a nameless tab that cannot fetch.
    short = [f.get("id", "?") for f in feeds if not (f.get("id") and f.get("label") and f.get("url"))]
    if feeds and not short: ok("all %d declared feed(s) carry id, label and url" % len(feeds))
    else: bad("feed(s) with no id/label/url, or no feed declared at all: %s" % (short or "none declared"))
    # (b) ZERO KOTLIN for a new feed. No id, label, url, host, items key, state
    #     field or status word from the declaration may appear in the store's
    #     code. This is the assertion that fails when someone hardcodes a tab.
    owned = set()
    for f in feeds:
        for key in ("id", "label", "url", "items", "state"):
            if f.get(key): owned.add(f[key])
        for key in ("ok", "bad"):
            owned.update(f.get(key) or [])
        # the repo/host out of the endpoint, and every templated field path
        m = re.match(r"https?://([^/]+)/([^?]*)", f.get("url", ""))
        if m:
            owned.add(m.group(1))
            owned.update(p for p in m.group(2).split("/") if p)
        for key in ("ref", "title", "subtitle", "link"):
            owned.update(re.findall(r"\{([A-Za-z0-9_.]+)\}", f.get(key, "")))
    owned = {w for w in owned if len(w) > 2}
    leaked = sorted({"%s: %r" % (fn, w) for fn, t in store_files.items()
                     for w in owned if '"%s"' % w in t})
    if owned and not leaked:
        ok("none of the %d declared feed strings is written in the store's code" % len(owned))
    else: bad("a feed string is hardcoded outside the one declaration: %s" % "; ".join(leaked[:6]))
    # (c) ONE REQUEST PATH. The reader must not open a connection of its own —
    #     a second one is a second timeout and a second redirect policy.
    if "SourceResolver.getBody(" in viewer: ok("the reader fetches through SourceResolver, the store's one request path")
    else: bad("FeedViewer does not fetch through SourceResolver — it has its own network path")
    own_net = re.search(r"openConnection|HttpURLConnection|URL\(", viewer)
    if not own_net: ok("FeedViewer opens no connection of its own")
    else: bad("FeedViewer opens its own connection (%s) beside SourceResolver" % own_net.group(0))
    # (d) A READER CANNOT ACT. No install, commit or uninstall reachable from it.
    acts = [n for n in ("Fleet.commit(", "Fleet.install", "FleetInstall.run(", "Fleet.uninstall(",
                        "BootstrapInstall.") if n in viewer]
    if not acts: ok("the feed reader reaches no install or uninstall path")
    else: bad("FeedViewer can act on the device: %s" % acts)
    # (e) EMPTY IS NOT FAILED. Both outcomes must be drawn, and drawn
    #     differently: a fetch that threw and rendered as an empty list is the
    #     quiet-green shape that keeps costing this repo days.
    if "onFailure" in viewer and "isEmpty()" in viewer:
        ok("a failed fetch and an empty feed are separate branches")
    else: bad("FeedViewer does not distinguish a failed fetch from an empty feed")
    # (f) IN-FLIGHT IS NEUTRAL. A run that has not finished carries a state in
    #     neither ok nor bad, and must not be coloured as a failure.
    if re.search(r"in feed\.ok\s*->", viewer) and re.search(r"in feed\.bad\s*->", viewer) \
       and re.search(r"else\s*->", viewer):
        ok("a state in neither ok nor bad falls to a neutral else — unfinished is not failed")
    else: bad("FeedViewer has no neutral branch: an in-flight run would be coloured as a verdict")
    # (g) The strip is a RENDERING of the declaration, and parse() is separable
    #     so a test can hand it a feed list it invented.
    if "FeedViewer.labels(feeds)" in cloud: ok("the tab strip is FeedViewer.labels over the declared feeds")
    else: bad("StoreCloudFragment does not build its feed tabs from FeedViewer.labels")
    if re.search(r"fun parse\(decl: JSONObject\)", viewer): ok("parse() takes a declaration, so it can be handed an invented one")
    else: bad("FeedViewer.parse is not separable from the asset")

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
