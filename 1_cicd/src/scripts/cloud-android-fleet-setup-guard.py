#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-fleet-setup-guard — every fleet app answers the    ║
# ║ setup contract for every store it declares                       ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY (#873). Cloud Account performs the FULL FLEET SETUP through ONE contract: every
# app serves `<package>.fleetsetup` (libs:fleetconfig-model SetupProvider: describe /
# export / apply / status) for the stores fleet-config.json declares for it. An app that
# declares stores but does not link the provider, or declares a store nothing can read and
# write, is a setting Fleet Setup silently cannot place. This guard turns both into a red.
#
# It fails on:
#   S0 the contract itself is not in place: the provider is not declared (exported, behind
#      CONSTELLATION_DATA, authority ${applicationId}.fleetsetup) in libs:fleetconfig-model's
#      manifest, or libs:core no longer `api`s that module (so the apps stop answering);
#   S1 an app that declares migrating stores neither links the provider (build.json modules
#      carry libs:core + libs:fleetconfig-model, and a hand-written settings file includes
#      both) nor is exempt in the policy;
#   S2 an exemption without a reason a reviewer can disagree with, naming no real app, or
#      for an app that already links the provider (stale);
#   S3 a declared migrating store with no handler: a prefs / encrypted store is served by the
#      default; any other kind needs `SetupStores.register("<store>", ...)` in the app's or a
#      lib's source, or an `unserved` entry in the policy with its reason. An `unserved` entry
#      whose store is served, no longer declared or no longer used by that app is stale;
#   S4 a bundle route (fleet-config.json `bundle`) to a store that is not declared, a key the
#      manifest never migrates, or a store nothing can serve;
#   S5 nothing scanned at all (an empty checkout is not a clean fleet).
#
# Everything it knows is in the policy JSON. stdlib only.
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "fleet-setup-guard.json")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def read(path):
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return None


def registered(repo, cfg, roots):
    """Store names some source registers a handler for (comments blanked)."""
    rx = re.compile(cfg["register_pattern"])
    exts = tuple(cfg["extensions"])
    prune = set(cfg["prune_dirs"])
    found = {}
    for root in roots:
        for d, dirs, files in os.walk(os.path.join(repo, root)):
            dirs[:] = [x for x in dirs if x not in prune]
            for f in files:
                if f.endswith(exts):
                    text = read(os.path.join(d, f))
                    if text:
                        for m in rx.finditer(strip_comments(text)):
                            found.setdefault(m.group(1), os.path.relpath(os.path.join(d, f), repo))
    return found


def provider_ok(repo, cfg):
    """S0: (problems) the provider is declared right and core still carries the module."""
    out = []
    mf = read(os.path.join(repo, cfg["model_manifest"]))
    if mf is None:
        return ["%s is missing" % cfg["model_manifest"]]
    blocks = [b for b in re.findall(r"<provider\b.*?/>", mf, flags=re.S) if cfg["provider_class"] in b]
    if not blocks:
        out.append("%s declares no <provider> %s" % (cfg["model_manifest"], cfg["provider_class"]))
    else:
        b = blocks[0]
        if 'android:exported="true"' not in b:
            out.append("the setup provider is not exported")
        if 'android:authorities="%s"' % cfg["authority"] not in b:
            out.append("the setup provider's authority is not %s" % cfg["authority"])
        if 'android:permission="%s"' % cfg["permission"] not in b:
            out.append("the setup provider is not behind %s" % cfg["permission"])
    core = read(os.path.join(repo, cfg["core_gradle"]))
    if core is None or not re.search(r"^\s*api\s+project\(\s*['\"]:libs:fleetconfig-model['\"]\s*\)", strip_comments(core), flags=re.M):
        out.append("%s no longer `api`s :libs:fleetconfig-model, so no app answers the setup contract" % cfg["core_gradle"])
    src = read(os.path.join(repo, cfg["provider_source"]))
    if src is None or "checkCallingPermission(SetupContract.PERMISSION)" not in strip_comments(src):
        out.append("%s does not re-check the caller in call()" % cfg["provider_source"])
    return out


def settings_files(appdir):
    out = []
    for d, dirs, files in os.walk(appdir):
        dirs[:] = [x for x in dirs if x not in ("node_modules", "build", ".git", ".gradle", "dist", "www")]
        if d[len(appdir):].count(os.sep) > 6:
            dirs[:] = []
        for f in files:
            if f in ("settings.gradle", "settings.gradle.kts", "settings-extras.gradle"):
                out.append(os.path.join(d, f))
    return out


def links_provider(repo, module):
    """(linked, why-not). An app links the provider when libs:core and libs:fleetconfig-model are both in
    its Gradle build: build.json::modules (settings read it) or, for a hand-written settings file, both
    included there. A settings file that includes core by hand and not the model is the break."""
    appdir = os.path.join(repo, module)
    mods = {}
    bj = os.path.join(appdir, "build.json")
    if os.path.isfile(bj):
        try:
            mods = load(bj).get("modules", {})
        except ValueError:
            return False, "build.json is not JSON"
    by_json = "libs:core" in mods
    hand_core = hand_both = False
    for sf in settings_files(appdir):
        t = strip_comments(read(sf) or "")
        if "build.json" in t:
            continue
        if re.search(r"libs:core|libs/core", t):
            hand_core = True
            if "libs:fleetconfig-model" in t:
                hand_both = True
            else:
                return False, "%s includes libs:core by hand and not libs:fleetconfig-model" % os.path.relpath(sf, repo)
    if by_json:
        return ("libs:fleetconfig-model" in mods) or hand_both, "build.json modules do not carry libs:fleetconfig-model (libs:core api-depends on it)"
    if hand_both:
        return True, ""
    return False, "no libs:core in build.json modules or a settings file"


def app_stores(m, app):
    mods = set(app.get("libs", [])) | {app["module"]}
    mig = set(m["migrate"])
    out = {}
    for name, s in m["stores"].items():
        cls = s.get("class_by_app", {}).get(app["id"], s.get("class"))
        if cls in mig and any(u in mods for u in s.get("used_by", [])):
            out[name] = s
    return out


def check(repo, cfg):
    problems = []
    manifest_path = os.path.join(repo, cfg["manifest"])
    mtext = read(manifest_path)
    if mtext is None:
        return ["S5 %s is missing: an empty checkout is not a clean fleet" % cfg["manifest"]], 0
    m = json.loads(mtext)
    apps = {i: dict(a, id=i) for i, a in m["apps"].items()}
    for p in provider_ok(repo, cfg):
        problems.append("S0 " + p)

    exempt, unserved = cfg.get("exempt", {}), cfg.get("unserved", {})
    # S2 exemptions
    scanned = 0
    linked = {}
    for a in apps.values():
        if os.path.isdir(os.path.join(repo, a["module"])):
            scanned += 1
            linked[a["id"]] = links_provider(repo, a["module"])
    for mod, why in exempt.items():
        app = next((a for a in apps.values() if a["module"] == mod), None)
        if app is None:
            problems.append("S2 exemption %s names no app in the manifest" % mod)
        elif len(why.strip()) < cfg["min_reason_chars"]:
            problems.append("S2 exemption %s: the reason is under %d characters" % (mod, cfg["min_reason_chars"]))
        elif linked.get(app["id"], (False,))[0]:
            problems.append("S2 exemption %s is stale: the app links the provider now (delete the exemption)" % mod)

    # S1 + S3 per app
    for a in apps.values():
        mod = a["module"]
        if not os.path.isdir(os.path.join(repo, mod)):
            continue
        stores = app_stores(m, a)
        ok, why = linked[a["id"]]
        if not stores:
            continue
        if not ok:
            if mod not in exempt:
                problems.append("S1 %s declares %d migrating store(s) and does not link the setup provider: %s (link it, or exempt it with a reason in fleet-setup-guard.json)" % (mod, len(stores), why))
            continue
        roots = [mod] + [os.path.join(cfg["lib_root"], u[4:]) for u in set(a["libs"]) if u.startswith("lib-")]
        regs = registered(repo, cfg, [r for r in roots if os.path.isdir(os.path.join(repo, r))])
        mine = unserved.get(mod, {})
        for name, s in sorted(stores.items()):
            default = s["kind"] in cfg["default_kinds"]
            if default or name in regs:
                if name in mine:
                    problems.append("S3 %s: `unserved` entry for %s is stale (it is served by %s)" % (mod, name, "the default handler" if default else regs[name]))
                continue
            if name not in mine:
                problems.append("S3 %s: store %s (%s) has no handler: register one (SetupStores.register(\"%s\", ...)) or list it under `unserved` with the reason" % (mod, name, s["kind"], name))
            elif len(mine[name].strip()) < cfg["min_reason_chars"]:
                problems.append("S3 %s: the `unserved` reason for %s is under %d characters" % (mod, name, cfg["min_reason_chars"]))
    for mod, d in unserved.items():
        app = next((a for a in apps.values() if a["module"] == mod), None)
        if app is None:
            problems.append("S3 `unserved` names %s, which is no app in the manifest" % mod)
            continue
        have = app_stores(m, app)
        for name in d:
            if name not in have:
                problems.append("S3 %s: `unserved` entry %s is stale (the app no longer declares a migrating store of that name)" % (mod, name))

    # S4 bundle routes
    mig = set(m["migrate"])
    for path, targets in (m.get("bundle") or {}).items():
        if path.startswith("_"):
            continue
        for t in targets:
            s = m["stores"].get(t["store"])
            if s is None:
                problems.append("S4 bundle route %s -> %s: the store is not declared" % (path, t["store"]))
                continue
            kc = s.get("keys", {}).get(t["key"], s.get("class"))
            if kc not in mig:
                problems.append("S4 bundle route %s -> %s.%s: a %s key never migrates" % (path, t["store"], t["key"], kc))
            if s["class"] not in mig:
                problems.append("S4 bundle route %s -> %s: a %s store never migrates" % (path, t["store"], s["class"]))
            if t.get("pull"):
                pa = apps.get(t["pull"])
                if pa is None:
                    problems.append("S4 bundle route %s -> %s: `pull` names %s, which is no app in the manifest" % (path, t["store"], t["pull"]))
                elif t["store"] not in app_stores(m, pa):
                    problems.append("S4 bundle route %s -> %s: `pull` app %s does not declare that store" % (path, t["store"], t["pull"]))
            if s["kind"] not in cfg["default_kinds"] and not registered(repo, cfg, cfg["register_roots"]).get(t["store"]):
                problems.append("S4 bundle route %s -> %s: nothing can serve a %s store" % (path, t["store"], s["kind"]))
    if scanned == 0:
        problems.append("S5 no app directory scanned")
    return problems, scanned


def main(argv):
    repo = os.getcwd()
    policy = DEFAULT_POLICY
    only = None
    args = list(argv)
    while args:
        a = args.pop(0)
        if a == "--root":
            repo = args.pop(0)
        elif a == "--policy":
            policy = args.pop(0)
        elif a == "--app":
            only = args.pop(0)
        else:
            repo = a
    cfg = load(policy)
    problems, scanned = check(repo, cfg)
    if only:   # one app's tester: the contract itself (S0/S4/S5) and this app's own lines
        problems = [p for p in problems if p[:2] in ("S0", "S4", "S5") or (" %s" % only) in p or ("%s:" % only) in p]
    if problems:
        print("fleet setup guard: %d violation(s). Rule: every app that declares migrating stores links the setup provider, and every such store has a handler" % len(problems))
        for p in problems:
            print("  " + p)
        return 1
    print("fleet setup guard: OK — %d app(s) scanned; every declared migrating store is served or listed with its reason" % scanned)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
