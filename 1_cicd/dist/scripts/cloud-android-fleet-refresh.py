# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-fleet-refresh.py ───
#!/usr/bin/env python3
"""
cloud-android-fleet-refresh — #836: ship the apps a shared-lib change did NOT
ship per push, once, on a schedule.

Since #836 a push to a shared lib starts only the lib's PRIMARY consumer
(1_cicd/src/data/lib-primary-consumers.json). Every other app that compiles it
carries the lib as a DEFERRED input of its ship workflow: comment lines
`#   input: "..."` inside the managed paths fence, which GitHub ignores and
cloud-android-source-identity.sh hashes. This script answers, per app:

    did any deferred shared-lib input change between the commit the app's
    published APK was built from and HEAD?

The baseline is line 2 of the app's `<asset>.source` sidecar on its release
(cloud-android-publish-gate.sh stamp, #826) — the commit the bytes on the
phone were built from. Only shared-lib inputs count: the workflow file is
deferred too, but a generator run rewrites every ship workflow at once and
counting it would turn each such commit into a fleet rebuild.

An app with no readable baseline (no sidecar, or a sidecar naming no commit in
this history) is NOT selected (reason `no-baseline`, listed on the run's
summary): with nothing to diff against, every refresh would dispatch it again
— and an app whose release never got a sidecar would republish every 6 hours.
Its next own push publishes and stamps one, after which it is refreshed.

USAGE
  cloud-android-fleet-refresh.py plan     [--root DIR] [--baselines FILE] [--json]
  cloud-android-fleet-refresh.py dispatch [--root DIR] [--baselines FILE] [--dry-run]
      --baselines FILE  JSON {app_dir: commit-or-null} instead of reading the
                        releases (offline tests; the simulator)
  dispatch sends one repository_dispatch `fleet-refresh-<app>` per selected app
  (the event every refreshable ship workflow listens for; the generator injects
  it). Needs GH_TOKEN and GITHUB_REPOSITORY.
"""
import glob, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
from cloud_android_workflow_paths import deferred_inputs  # noqa: E402

SHARED_LIBS = "ab_cloud-libs-shared/libs/"
REFRESH_TYPE = re.compile(r"^    types: \[(fleet-refresh-[^\]]+)\]$", re.M)


def refreshable(root):
    """[(app, workflow, event_type, [lib dirs])] for every ship workflow the
    generator gave a refresh trigger."""
    out = []
    for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/ship-*.yml"))):
        text = open(wf).read()
        ev = REFRESH_TYPE.search(text)
        app = re.search(r"^  WORK_DIR: (\S+)$", text, re.M)
        if not ev or not app:
            continue
        libs = [d.rstrip("*").rstrip("/") for d in deferred_inputs(text) if d.startswith(SHARED_LIBS)]
        if libs:
            out.append((app.group(1), os.path.basename(wf), ev.group(1), libs))
    return out


def _gh(*args, binary=False):
    r = subprocess.run(["gh", "api", *args], capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout if binary else r.stdout.decode()


def _asset_and_tag(root, app):
    try:
        bj = json.load(open(os.path.join(root, app, "build.json")))
    except (OSError, ValueError):
        return None, None
    rel = bj.get("release") or {}
    variants = rel.get("variants") or []
    asset = next((v.get("gh_asset") for v in variants if isinstance(v, dict) and v.get("gh_asset")), None)
    asset = asset or (rel.get("gh_release") or {}).get("asset_name")
    tag = (rel.get("gh_release") or {}).get("rolling_tag") or "latest"
    return asset, tag


def release_baselines(root, apps):
    """{app: commit or None} from each app's <asset>.source sidecar."""
    repo = os.environ.get("GITHUB_REPOSITORY", "diegonmarcos/cloud-u-android")
    cache, out = {}, {}
    for app in apps:
        asset, tag = _asset_and_tag(root, app)
        if not asset:
            out[app] = None
            continue
        if tag not in cache:
            raw = _gh(f"repos/{repo}/releases/tags/{tag}")
            cache[tag] = {a["name"]: a["id"] for a in (json.loads(raw)["assets"] if raw else [])}
        aid = cache[tag].get(asset + ".source")
        body = _gh("-H", "Accept: application/octet-stream", f"repos/{repo}/releases/assets/{aid}", binary=True) if aid else None
        lines = body.decode(errors="replace").splitlines() if body else []
        out[app] = lines[1].strip() if len(lines) > 1 else None
    return out


def _is_commit(root, sha):
    return bool(sha) and re.fullmatch(r"[0-9a-f]{40}", sha) is not None and subprocess.run(
        ["git", "-C", root, "cat-file", "-e", sha + "^{commit}"], capture_output=True).returncode == 0


def plan(root, baselines):
    rows = []
    for app, wf, ev, libs in refreshable(root):
        base = baselines.get(app)
        row = {"app": app, "workflow": wf, "event_type": ev, "baseline": base, "changed": []}
        if not _is_commit(root, base):
            row["selected"], row["reason"] = False, "no-baseline"
        else:
            diff = subprocess.run(["git", "-C", root, "diff", "--name-only", base, "HEAD", "--", *libs],
                                  capture_output=True, text=True, check=True).stdout.split()
            row["changed"] = sorted({p for p in libs for f in diff if f == p or f.startswith(p + "/")})
            row["selected"] = bool(row["changed"])
            row["reason"] = "lib-input-moved" if diff else "unchanged"
        rows.append(row)
    return rows


def main(argv):
    if not argv or argv[0] not in ("plan", "dispatch"):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    cmd, root, bfile, as_json, dry = argv[0], ".", None, False, False
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--root":
            root = argv[i + 1]; i += 2; continue
        if a == "--baselines":
            bfile = argv[i + 1]; i += 2; continue
        if a == "--json":
            as_json = True
        elif a == "--dry-run":
            dry = True
        else:
            print(f"unknown option: {a}", file=sys.stderr)
            return 2
        i += 1
    apps = [r[0] for r in refreshable(root)]
    baselines = json.load(open(bfile)) if bfile else release_baselines(root, apps)
    rows = plan(root, baselines)
    sel = [r for r in rows if r["selected"]]
    if as_json:
        print(json.dumps(rows, indent=1))
    else:
        for r in rows:
            print(f"{'SHIP' if r['selected'] else 'skip':4}  {r['app']:40} {r['reason']:16} "
                  f"base={(r['baseline'] or '-')[:9]}  {' '.join(x[len(SHARED_LIBS):] for x in r['changed'])}")
        print(f"── {len(sel)} of {len(rows)} refreshable app(s) selected ──")
    if cmd == "dispatch":
        repo = os.environ.get("GITHUB_REPOSITORY")
        if not repo and not dry:
            print("GITHUB_REPOSITORY is not set", file=sys.stderr)
            return 2
        failed = 0
        for r in sel:
            if dry:
                print(f"would dispatch {r['event_type']}")
                continue
            ok = subprocess.run(["gh", "api", "-X", "POST", f"repos/{repo}/dispatches",
                                 "-f", f"event_type={r['event_type']}"], capture_output=True).returncode == 0
            print(f"{'dispatched' if ok else 'FAILED    '} {r['event_type']}")
            failed += not ok
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as fh:
                fh.write(f"### Fleet refresh: {len(sel)} of {len(rows)} app(s) shipped\n\n")
                for r in rows:
                    fh.write(f"- {'**SHIP**' if r['selected'] else 'skip'} `{r['app']}` — {r['reason']}"
                             + (f" ({', '.join(x[len(SHARED_LIBS):] for x in r['changed'])})" if r["changed"] else "") + "\n")
        return 1 if failed else 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
