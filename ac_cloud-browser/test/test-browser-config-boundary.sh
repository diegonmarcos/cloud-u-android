#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ THE #209 BOUNDARY: his tabs live in HIS app, not in the library  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# libs:browser is shared BY REFERENCE — ac_cloud-browser/build.json and
# ac_cloud-vault/build.json both point at the same directory. So the
# question this tester answers is not "are the four URLs present?" but
# "are they present ONLY where they belong?".
#
# Asserting the four URLs appear in the source would prove a string
# literal and nothing else. What is asserted here instead:
#   • build.json carries them, in his order, as the app's own config
#   • the SHARED LIBRARY carries none of them, anywhere
#   • the library's own default pin list is EMPTY
#   • the per-app wiring that carries config across actually exists
#
# The last three are what stop another app inheriting his homepage set.
# Because the library ships no URL and defaults to none, no consumer CAN
# inherit them — which is a stronger statement than checking one named
# consumer, and it needs no reach outside this app's own source.
#
# Every check is made against COMMENT-STRIPPED Kotlin. A grep cannot tell
# a call from prose, and this repository has already shipped a guard that
# was satisfied by its own KDoc.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
python3 - <<'PYEOF'
import json, os, sys

ROOT = os.environ["ROOT"]
APP  = os.path.join(ROOT, "ac_cloud-browser")
LIB  = os.path.join(ROOT, "ab_cloud-libs-shared", "libs", "browser")

fails = []
def check(ok, label, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + label + (("\n          " + detail) if (detail and not ok) else ""))
    if not ok:
        fails.append(label)

def must_read(path):
    """Fail CLOSED: a missing file is a failed assertion, never a skip."""
    if not os.path.isfile(path):
        check(False, "file exists: " + os.path.relpath(path, ROOT))
        return None
    with open(path, encoding="utf-8") as fh:
        return fh.read()

def strip_kotlin_comments(src):
    """Remove // and NESTING /* */ comments, while PRESERVING string
    literals verbatim.

    String-awareness is not a nicety here. A naive stripper treats the
    // inside "https://..." as the start of a comment and eats the rest
    of the line, so every assertion about a URL in Kotlin source silently
    becomes an assertion about nothing. Kotlin block comments also NEST,
    and one stray /* in a KDoc otherwise swallows the whole file."""
    out, i, n, depth = [], 0, len(src), 0
    while i < n:
        two = src[i:i+2]
        if depth:
            if two == "/*": depth += 1; i += 2; continue
            if two == "*/": depth -= 1; i += 2; continue
            i += 1; continue
        if src[i:i+3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j; continue
        if src[i] in ('"', "'"):
            q = src[i]; j = i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(src[i:j]); i = j; continue
        if two == "/*": depth += 1; i += 2; continue
        if two == "//":
            j = src.find("\n", i); i = n if j < 0 else j; continue
        out.append(src[i]); i += 1
    return "".join(out)

EXPECTED = [
    "https://diegonmarcos.github.io/cloud-mobile",
    "https://diegonmarcos.github.io/linktree_icon-view",
    "https://qwant.com",
    "http://termux.app",
    "http://nix.app",
    "http://emulated.app",
    "http://cloud.app",
]

# ── A. the app's own config carries them, in his order ───────────────
raw = must_read(os.path.join(APP, "build.json"))
if raw is None:
    print("build.json unreadable - cannot continue"); sys.exit(1)
bj = json.loads(raw)
browser = bj.get("ui", {}).get("browser")
check(browser is not None, "build.json::ui.browser exists")
browser = browser or {}

def _pin_url(p): return p.get("url") if isinstance(p, dict) else p
PIN_URLS = [_pin_url(p) for p in (browser.get("default_pinned_tabs") or [])]
PIN_TITLES = {p.get("url"): p.get("title") for p in (browser.get("default_pinned_tabs") or []) if isinstance(p, dict)}
check(PIN_URLS == EXPECTED,
      "build.json pins exactly his seven URLs (three sites, then the four private names), in his order",
      "got: %r" % (PIN_URLS,))
# The four local pins are the private names of cloud-c3-webserver's build.json::serving (THE name -> port map),
# each with its owner's port, titled, and the browser bakes that same map (no second copy of a name or a port).
SERVING = json.loads(must_read(os.path.join(ROOT, "ac_cloud-c3-webserver/build.json")) or "{}").get("serving") or {}
def _resolve(e):
    """The port the endpoint's owner declares at port_from: serving itself holds no port (one declaration, the owner's)."""
    d = json.loads(must_read(os.path.join(ROOT, e["owner"] + "/build.json")) or "{}")
    for k in str(e.get("port_from", "")).split("."): d = d.get(k) if isinstance(d, dict) else None
    return d
check(len(SERVING.get("endpoints") or []) == 4, "cloud-c3-webserver declares four served endpoints",
      "got: %r" % (len(SERVING.get("endpoints") or []),))
for e in SERVING.get("endpoints") or []:
    want = "http://%s" % e["name"]
    check("port" not in e and isinstance(_resolve(e), int),
          "%s: serving writes no port; its owner %s declares :%s at %s" % (e["name"], e["owner"], _resolve(e), e.get("port_from")),
          "serving spells port=%r, owner declares %r" % (e.get("port"), _resolve(e)))
    check((browser.get("local_pinned_tabs") or {}).get(e["title"]) == want
          and want in PIN_URLS and PIN_TITLES.get(want) == e["title"],
          "%s pin is the private name %s and carries its title" % (e["title"], want),
          "got: %r" % ((browser.get("local_pinned_tabs") or {}).get(e["title"]),))
check(not any("localhost" in (u or "") or "127.0.0.1" in (u or "") for u in PIN_URLS),
      "no pin spells a loopback address any more: the names are the pins")
check(browser.get("default_engine") == "duckduckgo",
      "build.json ships DuckDuckGo as the default engine",
      "got: %r" % (browser.get("default_engine"),))
ids = [e.get("id") for e in browser.get("search_engines", [])]
check("duckduckgo" in ids and "google" in ids,
      "the alternatives he can switch to are offered", "got: %r" % (ids,))

# ── B. the SHARED LIBRARY carries none of them ───────────────────────
# Scanned raw, comments included: a URL of his has no business in this
# module in any form, and a commented-out default is one uncomment away
# from being a real one.
lib_hits = []
for dirpath, _dirs, files in os.walk(LIB):
    if os.sep + "build" + os.sep in dirpath + os.sep:
        continue
    for fn in files:
        if not fn.endswith((".kt", ".java", ".xml", ".gradle", ".json")):
            continue
        p = os.path.join(dirpath, fn)
        try:
            body = open(p, encoding="utf-8", errors="replace").read()
        except OSError as exc:
            check(False, "readable: " + os.path.relpath(p, ROOT), str(exc)); continue
        for url in EXPECTED:
            if url in body:
                lib_hits.append("%s carries %s" % (os.path.relpath(p, ROOT), url))

check(not lib_hits,
      "the shared library carries NONE of his four URLs",
      "; ".join(lib_hits))

# ── C. the library's own default pins nothing ────────────────────────
cfg = must_read(os.path.join(LIB, "src/main/java/com/diegonmarcos/superapp/browser/BrowserConfig.kt"))
if cfg is not None:
    code = strip_kotlin_comments(cfg)
    # The DEFAULT declaration must set an EMPTY pin list. Matched on the
    # assignment itself, not on the words "empty" appearing nearby.
    check("defaultPinnedTabs = emptyList()" in code,
          "BrowserConfig.DEFAULT pins nothing (defaultPinnedTabs = emptyList())")
    # ...and nothing in the module hands it a populated one.
    check("https://" not in code.split("val DEFAULT")[-1].split("fun parse")[0],
          "no URL is baked into BrowserConfig.DEFAULT")

# ── D. the per-app wiring that carries config across exists ──────────
gradle = must_read(os.path.join(APP, "app/build.gradle"))
if gradle is not None:
    check("buildJson.ui.browser" in gradle and "UI_BROWSER_CONFIG_B64" in gradle,
          "app/build.gradle bakes ui.browser into BuildConfig")

act = must_read(os.path.join(APP, "app/src/main/java/com/diegonmarcos/cloudbrowser/MainActivity.kt"))
if act is not None:
    code = strip_kotlin_comments(act)
    check("BuildConfig.UI_BROWSER_CONFIG_B64" in code
          and "BrowserHostFragment.newInstance" in code,
          "MainActivity hands the app's config to the shared fragment")

frag = must_read(os.path.join(LIB, "src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt"))
if frag is not None:
    code = strip_kotlin_comments(frag)
    check("BrowserConfig.parseBase64" in code,
          "the fragment reads its content from the config, not from itself")
    check("seedOnce(config.defaultPinnedTabs" in code,
          "first-run seeding uses the CONFIG's list, not a list of its own")

print()
if fails:
    print("FAILED %d assertion(s):" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("config boundary: all assertions passed")
PYEOF
