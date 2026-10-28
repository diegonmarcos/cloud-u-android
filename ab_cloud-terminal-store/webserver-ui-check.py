#!/usr/bin/env python3
"""Both Cloud Terminals serve the LINUX webserver UI -- prove it, byte for byte.

    webserver-ui-check.py [--url http://127.0.0.1:PORT] [--bin fleet-webserver] <terminal dir>

<terminal dir> is ac_cloud-termux or ac_cloud-nix-on-droid. The server is the one shared lib
ab_cloud-libs-shared/libs/fleet-webserver (binary fleet-webserver); nothing is copied or downloaded
here. Guards, each a hard failure:

  1. PORT   the port comes from <terminal dir>/build.json::runtime.webserver.port, nowhere else
            (with --url the URL's port must equal it); both terminals name binary fleet-webserver.
  2. SOURCE the lib's vendor/ frontend (what the binary bundles) is byte-identical to
            cloud-u-linux da_my-webserver/src at the commit vendor/SOURCE records AND at
            Linux main today.
  3. UI     GET / returns that SPA (sha256 equal; the server only substitutes __INIT_PATH__) and
            the folder-tree / viewer / toolbar markers are present.
  4. FS     /__api__/ls lists a file planted in the served root.

Without --url the binary is started on the declared port over a temp root and stopped; without
--bin it is built first from the lib's own source with cargo (CI only; a missing cargo FAILS).
"""
import argparse, hashlib, json, os, shutil, signal, subprocess, sys, tempfile, time
import urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
LIB = os.path.join(REPO, "ab_cloud-libs-shared/libs/fleet-webserver")
RAW = "https://raw.githubusercontent.com/diegonmarcos/cloudy-u-linux"
MARKERS = {
    "folder tree (left)": ['id="sidebar"', 'id="file-list"', 'id="path-bar"'],
    "viewer (right)": ['id="right-panel"', 'id="viewer"'],
    "toolbar (top of viewer)": ['id="viewer-toolbar"', 'id="raw-toggle"', 'id="copy-raw"'],
}
# <name under vendor/>  <path under cloud-u-linux da_my-webserver/src>
VENDORED = {
    "browse.html": "lib/browse.html",
    "github-markdown-dark.css": "lib/github-markdown-dark.css",
    "js-yaml.min.js": "lib/js-yaml.min.js",
    "marked.min.js": "lib/marked.min.js",
    "my-webserver.cjs": "my-webserver.cjs",
}


def die(msg):
    print("::error::FAIL -- " + msg)
    sys.exit(1)


def ok(msg):
    print("  ok   -- " + msg)


def get(url, timeout=60):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.status, r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url")
    ap.add_argument("--bin")
    ap.add_argument("app")
    a = ap.parse_args()
    app = os.path.abspath(a.app)

    ws = json.load(open(os.path.join(app, "build.json")))["runtime"]["webserver"]
    port = ws["port"]
    if ws["binary"] != "fleet-webserver":
        die("%s runtime.webserver.binary is %r, not the shared fleet-webserver" % (app, ws["binary"]))
    ok("%s: fleet-webserver on port %d from build.json::runtime.webserver" % (os.path.basename(app), port))
    if a.url and urllib.parse.urlparse(a.url).port != port:
        die("--url %s is not the declared port %d" % (a.url, port))

    # 2. the bundled frontend IS Linux's, now and at the commit the lib says it synced from
    vendor = os.path.join(LIB, "vendor")
    commit = open(os.path.join(vendor, "SOURCE")).read().split("@")[-1].strip()
    for name, rel in VENDORED.items():
        mine = open(os.path.join(vendor, name), "rb").read()
        for ref in (commit, "main"):
            _, theirs = get("%s/%s/da_my-webserver/src/%s" % (RAW, ref, rel))
            if mine != theirs:
                die("ab_cloud-libs-shared/libs/fleet-webserver/vendor/%s differs from cloud-u-linux %s "
                    "(run libs/fleet-webserver/sync.sh --from <cloud-u-linux>)" % (name, ref if ref == "main" else ref[:9]))
    ok("vendor/ (%d files) == cloud-u-linux da_my-webserver/src at %s and at main" % (len(VENDORED), commit[:9]))
    source = open(os.path.join(vendor, "browse.html"), "rb").read()
    want = source.replace(b"__INIT_PATH__", b'"/"', 1)

    proc, tmp = None, None
    try:
        if not a.url:
            tmp = tempfile.mkdtemp(prefix="ws-ui-check-")
            binp = a.bin
            if not binp:
                if not shutil.which("cargo"):
                    die("cargo is not installed: cannot build the shared webserver")
                subprocess.run(["cargo", "build", "--release", "--locked", "--manifest-path",
                                os.path.join(LIB, "Cargo.toml"), "--target-dir", os.path.join(tmp, "target")],
                               check=True)
                binp = os.path.join(tmp, "target/release/fleet-webserver")
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
            die("served UI sha256 %s != Linux browse.html %s" % (digest, hashlib.sha256(want).hexdigest()))
        ok("served UI == the Linux browse.html (sha256 %s)" % digest)
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
