# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-engine-contract-guard.py ───
#!/usr/bin/env python3
"""cloud-android-engine-contract-guard.py -- #705 an engine an app BINDS must answer what that app calls.

An app that binds an engine instead of compiling it (build.json::engines, e.g.
cloud-drive -> Cloud-Lib-Gh.apk) and the engine it binds now ship SEPARATELY:
an engine change republishes only its own APK. That is the point, and it is
also the risk -- nothing in either ship sees the other side. Each side's own
tester suite may only read its own source (a tester that reaches across is
downgraded to advisory by cloud-android-test-engine.sh), so the one check that
needs BOTH sides lives here, in a guard that runs on every push.

For every engine any app declares, from the declarations alone:

  K1  the declared Store row exists and is a lib, and its package is the
      package of an engine module on the shelf (<application_id_prefix>.<module>);
  K2  that module's manifest has a service declaring the engine CONTRACT, and
      the app's action ({package}.ENGINE) is the action that service answers
      (${applicationId}.ENGINE) once both placeholders are the package;
  K3  the contract the app needs is not above the contract the engine declares;
  K4  every method the app's client calls is a method the engine's service
      lists -- an engine that drops one breaks every installed copy of that app;
  K5  the client reads the same CONTRACT key the engine declares;
  K6  no app compiles or watches an engine: no build.json::modules entry points
      at an engine module's directory, and no ship workflow watches it. Either
      one makes an engine change republish that app again -- the coupling the
      split removed (engine-apk-split: Cloud Agenda kept declaring libs:cal long
      after it stopped linking it, so every calendar edit re-shipped it). This
      covers apps with no testers of their own, which is why it lives here.

Vacuity is a failure: no declared engine, or a client with no calls, checks nothing.
Usage: cloud-android-engine-contract-guard.py <repo root>
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
LIB_APKS = "ab_cloud-libs-shared/lib-apks"


def code(path):
    """A source file with its comment lines dropped: prose may NAME a call it does not make."""
    with open(path, encoding="utf-8") as f:
        return "\n".join(l for l in f.read().split("\n") if not re.match(r"\s*(\*|//|/\*)", l))


def sources(root):
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith((".kt", ".java")):
                yield os.path.join(dirpath, f)


def engine_modules(root):
    """package -> module dir, for every module under every lib_apks scan root."""
    cfg = json.load(open(os.path.join(root, LIB_APKS, "build.json"), encoding="utf-8"))["lib_apks"]
    scan = cfg["scan"] if isinstance(cfg["scan"], list) else [cfg["scan"]]
    out = {}
    for rel in scan:
        base = os.path.normpath(os.path.join(root, LIB_APKS, rel))
        for d in sorted(os.listdir(base)):
            if os.path.isfile(os.path.join(base, d, "build.gradle")):
                out[cfg["application_id_prefix"] + "." + d.replace("-", "")] = os.path.join(base, d)
    return out


def engine_service(module_dir):
    """(actions, contract, methods) of the module's contract-declaring service, or a reason string."""
    mf = os.path.join(module_dir, "src", "main", "AndroidManifest.xml")
    if not os.path.isfile(mf):
        return "has no manifest"
    for svc in ET.parse(mf).getroot().iter("service"):
        metas = {m.get(A + "name"): m.get(A + "value") for m in svc.iter("meta-data")}
        if CONTRACT_KEY not in metas:
            continue
        try:
            contract = int(metas[CONTRACT_KEY])
        except (TypeError, ValueError):
            return "declares a contract %r that is not a number" % metas[CONTRACT_KEY]
        actions = [a.get(A + "name") for f in svc.iter("intent-filter") for a in f.iter("action")]
        simple = (svc.get(A + "name") or "").rsplit(".", 1)[-1]
        files = [p for p in sources(os.path.join(module_dir, "src", "main"))
                 if re.search(r"class\s+%s\b" % re.escape(simple), code(p))]
        if len(files) != 1:
            return "declares service %s, whose class is not in its sources" % simple
        text = code(files[0])
        consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', text))
        m = re.search(r"fun\s+methodNames\(\)\s*:\s*Array<String>\s*=\s*arrayOf\(([^)]*)\)", text)
        if not m:
            return "service %s has no methodNames() this guard can read" % simple
        methods = set()
        for tok in (t.strip() for t in m.group(1).split(",") if t.strip()):
            methods.add(tok[1:-1] if tok.startswith('"') else consts.get(tok, "?" + tok))
        return actions, contract, methods
    return "declares no service with the engine CONTRACT meta-data"


def client_calls(app_dir, key):
    """(file, method names its ask(...) calls name, the CONTRACT key it reads) for the engine `key`."""
    marker = "BuildConfig.%s_ENGINE_ACTION" % key.upper().replace("-", "_")
    for path in sources(os.path.join(app_dir, "app", "src", "main")):
        text = code(path)
        if marker not in text:
            continue
        consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', text))
        # calls, not the definition: `fun ask(method: String, ...)` names a parameter, not a method
        calls = {consts.get(n, "?" + n) for n in re.findall(r"(?<!fun )\bask\((\w+)", text)}
        return path, calls, consts.get("CONTRACT_KEY", "")
    return None, set(), ""


def coupled(root, modules):
    """K6: every app module entry or ship-workflow watch line that reaches an engine module."""
    engines = {os.path.normpath(d): os.path.basename(d) for d in modules.values()
               if not isinstance(engine_service(d), str)}
    bad = []
    for bj in sorted(glob.glob(os.path.join(root, "*", "build.json"))):
        mods = json.load(open(bj, encoding="utf-8")).get("modules") or {}
        for key, spec in mods.items():
            if isinstance(spec, dict) and spec.get("dir"):
                d = os.path.normpath(os.path.join(os.path.dirname(bj), spec["dir"]))
                if d in engines:
                    bad.append("K6 %s/build.json::modules.%s is the %s engine -- that app would compile it, and every "
                               "engine change would republish it" % (os.path.basename(os.path.dirname(bj)), key, engines[d]))
    for wf in sorted(glob.glob(os.path.join(root, ".github", "workflows", "ship-*.yml"))):
        text = open(wf, encoding="utf-8").read()
        for d, name in sorted(engines.items()):
            watch = '"%s/**"' % os.path.relpath(d, root)
            if watch in text:
                bad.append("K6 %s watches %s -- every %s engine change would republish that app"
                           % (os.path.relpath(wf, root), watch, name))
    return bad


def check(root):
    bad = []
    fleet = {a["id"]: a for a in json.load(open(os.path.join(root, FLEET), encoding="utf-8"))["apps"]}
    modules = engine_modules(root)
    declared = 0
    bad += coupled(root, modules)
    for bj in sorted(glob.glob(os.path.join(root, "*", "build.json"))):
        app_dir = os.path.dirname(bj)
        app = os.path.basename(app_dir)
        engines = json.load(open(bj, encoding="utf-8")).get("engines") or {}
        for key, decl in engines.items():
            if key.startswith("_"):
                continue
            declared += 1
            where = "%s/build.json::engines.%s" % (app, key)
            row = fleet.get(decl.get("fleet"))
            if row is None:
                bad.append("K1 %s names Store row %r, which the fleet manifest does not have" % (where, decl.get("fleet")))
                continue
            if row.get("kind") != "lib":
                bad.append("K1 %s: Store row %s is a %r, not a lib the Store installs as an engine" % (where, row["id"], row.get("kind")))
            pkg = row.get("package", "")
            module = modules.get(pkg)
            if module is None:
                bad.append("K1 %s: %s is not the package of any engine module on the shelf" % (where, pkg))
                continue
            svc = engine_service(module)
            if isinstance(svc, str):
                bad.append("K2 %s: %s %s" % (where, os.path.relpath(module, root), svc))
                continue
            actions, contract, methods = svc
            wanted = str(decl.get("action", "")).replace("{package}", pkg)
            answered = [a.replace("${applicationId}", pkg) for a in actions]
            if wanted not in answered:
                bad.append("K2 %s: the app looks for action %r and the engine answers %s" % (where, wanted, answered))
            need = decl.get("min_contract")
            if not isinstance(need, int) or need > contract:
                bad.append("K3 %s: the app needs contract %r and the engine declares %d" % (where, need, contract))
            path, calls, key_read = client_calls(app_dir, key)
            if path is None or not calls:
                bad.append("K4 %s: no client in %s reaches this engine through ask(...) -- nothing to hold the engine to" % (where, app))
                continue
            missing = sorted(c for c in calls if c not in methods)
            if missing:
                bad.append("K4 %s: %s calls %s, which the engine does not list (it lists %s)"
                           % (where, os.path.relpath(path, root), missing, sorted(methods)))
            if key_read != CONTRACT_KEY:
                bad.append("K5 %s: %s reads contract key %r, the engines declare %r" % (where, os.path.relpath(path, root), key_read, CONTRACT_KEY))
    if declared == 0:
        bad.append("no app declares build.json::engines -- this guard checked nothing")
    return declared, bad


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    declared, bad = check(argv[1])
    for b in bad:
        print("FAIL  " + b)
    if bad:
        return 1
    print("PASS  %d declared engine binding(s): every client's calls are answered, at a contract the engine declares" % declared)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
