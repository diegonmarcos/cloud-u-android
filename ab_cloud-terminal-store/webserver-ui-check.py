#!/usr/bin/env python3
"""Both Cloud Terminals serve the LINUX webserver UI -- prove it, byte for byte.

    webserver-ui-check.py [--url http://127.0.0.1:PORT] [--src browse.html] <terminal dir>

<terminal dir> is ac_cloud-termux or ac_cloud-nix-on-droid. Guards, each a hard failure:

  1. PORT   the port comes from <terminal dir>/build.json::runtime.webserver.port, nowhere else
            (with --url the URL's port must equal it).
  2. PIN    termux (rootfs/rootfs.json) and nix (build.json ... extras.pins) pin ONE and the same
            my-webserver version: both terminals run the same program.
  3. UI     GET / returns the Linux SPA: its sha256 equals cloud-u-linux's
            da_my-webserver/src/lib/browse.html at that pin's tag (the server only substitutes
            __INIT_PATH__), and the tree / viewer / toolbar markers are present.
  4. FS     /__api__/ls lists a file planted in the served root.

Without --url the pinned release asset is downloaded (CI only; never at runtime on a device),
checked against the pinned sha256, started on the declared port over a temp root, and stopped.
"""
import argparse, base64, hashlib, json, os, platform, shutil, signal, stat, subprocess
import sys, tempfile, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
LINUX = "https://github.com/diegonmarcos/cloud-u-linux"
RAW = "https://raw.githubusercontent.com/diegonmarcos/cloud-u-linux"
MARKERS = {
    "folder tree (left)": ['id="sidebar"', 'id="file-list"', 'id="path-bar"'],
    "viewer (right)": ['id="right-panel"', 'id="viewer"'],
    "toolbar (top of viewer)": ['id="viewer-toolbar"', 'id="raw-toggle"', 'id="copy-raw"'],
}


def die(msg):
    print("::error::FAIL -- " + msg)
    sys.exit(1)


def ok(msg):
    print("  ok   -- " + msg)


def get(url, timeout=60):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.status, r.read()


def load(path):
    with open(path) as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url")
    ap.add_argument("--src", help="local copy of Linux's browse.html at the pin (default: fetched)")
    ap.add_argument("app")
    a = ap.parse_args()
    app = os.path.abspath(a.app)

    port = load(os.path.join(app, "build.json"))["runtime"]["webserver"]["port"]
    ok("%s: port %d from build.json::runtime.webserver" % (os.path.basename(app), port))
    if a.url and urllib.parse.urlparse(a.url).port != port:
        die("--url %s is not the declared port %d" % (a.url, port))

    termux = load(os.path.join(REPO, "ac_cloud-termux/rootfs/rootfs.json"))["tarballs"]["my-webserver"]
    nix = load(os.path.join(REPO, "ac_cloud-nix-on-droid/build.json"))
    for k in ("forks", "nixdroid", "bootstrap", "default_packages", "extras", "pins", "my-webserver"):
        nix = nix[k]
    if termux["version"] != nix["version"]:
        die("termux pins my-webserver %s, nix pins %s: the terminals must run one build"
            % (termux["version"], nix["version"]))
    ver = nix["version"]
    ok("termux and nix pin the same my-webserver %s" % ver)

    if a.src:
        source = open(a.src, "rb").read()
    else:
        _, source = get("%s/my-webserver-%s/da_my-webserver/src/lib/browse.html" % (RAW, ver))
    if b"__INIT_PATH__" not in source:
        die("Linux browse.html at my-webserver-%s has no __INIT_PATH__ placeholder" % ver)
    want = source.replace(b"__INIT_PATH__", b'"/"', 1)

    proc, tmp = None, None
    try:
        if not a.url:
            arch = {"x86_64": "amd64", "aarch64": "arm64"}[platform.machine()]
            name = "my-webserver-" + platform.machine()
            tmp = tempfile.mkdtemp(prefix="ws-ui-check-")
            binp = os.path.join(tmp, name)
            _, blob = get("%s/releases/download/my-webserver-%s/%s" % (LINUX, ver, name), 600)
            sri = "sha256-" + base64.b64encode(hashlib.sha256(blob).digest()).decode()
            if sri != nix["hash"][arch]:
                die("%s digest %s is not the pinned %s" % (name, sri, nix["hash"][arch]))
            with open(binp, "wb") as f:
                f.write(blob)
            os.chmod(binp, os.stat(binp).st_mode | stat.S_IXUSR)
            ok("%s downloaded and matches the pinned sha256" % name)
            root = os.path.join(tmp, "home")
            os.mkdir(root)
            open(os.path.join(root, "planted-by-ui-check.txt"), "w").write("x")
            env = {k: v for k, v in os.environ.items() if not k.startswith("HTTPD_WRITE")}
            log = open(os.path.join(tmp, "server.log"), "w")
            proc = subprocess.Popen([binp, str(port), root], env=env, stdout=log, stderr=log,
                                    stdin=subprocess.DEVNULL, start_new_session=True)
            a.url = "http://127.0.0.1:%d" % port

        body, last = None, None
        for _ in range(60):
            if proc and proc.poll() is not None:
                die("server exited %s: %s" % (proc.returncode, open(os.path.join(tmp, "server.log")).read()[-800:]))
            try:
                st, body = get(a.url + "/", 5)
                break
            except Exception as e:  # not up yet
                last = e
                time.sleep(1)
        if body is None:
            die("nothing answered GET %s/ within 60s (%s)" % (a.url, last))
        if st != 200:
            die("GET / returned %s" % st)
        # A served root holding its own index.html is shown instead of the SPA; /__browse__/ is
        # always the SPA. The device root ($HOME) normally has none; the smoke root never does.
        if body != want:
            _, body = get(a.url + "/__browse__/", 5)
        digest = hashlib.sha256(body).hexdigest()
        if body != want:
            die("served UI sha256 %s != Linux browse.html@%s %s" % (digest, ver, hashlib.sha256(want).hexdigest()))
        ok("served UI == cloud-u-linux browse.html@my-webserver-%s (sha256 %s)" % (ver, digest))
        text = body.decode("utf8")
        for what, ids in MARKERS.items():
            miss = [m for m in ids if m not in text]
            if miss:
                die("UI is missing the %s markers %s" % (what, miss))
            ok("marker present: " + what)
        if proc:
            _, ls = get(a.url + "/__api__/ls?path=/", 10)
            if b"planted-by-ui-check.txt" not in ls:
                die("/__api__/ls does not list the planted file: %r" % ls[:300])
            ok("/__api__/ls lists the served root")
    finally:
        if proc and proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


main()
