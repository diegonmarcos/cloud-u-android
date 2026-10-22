# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-crash-default-guard.py ───
#!/usr/bin/env python3
"""#1122 crash-safe by default: every fleet app gets libs:core's crash handler, and none can opt out.

Usage: cloud-android-crash-default-guard.py [ROOT]   (default: the repo root)
Policy: 1_cicd/src/data/crash-default-guard.json. Exit 1 on any finding."""
import glob
import json
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
POLICY = os.path.join(ROOT, "1_cicd/src/data/crash-default-guard.json")
CORE = "ab_cloud-libs-shared/libs/core"
CRASH = "ab_cloud-libs-shared/libs/crash"


def read(rel):
    try:
        with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


def strip(text):
    return re.sub(r"//[^\n]*|/\*.*?\*/", "", text, flags=re.S)


def main():
    bad = []
    policy = json.loads(read("1_cicd/src/data/crash-default-guard.json") or "{}")
    not_linked = policy.get("not_linked", {})

    manifest = read(f"{CORE}/src/main/AndroidManifest.xml") or ""
    m = re.search(r"<provider[^>]*CoreInitProvider[^>]*/?>", manifest, re.S)
    if not m:
        bad.append(f"{CORE}: the manifest no longer declares CoreInitProvider")
    elif 'android:enabled="false"' in m.group(0):
        bad.append(f"{CORE}: CoreInitProvider is disabled")
    provider = read(f"{CORE}/src/main/java/com/diegonmarcos/superapp/core/CoreInitProvider.kt") or ""
    if "CrashReports.installHandler(" not in provider:
        bad.append(f"{CORE}: CoreInitProvider does not install CrashReports.installHandler (the default handler)")
    if "CrashReports.flushLocal(" not in provider:
        bad.append(f"{CORE}: CoreInitProvider does not retry the local sink (CrashReports.flushLocal)")
    if not re.search(r"""project\(\s*['"]:libs:crash['"]""", read(f"{CORE}/build.gradle") or ""):
        bad.append(f"{CORE}/build.gradle: libs:core no longer depends on :libs:crash (the module that holds the default handler and screen)")
    if "CRASH_SINK_PORT" not in (read(f"{CRASH}/build.gradle") or ""):
        bad.append(f"{CRASH}/build.gradle: CRASH_SINK_PORT (the local sink's declared port) is not baked")
    if "CrashActivity" not in (read(f"{CRASH}/src/main/AndroidManifest.xml") or ""):
        bad.append(f"{CRASH}: the manifest no longer declares CrashActivity (the generic crash screen)")
    if "/__app__/api/crashes" not in (read("ac_cloud-c3-webserver/server/src/app.rs") or ""):
        bad.append("ac_cloud-c3-webserver: the local crash sink route /__app__/api/crashes is not served")

    seen = set()
    for bj in sorted(glob.glob(os.path.join(ROOT, "a[ac]_*/build.json"))):
        app = os.path.basename(os.path.dirname(bj))
        if app in seen or app.startswith("ab_"):
            continue
        seen.add(app)
        try:
            d = json.load(open(bj, encoding="utf-8"))
        except ValueError:
            bad.append(f"{app}/build.json: not valid JSON")
            continue
        if not d.get("android", {}).get("application_id") and "modules" not in d:
            continue
        # linked by the build.json module map, or by a hand-written settings file (Gallery, Camera: upstream trees
        # whose settings.gradle(.kts) includes the shared module by path)
        linked = "libs:core" in (d.get("modules") or {}) or any(
            re.search(r"""include\(\s*['"]:libs:core['"]""", strip(open(sf, encoding="utf-8", errors="replace").read()))
            for sf in glob.glob(os.path.join(ROOT, app, "settings.gradle*")))
        if d.get("crash_default") is False:
            bad.append(f"{app}/build.json: crash_default=false — an app cannot opt out of the default crash handler")
        if not linked and app not in not_linked:
            bad.append(f"{app}: does not link libs:core, so it has no default crash handler (link it, or list it in crash-default-guard.json::not_linked with a reason)")
        if linked and app in not_linked:
            bad.append(f"{app}: now links libs:core — remove its stale not_linked entry")
        for mf in glob.glob(os.path.join(ROOT, app, "**/AndroidManifest.xml"), recursive=True):
            if "/build/" in mf or "/z_archive/" in mf:
                continue
            if "CoreInitProvider" in open(mf, encoding="utf-8", errors="replace").read():
                bad.append(f"{os.path.relpath(mf, ROOT)}: names CoreInitProvider (removing or disabling it is an opt-out)")
    for app in not_linked:
        if app not in seen:
            bad.append(f"crash-default-guard.json: not_linked names {app}, which is not an app")

    for b in bad:
        print("FAIL", b)
    print(f"crash-default-guard: {len(seen)} app(s) checked, {len(bad)} finding(s)")
    return 1 if bad else 0


sys.exit(main())
