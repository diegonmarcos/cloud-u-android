#!/usr/bin/env bash
# #572 — the long-press menu's URLs section is FULLY DECLARATIVE, and this is
# the guard that says so (the #499 identity-string guard pattern: derive the
# allowed set from the declaration, fail on anything outside it).
#
# THE RULE (Diego, caps): no per-app URL tables in Kotlin. Our apps' endpoints
# come from data/services_{public,private}.json through the `service` key on the
# app's ui.external_apps entry; a phone app's website is derived at runtime from
# its own manifest / installer. Both derivations live in AppUrls.kt, whose only
# inputs are build.json::ui.app_urls and the snapshots — so the menu code
# (AppLongPressMenu.kt) AND the derivation (AppUrls.kt) carry ZERO literal
# http(s) URLs. A URL typed into either is the table this ticket forbids, and
# there is no allow-list of "ok" files to widen: the declaration is JSON.
#
# #677 adds the third source, a published static site: `site` names a
# linktree.json link label (T6), and an empty URLs section says WHY (T7).
# Overrides for mutation runs: BUILD, MENU, URLS, LINKTREE.
#
# The scanner proves it can fail: T5 feeds it a planted URL and a planted
# package table and requires both to be caught.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
LAUNCHER="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"

python3 - "${BUILD:-$APP/build.json}" "$APP/data/services_public.json" "$APP/data/services_private.json" \
    "${MENU:-$LAUNCHER/AppLongPressMenu.kt}" "${URLS:-$LAUNCHER/AppUrls.kt}" "${LINKTREE:-$APP/data/linktree.json}" <<'PY'
import json, re, sys

build_p, pub_p, priv_p, menu_p, urls_p, lt_p = sys.argv[1:]
PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  PASS: " + m)
def bad(m):
    global FAIL; FAIL += 1; print("  FAIL: " + m)
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()

def code(text):
    """Kotlin with comments removed — a comment ABOUT a URL is not one."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in text.splitlines())

URL = re.compile(r"https?://")
# A package-shaped string literal: the seed of a per-app table.
PKG = re.compile(r'"(?:com|org|net|io|cld|app)\.[a-z0-9_]+(?:\.[a-z0-9_]+)+"')
def violations(src):
    c = code(src)
    return URL.findall(c) + PKG.findall(c)

build = json.loads(read(build_p))
ui = build["ui"]
pub = {r["name"]: r for r in json.load(open(pub_p, encoding="utf-8"))}
priv = {r["name"]: r for r in json.load(open(priv_p, encoding="utf-8"))}
menu, urls = read(menu_p), read(urls_p)

print("== T1: no literal URL and no package table in the menu code or its derivation ==")
for name, src in (("AppLongPressMenu.kt", menu), ("AppUrls.kt", urls)):
    v = violations(src)
    ok("%s holds no literal URL or package string" % name) if not v else bad("%s holds %s" % (name, v))

print("== T2: the menu has the three sections and gets its URLs from the one derivation ==")
mc = code(menu)
for title in ("Actions", "Configs", "URLs"):
    ok('section "%s" is drawn' % title) if 'sectionHeader(ctx, "%s"' % title in mc else bad('no "%s" section' % title)
ok("URL rows come from AppUrls.of") if "AppUrls.of(" in mc else bad("the menu does not call AppUrls.of")
ok('an "Update" row exists') if 'makeMenuRow(ctx, "Update"' in mc else bad('no "Update" row')

print("== T3: Update is the ONE path per kind (fleet -> FleetInstall, else the #571 ladder) - no second updater ==")
ok("Update runs FleetInstall.run for a fleet app") if "FleetInstall.run(" in mc else bad("Update does not go through FleetInstall")
ok("Update runs ExternalInstall.run over SourceResolver for any other app") if ("ExternalInstall.run(" in mc and "SourceResolver.resolve(" in mc) else bad("Update does not go through the #571 resolver")
second = [w for w in ("Updater.", "PackageInstaller.Session", "openSession", "createSession", "HttpURLConnection", "DownloadManager") if w in mc]
ok("no second updater in the menu") if not second else bad("menu grew its own updater: %s" % second)

print("== T4: the declaration resolves — every `service` names a snapshot row, endpoints are never restated ==")
cfg = ui.get("app_urls") or {}
for k in ("public_scheme", "private_scheme"):
    ok("app_urls.%s is a scheme" % k) if str(cfg.get(k, "")).endswith("://") else bad("app_urls.%s missing or not a scheme" % k)
ok("website_meta_keys declared") if cfg.get("website_meta_keys") else bad("app_urls.website_meta_keys empty")
pages = cfg.get("installer_pages") or {}
for inst, p in pages.items():
    good = p.get("label") and str(p.get("url", "")).startswith("https://") and "{pkg}" in p.get("url", "")
    ok("installer_pages[%s] has a label and an https {pkg} template" % inst) if good else bad("installer_pages[%s] malformed" % inst)
ok("at least one installer page declared") if pages else bad("installer_pages empty")
withsvc = [a for a in ui["external_apps"] if "service" in a]
ok("%d external_apps entries declare a service" % len(withsvc)) if withsvc else bad("no external_apps entry declares a service")
for a in withsvc:
    s = a["service"]
    ok("%s -> %s is a declared service row" % (a["id"], s)) if (s in pub or s in priv) else bad("%s names service %r, which is in neither services snapshot" % (a["id"], s))
    stray = [k for k in a if k in ("public_url", "private_url", "private_dns", "url")]
    ok("%s restates no endpoint" % a["id"]) if not stray else bad("%s restates %s next to its service" % (a["id"], stray))

print("== T5: the scanner can fail (planted violations must be caught) ==")
ok("a planted URL is caught") if violations('val u = "https://example.org/x"') else bad("scanner missed a planted URL")
ok("a planted package table is caught") if violations('val t = mapOf("com.foo.bar" to 1)') else bad("scanner missed a planted package literal")
ok("a URL in a comment is ignored") if not violations('// see https://example.org\nval x = 1') else bad("scanner flags comments")

print("== T6: a published-site `site` resolves to exactly one linktree link (#677) ==")
lt = json.load(open(lt_p, encoding="utf-8"))
def lt_urls(label):
    found = set()
    def walk(o):
        if isinstance(o, dict):
            if o.get("label") == label and re.match(r"https?://[^/]", str(o.get("url", ""))): found.add(o["url"])
            for v in o.values(): walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
    walk(lt); return found
withsite = [a for a in ui["external_apps"] if "site" in a]
ok("%d external_apps entries declare a site" % len(withsite)) if withsite else bad("no external_apps entry declares a site")
for a in withsite:
    u = lt_urls(a["site"])
    ok("%s -> site %r = %s" % (a["id"], a["site"], next(iter(u)))) if len(u) == 1 else bad("%s names site %r, which matches %d linktree links (need exactly 1)" % (a["id"], a["site"], len(u)))
uc = code(urls)
ok("AppUrls.of draws the site source") if re.search(r"fun of\(.*?site\(pkg, ext, decode\(BuildConfig\.LINKTREE_JSON_B64\)\)", uc, re.S) else bad("AppUrls.of does not include the site source")
sb = re.search(r"fun site\(.*?\n    }", uc, re.S); sb = sb.group(0) if sb else ""
ok("site() reads the url from linktree via siteUrl, nothing else") if ("siteUrl(label, linktree)" in sb and "github.io" not in sb and "+ \"/\"" not in sb) else bad("site() does not resolve through linktree (or builds a URL)")
su = re.search(r"fun siteUrl\(.*?\n    }", uc, re.S); su = su.group(0) if su else ""
ok("an ambiguous or absent label yields no URL (singleOrNull)") if "found.singleOrNull()" in su else bad("siteUrl guesses among several links or none")

print("== T7: an empty URLs section says why, two different ways (#677) ==")
ex = re.search(r"fun explain\(.*?\n    }", uc, re.S); ex = ex.group(0) if ex else ""
m1 = re.search(r'"(URL missing: )"', ex); m2 = re.search(r'"(No URL kind for this app:[^"]*)"', ex)
ok("missing value -> %r" % m1.group(1)) if m1 else bad("no 'URL missing:' message for a declared kind that does not resolve")
ok("unsupported kind -> %r" % m2.group(1)) if m2 else bad("no 'No URL kind for this app:' message")
if m1 and m2 and m1.group(1) != m2.group(1)[:len(m1.group(1))]: ok("the two messages differ")
elif m1 and m2: bad("missing and unsupported produce the same message")
ok("explain checks a declared site against linktree") if "siteUrl(it, linktree) == null" in ex else bad("explain does not detect a missing site")
ok("the menu draws AppUrls.absence when no link resolved") if re.search(r"\} else AppUrls\.absence\(ctx, pkg\)", mc) else bad("an empty URLs section is still silent")

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
