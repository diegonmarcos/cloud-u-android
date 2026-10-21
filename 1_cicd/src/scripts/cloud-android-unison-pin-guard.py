#!/usr/bin/env python3
"""cloud-android-unison-pin-guard.py -- #1084 ONE Unison, the same version on every peer.

Unison refuses a client and a server of different versions and distrusts an archive another version
wrote, so the phone, the desktop home-manager and every fleet host must run the SAME release. The pin is
ab_cloud-libs-shared/libs/rclone-engine/data/unison-binary.json (`version`, `ocaml`); every peer declares
its own copy in its own repository (a small JSON the peer's Nix module reads), and this guard fails when:

  U1  the pin is malformed: version / ocaml are not X.Y.Z, an ABI's tarball or binary sha256 is not
      64-hex, a URL or directory does not carry the pinned version, interp is not "none";
  U2  the engine's build.gradle does not read the pin (a literal version in gradle is a second pin) or
      does not verify both hashes and PT_INTERP;
  U3  the arm64 build workflow does not read the pin, or the pinned arm64 URL is not a release the
      workflow publishes for that version;
  U4  a peer's declared version or ocaml differs from the pin, a peer file cannot be read, or the peer
      is not a JSON with both fields.

Peers are read from a checkout root (--peers-root DIR, laid out DIR/<repo>/<path>). A peer whose
checkout is absent is a FAILURE when --require-peers is given (a peer it cannot read is a peer it cannot
vouch for) and a NOTE otherwise: the private repositories run the same comparison from their own CI with
`peer-check` against this repository's public pin.

Usage:
  cloud-android-unison-pin-guard.py check <repo root> [--peers-root DIR] [--require-peers]
  cloud-android-unison-pin-guard.py peer-check <peer json> <pin json>
"""
import json
import os
import re
import sys

PIN = "ab_cloud-libs-shared/libs/rclone-engine/data/unison-binary.json"
GRADLE = "ab_cloud-libs-shared/libs/rclone-engine/build.gradle"
WORKFLOW = ".github/workflows/build-unison-static.yml"
X3 = re.compile(r"\d+\.\d+\.\d+")
HEX = re.compile(r"[0-9a-f]{64}")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def pin_problems(pin, root):
    bad = []
    v, o = pin.get("version", ""), pin.get("ocaml", "")
    if not X3.fullmatch(v):
        bad.append("U1 pin version %r is not X.Y.Z" % v)
    if not X3.fullmatch(o):
        bad.append("U1 pin ocaml %r is not X.Y.Z" % o)
    if pin.get("interp") != "none":
        bad.append("U1 pin interp must be 'none' (a static binary), got %r" % pin.get("interp"))
    if not re.fullmatch(r"lib[\w.-]+\.so", pin.get("jni_name", "")):
        bad.append("U1 pin jni_name must be lib*.so, got %r" % pin.get("jni_name"))
    bins = pin.get("binaries", {})
    for abi in ("arm64-v8a", "x86_64"):
        b = bins.get(abi)
        if not b:
            bad.append("U1 pin has no %s binary" % abi)
            continue
        for k in ("tar_sha256", "binary_sha256"):
            if not HEX.fullmatch(b.get(k, "")):
                bad.append("U1 pin %s.%s is not a 64-hex sha256" % (abi, k))
        for k in ("url", "dir"):
            if v and v not in b.get(k, ""):
                bad.append("U1 pin %s.%s does not carry version %s: %r" % (abi, k, v, b.get(k)))
    # U2 the gradle reads the pin and checks it
    g = open(os.path.join(root, GRADLE), encoding="utf-8").read()
    if "unison-binary.json" not in g:
        bad.append("U2 %s does not read unison-binary.json" % GRADLE)
    if v and v in "".join(l for l in g.splitlines() if not l.strip().startswith(("//", "*"))):
        bad.append("U2 %s names the Unison version %s literally: that is a second pin" % (GRADLE, v))
    for needle in ("tar_sha256", "binary_sha256", "hasProgramInterpreter"):
        if needle not in g:
            bad.append("U2 %s does not use %s of the pin" % (GRADLE, needle))
    # U3 the arm64 build
    wf = open(os.path.join(root, WORKFLOW), encoding="utf-8").read()
    if "unison-binary.json" not in wf or ".version" not in wf or ".ocaml" not in wf:
        bad.append("U3 %s does not read version and ocaml from the pin" % WORKFLOW)
    arm = bins.get("arm64-v8a", {})
    if v and not re.search(r"/releases/download/unison-static-%s-r\d+/" % re.escape(v), arm.get("url", "")):
        bad.append("U3 the arm64 URL is not a release unison-static-%s-rN that %s publishes: %r" % (v, WORKFLOW, arm.get("url")))
    if "unison-static-${VER}-${REV}" not in wf:
        bad.append("U3 %s does not publish the tag unison-static-<version>-<revision>" % WORKFLOW)
    return bad


def compare(peer_json, pin, where):
    bad = []
    if not isinstance(peer_json, dict) or "version" not in peer_json or "ocaml" not in peer_json:
        return ["U4 %s: not a JSON with both `version` and `ocaml`" % where]
    for k in ("version", "ocaml"):
        if peer_json[k] != pin[k]:
            bad.append("U4 %s declares unison %s %r, the pin says %r" % (where, k, peer_json[k], pin[k]))
    return bad


def check(root, peers_root, require):
    pin = load(os.path.join(root, PIN))
    bad = pin_problems(pin, root)
    notes = []
    peers = pin.get("peers", [])
    if not peers:
        bad.append("U4 the pin lists no peer: nothing holds the other machines to this version")
    for p in peers:
        where = "%s %s:%s" % (p.get("id"), p.get("repo"), p.get("path"))
        path = os.path.join(peers_root, p["repo"], p["path"]) if peers_root else None
        if not path or not os.path.isfile(path):
            (bad if require else notes).append(("U4 " if require else "NOTE ") + "peer %s is not readable here" % where)
            continue
        try:
            bad += compare(load(path), pin, where)
        except ValueError as e:
            bad.append("U4 %s is not JSON: %s" % (where, e))
    return pin, bad, notes


def main(argv):
    if len(argv) >= 3 and argv[1] == "check":
        args = argv[3:]
        peers_root = args[args.index("--peers-root") + 1] if "--peers-root" in args else None
        pin, bad, notes = check(argv[2], peers_root, "--require-peers" in args)
        for n in notes:
            print(n)
        for b in bad:
            print("FAIL  " + b)
        if bad:
            return 1
        print("PASS  unison %s (ocaml %s): pin well-formed, built and read from one place, %d peer(s) declared"
              % (pin["version"], pin["ocaml"], len(pin.get("peers", []))))
        return 0
    if len(argv) == 4 and argv[1] == "peer-check":
        bad = compare(load(argv[2]), load(argv[3]), argv[2])
        for b in bad:
            print("FAIL  " + b)
        if not bad:
            print("PASS  %s matches the pin" % argv[2])
        return 1 if bad else 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
