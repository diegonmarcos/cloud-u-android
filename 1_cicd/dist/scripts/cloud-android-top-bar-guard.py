# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-top-bar-guard.py ───
#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-top-bar-guard — an app that DECLARES ui.top_bar     ║
# ║ draws it with libs:bottomnav's AppTopBar, never a copy of it     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The owner kept asking for the same first row in every app (Cloud Tasks, Cloud Calc, Cloud
# Store): a hamburger that opens a drawer, the app's title, and two round buttons, AI and Settings. It is
# built ONCE, in libs:bottomnav (AppTopBar / AppTopBarScaffold / TopBarDrawer, declared by
# build.json::ui.top_bar). This guard makes a second drawing of it impossible to arrive silently. For every
# app whose build.json declares ui.top_bar it fails when
#   - the declaration is wrong (it says nothing, or names a settings entry the bar does not know),
#   - the app does not compile libs:bottomnav, does not bake UI_TOP_BAR_B64, or never calls AppTopBarScaffold,
#   - ai is declared but the app does not link libs:ai and fill the panel with it,
#   - settings names account / configs but the app does not wire rememberAccountLauncher / onConfigs,
#   - the app's own Kotlin draws what the bar draws: a hamburger icon, a navigation drawer, a top app bar, or
#     a hand-rolled launch of Cloud Account (the bar's Account entry is the one launch).
# It also fails when the owner stops HOLDING the bar (an emptied lib would stay green for every app that
# copied the bar back out), and when there is nothing to check (a guard that found no app declaring a top bar
# because it saw no apps is a false green).
#
# The failure message teaches the rule: a guard that only says "unexpected file" gets deleted by the next
# person who hits it. Everything it knows is in 1_cicd/src/data/top-bar-guard.json: no app, no path and no
# marker is named below. Python 3 only; no ripgrep (it is not on the CI runner).
#
# usage: cloud-android-top-bar-guard.py [repo-root] [--policy FILE]
# exit:  0 clean, 1 a violation, 2 the guard could not run (bad policy, nothing declared)

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "top-bar-guard.json")


def strip_comments(src):
    """Kotlin source without comments; string literals are kept (the rules look at them)."""
    out, i, n, depth = [], 0, len(src), 0
    while i < n:
        two = src[i:i + 2]
        if depth:
            if two == "/*":
                depth += 1; i += 2; continue
            if two == "*/":
                depth -= 1; i += 2; continue
            i += 1; continue
        if src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j; continue
        if src[i] in ('"', "'"):
            q = src[i]; j = i + 1
            while j < n and src[j] != q and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1]); i = j + 1; continue
        if two == "/*":
            depth += 1; i += 2; continue
        if two == "//":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        out.append(src[i]); i += 1
    return "".join(out)


def refuse(msg):
    """The guard could not run: exit 2, never a verdict."""
    print("top-bar-guard: " + msg, file=sys.stderr)
    sys.exit(2)


def norm(p):
    return os.path.normpath(p).replace(os.sep, "/")


def load_policy(path):
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError) as e:
        refuse("cannot read the policy %s (%s)" % (path, e))
    for k in ("owner", "owner_must_hold", "scan_prefixes", "known_settings", "rules", "wiring"):
        if not cfg.get(k):
            refuse("the policy has no %s" % k)
    for name, r in cfg["rules"].items():
        try:
            re.compile(r["pattern"])
        except (re.error, KeyError) as e:
            refuse("rule %s is not a pattern (%s)" % (name, e))
    return cfg


def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


def owner_findings(repo, cfg):
    bad = []
    owner = os.path.join(repo, cfg["owner"])
    if not os.path.isdir(owner):
        return ["the owner tree %s does not exist: the fleet's top bar is gone" % cfg["owner"]]
    for need in cfg["owner_must_hold"]:
        text = read(os.path.join(owner, need["file"]))
        if text is None:
            bad.append("%s/%s is missing: libs:bottomnav no longer holds the top bar" % (cfg["owner"], need["file"]))
            continue
        code = strip_comments(text)
        for n in need["needles"]:
            if n not in code:
                bad.append("%s/%s no longer contains %s: libs:bottomnav no longer holds the top bar" % (cfg["owner"], need["file"], n))
    return bad


def declaring_apps(repo, cfg):
    """[(app dir, its build.json::ui.top_bar)] for every app tree whose build.json declares a top bar."""
    apps = []
    for top in sorted(os.listdir(repo)):
        if not any(top.startswith(p) for p in cfg["scan_prefixes"]) or not os.path.isdir(os.path.join(repo, top)):
            continue
        raw = read(os.path.join(repo, top, "build.json"))
        if raw is None:
            continue
        try:
            ui = (json.loads(raw).get("ui") or {})
        except ValueError:
            continue
        if isinstance(ui, dict) and "top_bar" in ui:
            apps.append((top, ui["top_bar"], json.loads(raw)))
    return apps


def kotlin_files(repo, app, cfg):
    skip_dirs = set(cfg.get("skip_dirs", []))
    parts = cfg.get("skip_path_parts", [])
    files = []
    for dp, dn, fn in os.walk(os.path.join(repo, app)):
        dn[:] = sorted(d for d in dn if d not in skip_dirs)
        rel_dir = norm(os.path.relpath(dp, repo)) + "/"
        for f in sorted(fn):
            if not f.endswith(".kt"):
                continue
            rel = rel_dir + f
            if any(part in "/" + rel for part in parts):
                continue
            files.append(rel)
    return files


def app_findings(repo, cfg, app, top_bar, build):
    bad = []
    known = cfg["known_settings"]
    if not isinstance(top_bar, dict):
        return ["%s: build.json ui.top_bar is not an object" % app]
    settings = top_bar.get("settings") or []
    if not (top_bar.get("drawer") is True or top_bar.get("ai") is True or settings):
        bad.append("%s: ui.top_bar declares nothing (no drawer, no ai, no settings): delete it or declare what the bar shows" % app)
    for sid in settings:
        if sid not in known:
            bad.append("%s: ui.top_bar.settings id %r is not one of %s" % (app, sid, known))
    if len(set(settings)) != len(settings):
        bad.append("%s: ui.top_bar.settings repeats an id" % app)
    modules = build.get("modules") or {}
    if "libs:bottomnav" not in modules:
        bad.append("%s: ui.top_bar is declared but build.json::modules has no libs:bottomnav" % app)
    if top_bar.get("ai") is True and "libs:ai" not in modules:
        bad.append("%s: ui.top_bar.ai is declared but build.json::modules has no libs:ai (the AI panel is libs:ai's chat)" % app)
    gradle_files = list(cfg.get("gradle_files", ["app/build.gradle", "app/build.gradle.kts"]))
    gm = (build.get("android") or {}).get("gradle_module")
    if isinstance(gm, str) and gm.strip("./"):  # a nested Android project (Cloud Notes vendors AFFiNE's)
        gradle_files += [norm(os.path.join(gm, "build.gradle")), norm(os.path.join(gm, "build.gradle.kts"))]
    gradle = "".join(filter(None, (read(os.path.join(repo, app, g)) for g in gradle_files)))
    if cfg["wiring"]["bake"] not in gradle:
        bad.append("%s: its app gradle does not bake %s: the bar's declaration never reaches the app" % (app, cfg["wiring"]["bake"]))
    files = kotlin_files(repo, app, cfg)
    codes = {}
    for rel in files:
        raw = read(os.path.join(repo, rel))
        if raw is not None:
            codes[rel] = strip_comments(raw)
    everything = "\n".join(codes.values())
    if cfg["wiring"]["scaffold"] not in everything:
        bad.append("%s: declares ui.top_bar but no Kotlin calls %s: the declared bar is never drawn" % (app, cfg["wiring"]["scaffold"]))
    if top_bar.get("ai") is True and not any(w in everything for w in cfg["wiring"]["ai"]):
        bad.append("%s: ui.top_bar.ai is declared but no Kotlin fills the AI panel with libs:ai (%s)" % (app, " or ".join(cfg["wiring"]["ai"])))
    for sid in settings:
        need = cfg["wiring"]["settings"].get(sid)
        if need and not any(w in everything for w in need):
            bad.append("%s: ui.top_bar.settings has %r but no Kotlin wires it (%s)" % (app, sid, " or ".join(need)))
    for rel, code in codes.items():
        for name, r in cfg["rules"].items():
            m = re.search(r["pattern"], code)
            if m:
                line = code.count("\n", 0, m.start()) + 1
                bad.append("%s:%d [%s] %s: `%s`" % (rel, line, name, r.get("why", ""), m.group(0).strip()[:70]))
    return bad


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    policy = DEFAULT_POLICY
    if "--policy" in argv:
        policy = argv[argv.index("--policy") + 1]
        args = [a for a in args if a != policy]
    repo = os.path.abspath(args[0] if args else os.path.join(HERE, "..", "..", ".."))
    cfg = load_policy(policy)
    bad = owner_findings(repo, cfg)
    apps = declaring_apps(repo, cfg)
    if not apps:
        refuse("no app declares ui.top_bar: a green verdict here would mean nothing")
    for app, top_bar, build in apps:
        bad += app_findings(repo, cfg, app, top_bar, build)
    if bad:
        print("top-bar-guard: FAIL (%d)" % len(bad))
        for b in bad:
            print("  - " + b)
        print()
        print("RULE: %s" % cfg.get("_rule", "an app that declares ui.top_bar draws it with libs:bottomnav's AppTopBar."))
        print("The hamburger + page drawer, the centred title, the AI panel and the Settings dropdown live ONCE in %s." % cfg["owner"])
        print("To give an app the bar: declare ui.top_bar in its build.json, link libs:bottomnav (and libs:ai for ai), bake")
        print("UI_TOP_BAR_B64, and call AppTopBarScaffold(TopBarDecl.fromBuildConfig(...), onAccount = rememberAccountLauncher(),")
        print("onConfigs = {...}, aiPanel = {...}) around the app's pages; each page fills the drawer with TopBarDrawer { ... }.")
        print("Do not draw a hamburger, a drawer or a top app bar yourself. If a rule is too wide, narrow it in the policy.")
        return 1
    print("top-bar-guard: ok (%d app(s) declare ui.top_bar: %s; the owner still holds the bar)" % (len(apps), ", ".join(a for a, _, _ in apps)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
