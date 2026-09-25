#!/usr/bin/env bash
# #571 WE ARE THE STORE — the source resolver's declaration and its readers.
# Behaviour (parse refusals, actions, the rendered empty-phone page) is asserted
# by app/src/test/.../apps/StoreResolverTest.kt; this tester holds the contracts
# a Robolectric run cannot see, and PROVES ITSELF BY MUTATION: the validator in
# T1 is run again over deliberately broken copies of the declaration and must go
# RED on each — a validator that passes a fake Play URL is no validator.
#
# T1 DECLARATION HONESTY (validator + 4 mutations). One `resolver` block inside
#    the ONE #564 map; `order` = vendor, fdroid, play; every app's ladder a
#    strict subsequence of it; a `play` rung carries nothing but its kind
#    (Google Play publishes no APK URL); every vendor `apk`/`feed`/`sha256`
#    is https; no URL anywhere points at play.google.com; the F-Droid signer
#    pin is a 64-hex sha256; at least one Play-only and one direct app exist.
# T2 ONE INSTALLER, ONE DOWNLOADER. ExternalInstall commits through
#    Fleet.commit and nothing else; SourceResolver and FDroidIndex download
#    through the updater's Download; no store file opens its own
#    HttpURLConnection for bytes, names a PackageInstaller, or names an
#    installer package (#564's rule, extended to the new files).
# T3 F-DROID IS VERIFIED. FDroidIndex opens the index as a verifying JarFile,
#    reads codeSigners, compares against the declared pin, and refuses an
#    unsigned entry; APKs are VerifiedApk.byDigest against the index hash.
# T4 THE PAGE. Phone Apps passes real Install all / Update all verbs, builds its
#    rows from installed ∪ fleet ∪ declared, and derives every not-installed
#    row's buttons from PhoneAppActions.forMissing.
# T5 REGISTRATION. The JVM test exists and names the honesty cases.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIB="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main"
MAP="$LIB/assets/appstore-install-sources.json"

python3 - "$APP" "$LIB" "$MAP" <<'PY'
import json, os, re, sys, glob, copy
app, lib, mapf = sys.argv[1:]
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

def validate(doc):
    """The honesty rules, as a list of violations. Empty = honest."""
    v = []
    r = doc.get("resolver")
    if not isinstance(r, dict): return ["no resolver block"]
    order = r.get("order")
    if order != ["vendor", "fdroid", "play"]: v.append("order is %r" % (order,))
    fd = r.get("fdroid", {})
    if not re.fullmatch(r"[0-9a-f]{64}", str(fd.get("cert_sha256", ""))): v.append("fdroid cert_sha256 is not a sha256 hex")
    for k in ("repo", "index", "index_entry", "api"):
        if not str(fd.get(k, "")).startswith("https://") and k != "index" and k != "index_entry": v.append("fdroid.%s not https" % k)
    play = r.get("play", {}).get("installer")
    if play not in doc.get("sources", {}): v.append("play installer %r is not in the #564 sources map" % play)
    apps = r.get("apps", {})
    if not apps: v.append("no apps")
    n_play_only = n_direct = 0
    for pkg, a in apps.items():
        srcs = a.get("sources", [])
        kinds = [s.get("kind") for s in srcs]
        ranks = [order.index(k) if k in (order or []) else -1 for k in kinds]
        if not srcs or -1 in ranks or ranks != sorted(ranks) or len(set(ranks)) != len(ranks):
            v.append("%s ladder %r is not a subsequence of order" % (pkg, kinds))
        if kinds == ["play"]: n_play_only += 1
        if any(k != "play" for k in kinds): n_direct += 1
        for s in srcs:
            if s.get("kind") == "play" and set(s) != {"kind"}: v.append("%s: a play rung carries %s" % (pkg, sorted(set(s) - {"kind"})))
            if s.get("kind") == "vendor":
                if not (s.get("apk") or s.get("apk_key")): v.append("%s: vendor rung names no apk" % pkg)
                for k in ("apk", "feed", "sha256"):
                    if k in s and not s[k].startswith("https://"): v.append("%s: vendor %s not https" % (pkg, k))
            for k, val in s.items():
                if isinstance(val, str) and "play.google.com" in val: v.append("%s: %s points at play.google.com — Play has no APK URL" % (pkg, k))
    if n_play_only == 0: v.append("no Play-only app declared (the badge would be untested)")
    if n_direct == 0: v.append("no direct app declared")
    return v

print("== T1: declaration honesty, proven by mutation ==")
doc = json.loads(read(mapf))
maps = sorted(os.path.basename(p) for p in glob.glob(os.path.join(lib, "assets/*.json")))
if maps == ["appstore-install-sources.json"]: ok("the resolver lives inside the ONE #564 map (%s)" % maps[0])
else: bad("a second asset map: %s" % maps)
viol = validate(doc)
if not viol: ok("declaration passes every honesty rule (%d apps)" % len(doc["resolver"]["apps"]))
else: bad("declaration violates: " + " | ".join(viol))

apps = doc["resolver"]["apps"]
play_only = next(p for p, a in apps.items() if [s["kind"] for s in a["sources"]] == ["play"])
direct = next(p for p, a in apps.items() if len(a["sources"]) > 1 and a["sources"][-1]["kind"] == "play")
vendor = next(p for p, a in apps.items() if a["sources"][0].get("kind") == "vendor" and "apk" in a["sources"][0])
mutations = {
    "a URL on a Play rung": lambda d: d["resolver"]["apps"][play_only]["sources"][0].__setitem__("apk", "https://play.google.com/fake.apk"),
    "Play ranked above a direct rung": lambda d: d["resolver"]["apps"][direct].__setitem__("sources", list(reversed(d["resolver"]["apps"][direct]["sources"]))),
    "a plain-http vendor APK": lambda d: d["resolver"]["apps"][vendor]["sources"][0].__setitem__("apk", "http://example.invalid/x.apk"),
    "a truncated F-Droid signer pin": lambda d: d["resolver"]["fdroid"].__setitem__("cert_sha256", "abc"),
    "a Play installer outside the #564 map": lambda d: d["resolver"]["play"].__setitem__("installer", "com.example.nostore"),
}
for name, mut in mutations.items():
    m = copy.deepcopy(doc); mut(m)
    if validate(m): ok("mutation goes RED: " + name)
    else: bad("mutation stayed GREEN — the validator does not see: " + name)

print("== T2: one installer, one downloader ==")
kt_dir = os.path.join(lib, "java/com/diegonmarcos/superapp/appstore")
kt = {os.path.basename(p): code(p) for p in glob.glob(kt_dir + "/*.kt")}
for f in ("SourceResolver.kt", "FDroidIndex.kt", "ExternalInstall.kt", "StorePhoneFragment.kt", "PhoneAppActions.kt"):
    if f not in kt: print("FATAL: %s missing" % f); sys.exit(2)
ext = kt["ExternalInstall.kt"]
if "Fleet.commit(" in ext and not re.search(r"PackageInstaller|ACTION_INSTALL_PACKAGE|HttpURLConnection|Download\.toFile", ext):
    ok("ExternalInstall commits through Fleet.commit and owns no installer or downloader")
else: bad("ExternalInstall grew a second installer/downloader")
for f in ("SourceResolver.kt", "FDroidIndex.kt"):
    if "Download.toFile(" in kt[f] and "PackageInstaller" not in kt[f]: ok(f + " downloads through the updater's Download")
    else: bad(f + " does not use Download.toFile, or reaches PackageInstaller")
if re.search(r"^\s*object Download\b", read(os.path.join(lib, "../../../updater/src/main/java/com/diegonmarcos/superapp/updater/source/Download.kt")), re.M):
    ok("updater Download is public, so the store can share it")
else: bad("updater Download is not public — the store cannot share the one downloader")
installers = sorted(doc["sources"])
leak = ["%s: %s" % (f, k) for f, t in kt.items() for k in installers if '"%s"' % k in t]
leak += [f for f, t in kt.items() if "play.google.com" in t]
if not leak: ok("no store file names an installer package or a Play URL")
else: bad("hardcoded installer / Play URL: %s" % leak)

print("== T3: F-Droid is verified ==")
fdi = kt["FDroidIndex.kt"]
for needle, what in (("JarFile(jar, true)", "the index is opened as a VERIFYING JarFile"),
                     ("codeSigners", "the entry's signers are read"),
                     ("not signed", "an unsigned entry is refused"),
                     ("certSha256.lowercase() !in fingerprints", "the signer must match the declared pin"),
                     ("VerifiedApk.byDigest(target, v.sha256)", "the APK is verified against the signed index hash"),
                     ("JsonReader", "the index is streamed, not parsed into a tree")):
    if needle in fdi: ok(what)
    else: bad("FDroidIndex: " + what + " — missing " + needle)
if 'hashType == "sha256"' in fdi: ok("only sha256-hashed versions are offered")
else: bad("FDroidIndex offers versions without a sha256 hash")

print("== T4: the page ==")
phone = kt["StorePhoneFragment.kt"]
m = re.search(r"StoreBar\.Verbs\((.*?)\)\)", phone, re.S)
if m and re.search(r"installAll\s*=\s*\{\s*installAll\(\)\s*\}", m.group(1)) and re.search(r"updateAll\s*=\s*\{\s*updateAll\(\)\s*\}", m.group(1)):
    ok("Phone Apps passes real Install all / Update all verbs")
else: bad("Phone Apps does not pass real batch verbs")
for needle, what in (("AppInventory.launchable(ctx)", "rows start from what is installed"),
                     ('filter { it.kind == "app" }', "rows add every fleet app"),
                     ("resolver.apps.values.forEach", "rows add every declared external app"),
                     ("PhoneAppActions.forMissing(", "not-installed rows derive their buttons from the one action source"),
                     ("SourceResolver.ofFleet(Fleet.status(", "fleet rows are probed by the fleet's own status"),
                     ("SourceResolver.check(app, resolver", "external rows are probed by the resolver")):
    if needle in phone: ok(what)
    else: bad("StorePhoneFragment: " + what + " — missing " + needle)
sr = kt["SourceResolver.kt"]
if "require(o.length() == 1)" in sr: ok("the parser refuses a Play rung that carries anything")
else: bad("the parser accepts a Play rung with extra fields")
if "Fleet.candidateIdentity(ctx, apk.file)" in sr and "id.pkg != pkg" in sr: ok("a downloaded APK must be the package asked for")
else: bad("SourceResolver installs bytes without checking their package")

print("== T5: registration ==")
jvm = os.path.join(app, "app/src/test/java/com/diegonmarcos/superapp/apps/StoreResolverTest.kt")
if os.path.exists(jvm):
    t = read(jvm)
    for needle in ("a URL on a Play rung is refused", "Play ranked above a direct rung is refused",
                   "a Play-only app - Install disabled", "an empty phone renders every fleet app"):
        if needle in t: ok("JVM test covers: " + needle)
        else: bad("JVM test lacks: " + needle)
else: bad("StoreResolverTest.kt missing")

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
