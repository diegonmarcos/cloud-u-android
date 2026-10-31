# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-webview-guard.py ───
#!/usr/bin/env python3
"""webview guard: every embedded web view of the fleet is built by libs:web-core (#1371).

Fails when Kotlin/Java app or lib code outside libs/web-core
  * constructs a WebView (`WebView(ctx)`; a `: WebView(` subclass only when its file runs CloudWeb.configure),
  * configures one by hand (JS, storage, file access, mixed content, user agent, autoplay, GPU knobs),
  * installs a bare WebViewClient()/WebChromeClient() (they lose render-process recovery and full-screen video),
or when the usage registry (1_cicd/src/data/webview-guard.json::usages) is inconsistent:
  * a file that calls CloudWeb.create/configure is not declared,
  * a declared usage names a profile WebProfile.kt does not define, or its file does not use that profile.
Files in `pending` are apps not yet migrated: they must still violate (a stale entry fails), so the list only shrinks.
"""
import json, os, re, sys

RULES = [
    ("constructs a WebView", re.compile(r"(?<![\w.@<])WebView\(\s*(?!\))")),
    ("configures a WebView by hand", re.compile(
        r"\b(javaScriptEnabled|domStorageEnabled|allowFileAccess|allowContentAccess|allowFileAccessFromFileURLs|"
        r"allowUniversalAccessFromFileURLs|mixedContentMode|userAgentString|mediaPlaybackRequiresUserGesture|"
        r"offscreenPreRaster|blockNetworkImage)\s*=|\.setRendererPriorityPolicy\(|\bsetJavaScriptEnabled\(")),
    ("installs a bare WebViewClient/WebChromeClient", re.compile(r"(?<![\w.])(WebViewClient|WebChromeClient)\(\)")),
]
CODE = (".kt", ".java")


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    return re.sub(r"(^|[^:])//.*", r"\1", src)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    pol_path = sys.argv[sys.argv.index("--policy") + 1] if "--policy" in sys.argv else \
        os.path.join(root, "1_cicd/src/data/webview-guard.json")
    pol = json.load(open(pol_path))
    lib, excl, pending = pol["lib"], pol["exclude"], set(pol.get("pending", []))
    errs, seen_pending, callers = [], set(), set()
    for top in sorted(os.listdir(root)):
        if not re.match(r"a[a-d]_", top):
            continue
        for d, dirs, files in os.walk(os.path.join(root, top)):
            rel_d = os.path.relpath(d, root)
            dirs[:] = [x for x in dirs if not x.startswith(".") and x not in ("build", "test", "androidTest")
                       and not any(os.path.join(rel_d, x).startswith(e) for e in excl + [lib])]
            for f in files:
                if not f.endswith(CODE):
                    continue
                rel = os.path.join(rel_d, f)
                src = strip_comments(open(os.path.join(d, f), errors="replace").read())
                if re.search(r"\bCloudWeb\.(create|configure)\(", src):
                    callers.add(rel)
                configures = "CloudWeb.configure(" in src
                hits = [(src[:m.start()].count("\n") + 1, why) for why, rx in RULES for m in rx.finditer(src)
                        # a `: WebView(ctx)` subclass is allowed when its file hands the instance to CloudWeb.configure
                        if not (why == "constructs a WebView" and configures and re.search(r":\s*$", src[:m.start()]))]
                app = next((p for p in pending if rel.startswith(p)), None)
                if app:
                    seen_pending.add(app) if hits else None
                    continue
                errs += [f"{rel}:{ln}: {why} outside {lib} (use CloudWeb.create(ctx, WebProfile.X))" for ln, why in hits]
    profiles = set(re.findall(r"^\s{4}([A-Z_]+)\(", open(os.path.join(root, lib, pol["profiles_file"])).read(), re.M))
    declared = {}
    for u in pol["usages"]:
        declared[u["file"]] = u
        if u["profile"] not in profiles:
            errs.append(f"usage {u['app']}/{u['usage']}: profile {u['profile']!r} is not in WebProfile {sorted(profiles)}")
        p = os.path.join(root, u["file"])
        if not os.path.isfile(p):
            errs.append(f"usage {u['app']}/{u['usage']}: file {u['file']} is missing")
        elif f"WebProfile.{u['profile']}" not in open(p, errors="replace").read():
            errs.append(f"usage {u['app']}/{u['usage']}: {u['file']} does not use WebProfile.{u['profile']}")
    errs += [f"{c}: builds a web view but declares no usage in webview-guard.json::usages" for c in sorted(callers - set(declared))]
    errs += [f"pending {p}: no hand-built WebView left, drop it from pending" for p in sorted(pending - seen_pending)]
    for e in errs:
        print("FAIL", e)
    print(f"webview guard: {len(declared)} usages over {len(profiles)} profiles, {len(pending)} pending, {len(errs)} defect(s)")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
