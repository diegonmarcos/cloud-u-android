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
# The scanner proves it can fail: T5 feeds it a planted URL and a planted
# package table and requires both to be caught.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
LAUNCHER="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"

python3 - "$APP/build.json" "$APP/data/services_public.json" "$APP/data/services_private.json" \
    "$LAUNCHER/AppLongPressMenu.kt" "$LAUNCHER/AppUrls.kt" <<'PY'
import json, re, sys

build_p, pub_p, priv_p, menu_p, urls_p = sys.argv[1:]
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

print("== T3: Update is the fleet's ONE install path — no second updater ==")
ok("Update runs FleetInstall.run") if "FleetInstall.run(" in mc else bad("Update does not go through FleetInstall")
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

print("RESULT: %d passed, %d failed" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
