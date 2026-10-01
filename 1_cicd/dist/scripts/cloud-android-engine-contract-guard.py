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

For every engine any app declares (build.json::engines), or any shared client
module declares for all of its consumers (<module>/engine-client.json, same
shape), from the declarations alone:

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
      at an engine module's directory, no app's settings/gradle file or other
      module map names it (Camera, Media Center and Office wire modules there),
      and no ship workflow watches it. Any of them
      one makes an engine change republish that app again -- the coupling the
      split removed (engine-apk-split: Cloud Agenda kept declaring libs:cal long
      after it stopped linking it, so every calendar edit re-shipped it). This
      covers apps with no testers of their own, which is why it lives here;
  K7  the client handshakes before it binds: it resolves the declared action in
      the declared package with its meta-data, refuses a contract below the
      needed one, and only then binds (a DataBackendClient, or bindService for an
      engine with a typed wire) -- a bind by class name reads an old or missing
      engine as a dead call;
  K8  the app can SEE the engine: its manifest queries ${<key>EnginePackage},
      bound by app/build.gradle's manifestPlaceholders, or holds
      QUERY_ALL_PACKAGES. On Android 11+ an unqueried package is invisible and
      reads exactly like "not installed" (engine-apk-split F2);
  K9  no app binds an engine it has not declared: every DataBackendClient an
      app's own source builds is in a declared engine's client. Cloud Agenda
      and Cloud News each built one by typed package and class name, outside
      build.json::engines -- so K1..K8 never looked at them, and F2 shipped.

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


def main_dir(client_dir):
    """An app's app/src/main, or a library module's src/main."""
    app = os.path.join(client_dir, "app", "src", "main")
    return app if os.path.isdir(app) else os.path.join(client_dir, "src", "main")


def gradle_files(client_dir):
    names = [os.path.join(client_dir, "app", g) for g in ("build.gradle", "build.gradle.kts")]
    names += [os.path.join(client_dir, g) for g in ("build.gradle", "build.gradle.kts")]
    return [g for g in names if os.path.isfile(g)]


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
    for path in sources(main_dir(app_dir)):
        text = code(path)
        if marker not in text:
            continue
        consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', text))
        # calls, not the definition: `fun ask(method: String, ...)` names a parameter, not a method
        # a method is named by a const or by a string literal; both are held to the engine's list
        calls = {lit or consts.get(n, "?" + n) for lit, n in re.findall(r'(?<!fun )\bask\((?:"(\w+)"|(\w+))', text)}
        return path, calls, consts.get("CONTRACT_KEY", "")
    return None, set(), ""


def handshake(path, key):
    """K7: the client's code orders resolve-with-meta-data < contract floor < bind, or a reason string."""
    text = code(path)
    up = key.upper().replace("-", "_")
    resolve = text.find("resolveService(Intent(BuildConfig.%s_ENGINE_ACTION).setPackage(" % up)
    if resolve < 0 or "PackageManager.GET_META_DATA" not in text[resolve:resolve + 200]:
        return "does not resolve the declared action in the declared package with its meta-data"
    if "BuildConfig.%s_ENGINE_PACKAGE" % up not in text or "BuildConfig.%s_ENGINE_MIN_CONTRACT" % up not in text:
        return "does not take the package and the needed contract from the declaration"
    floor = text.find("if (found < needed)")
    bind = min([i for i in (text.find("DataBackendClient("), text.find("bindService(")) if i >= 0] or [-1])
    if floor < 0:
        return "binds without refusing a contract below the needed one"
    if bind < 0 or not resolve < floor < bind:
        return "builds its DataBackendClient before the handshake has accepted the engine"
    return None


def visible(app_dir, key):
    """K8: the engine package is queryable from this app, or a reason string."""
    mf = os.path.join(main_dir(app_dir), "AndroidManifest.xml")
    if not os.path.isfile(mf):
        return "has no app manifest"
    root = ET.parse(mf).getroot()
    if any(p.get(A + "name") == "android.permission.QUERY_ALL_PACKAGES" for p in root.iter("uses-permission")):
        return None
    holder = "%sEnginePackage" % key
    if "${%s}" % holder not in [p.get(A + "name") for q in root.iter("queries") for p in q.iter("package")]:
        return "manifest <queries> does not name ${%s}: on Android 11+ the engine is invisible and reads as not installed" % holder
    gradle = gradle_files(app_dir)
    if not any(re.search(r"\b%s\s*[:=]\s*%s\b" % (holder, holder), code(g)) for g in gradle):
        return "its build.gradle does not bind the manifest placeholder %s to the resolved package" % holder
    return None


def undeclared_binds(app_dir, keys):
    """K9: app source files that build a DataBackendClient outside every declared engine's client."""
    markers = ["BuildConfig.%s_ENGINE_ACTION" % k.upper().replace("-", "_") for k in keys]
    main = os.path.join(app_dir, "app", "src", "main")
    return sorted(p for p in (sources(main) if os.path.isdir(main) else ())
                  if "DataBackendClient(" in code(p) and not any(m in code(p) for m in markers))


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
    # The same coupling spelled outside build.json::modules: an include in settings.gradle(.kts),
    # a project(...) in app/build.gradle(.kts), or another module map's "dir" (Office's
    # build.modules). Matched as a whole path component, so libs/ml-l-image does not hit
    # libs/ml-l-image-mlkit.
    for bj in sorted(glob.glob(os.path.join(root, "*", "build.json"))):
        app_dir = os.path.dirname(bj)
        files = [bj] + gradle_files(app_dir) + [f for f in (os.path.join(app_dir, "settings.gradle"),
                                                           os.path.join(app_dir, "settings.gradle.kts")) if os.path.isfile(f)]
        for f in files:
            text = open(f, encoding="utf-8").read() if f.endswith(".json") else code(f)
            for d, name in sorted(engines.items()):
                if os.path.normpath(app_dir) == os.path.normpath(os.path.join(root, LIB_APKS)):
                    continue
                if re.search(r"[/:]libs[/:]%s['\"]" % re.escape(name), text):
                    bad.append("K6 %s names the %s engine module -- that app would compile it, and every engine "
                               "change would republish it" % (os.path.relpath(f, root), name))
    for wf in sorted(glob.glob(os.path.join(root, ".github", "workflows", "ship-*.yml"))):
        text = open(wf, encoding="utf-8").read()
        for d, name in sorted(engines.items()):
            watch = '"%s/**"' % os.path.relpath(d, root)
            if watch in text:
                bad.append("K6 %s watches %s -- every %s engine change would republish that app"
                           % (os.path.relpath(wf, root), watch, name))
    return bad


def declarations(root, modules):
    """(client dir, declaration file) for every app build.json and every shared client module's engine-client.json."""
    out = [(os.path.dirname(bj), bj) for bj in sorted(glob.glob(os.path.join(root, "*", "build.json")))]
    dirs = sorted({os.path.dirname(d) for d in modules.values()})
    out += [(os.path.join(base, m), os.path.join(base, m, "engine-client.json"))
            for base in dirs for m in sorted(os.listdir(base))
            if os.path.isfile(os.path.join(base, m, "engine-client.json"))]
    return out


def check(root):
    bad = []
    fleet = {a["id"]: a for a in json.load(open(os.path.join(root, FLEET), encoding="utf-8"))["apps"]}
    modules = engine_modules(root)
    declared = 0
    bad += coupled(root, modules)
    for app_dir, bj in declarations(root, modules):
        app = os.path.relpath(app_dir, root) if bj.endswith("engine-client.json") else os.path.basename(app_dir)
        engines = json.load(open(bj, encoding="utf-8")).get("engines") or {}
        for path in undeclared_binds(app_dir, [k for k in engines if not k.startswith("_")]):
            bad.append("K9 %s binds an engine that %s/build.json::engines does not declare -- no handshake, "
                       "no visibility and no contract check covers it" % (os.path.relpath(path, root), app))
        for key, decl in engines.items():
            if key.startswith("_"):
                continue
            declared += 1
            where = "%s/%s::engines.%s" % (app, os.path.basename(bj), key)
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
            why = handshake(path, key)
            if why:
                bad.append("K7 %s: %s %s" % (where, os.path.relpath(path, root), why))
            why = visible(app_dir, key)
            if why:
                bad.append("K8 %s: %s" % (where, why))
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
