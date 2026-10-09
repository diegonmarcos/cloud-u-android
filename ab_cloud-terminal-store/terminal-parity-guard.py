#!/usr/bin/env python3
"""Parity guard: the two Cloud Terminals ship the same CLIs, greeting and startup steps.

ac_cloud-termux (Termux) and ac_cloud-nix-on-droid (Nix-on-Droid) are two apps that stay separate,
and they share one declaration: ab_cloud-terminal-store/store.json. This guard fails when one of
them lacks something the other has, in the three places that can drift:

  CLIs and greeting   store.json::linux_tools.files is the only list. Each terminal must be baked
                      by the shared fetcher (fetch-linux-tools.py, which refuses any byte that is
                      not the pinned sha256) and must VERIFY every entry by name in the files it
                      declares as terminals.<t>.linux_tools.verified_by.
  startup steps       store.json::startup.steps is the only list. Every `startup-step: <id>` marker
                      comment in terminals.<t>.startup_files is a step that terminal implements; the
                      two sets must equal the declared one. A step added to one terminal only, or
                      dropped from one, is red -- and so is a declared step nobody implements.
  the shared wiring   login-init.sh (the PATH, greeting path, store and first-start steps) must be
                      among both terminals' startup files and be carried by both bakes.

    terminal-parity-guard.py [ROOT]        ROOT defaults to the repository this file is in
    exit 0 holds, 1 names every problem, 2 usage

test-terminal-parity.sh proves each rule with a mutation. Static and offline.
"""
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MARKER = re.compile(r"startup-step:\s*([a-z][a-z0-9_]*)")


def _fetcher(root):
    path = os.path.join(root, "ab_cloud-terminal-store", "fetch-linux-tools.py")
    spec = importlib.util.spec_from_file_location("fetch_linux_tools", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _read(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as handle:
        return handle.read()


def problems_of(root):
    problems = []
    store_path = os.path.join(root, "ab_cloud-terminal-store", "store.json")
    with open(store_path, encoding="utf-8") as handle:
        decl = json.load(handle)

    # the declaration itself: one pin, a sha256 per file, deps in the toolset, sane install paths
    try:
        block = _fetcher(root).check(decl)
    except SystemExit as error:
        return ["declaration: %s" % error]

    terminals = decl.get("terminals", {})
    names = sorted(terminals)
    if len(names) != 2:
        problems.append("store.json declares %d terminals (%s), the guard compares exactly two" % (len(names), ", ".join(names)))
        return problems

    # ── CLIs and greeting: baked by the shared fetcher, verified by name, in BOTH ──
    tokens = {name: os.path.basename(f["install"]) for name, f in block["files"].items()}
    for t in names:
        spec = terminals[t].get("linux_tools") or {}
        baker, verifiers = spec.get("baked_by"), spec.get("verified_by") or []
        if not baker or not verifiers:
            problems.append("%s: terminals.%s.linux_tools needs baked_by and verified_by" % (t, t))
            continue
        for rel in [baker] + verifiers:
            if not os.path.isfile(os.path.join(root, rel)):
                problems.append("%s: %s is declared but does not exist" % (t, rel))
        if not os.path.isfile(os.path.join(root, baker)):
            continue
        text = _read(root, baker)
        if "fetch-linux-tools.py" not in text:
            problems.append("%s: %s does not use fetch-linux-tools.py, so its CLIs and greeting are not the pinned, "
                            "sha256-checked bytes store.json::linux_tools declares" % (t, baker))
        if "login-init.sh" not in text:
            problems.append("%s: %s does not carry login-init.sh, which holds the PATH, greeting and first-start steps" % (t, baker))
        verify_text = "\n".join(_read(root, rel) for rel in verifiers if os.path.isfile(os.path.join(root, rel)))
        for name, token in sorted(tokens.items()):
            if token not in verify_text:
                problems.append("%s: no verifier (%s) names %s, so %s %s could vanish from this terminal unnoticed "
                                "while the other still has it" % (t, ", ".join(verifiers), token,
                                                                  "the CLI" if block["files"][name]["role"] == "cli" else "the greeting", name))

    # ── startup steps: the declared set == each terminal's marker set ──
    declared = {k for k in decl.get("startup", {}).get("steps", {}) if not k.startswith("_")}
    if not declared:
        problems.append("store.json::startup.steps is empty")
    found = {}
    for t in names:
        files = terminals[t].get("startup_files") or []
        if "ab_cloud-terminal-store/login-init.sh" not in files:
            problems.append("%s: startup_files does not list ab_cloud-terminal-store/login-init.sh, the shared steps' home" % t)
        marks = {}
        for rel in files:
            if not os.path.isfile(os.path.join(root, rel)):
                problems.append("%s: startup file %s does not exist" % (t, rel))
                continue
            for step in MARKER.findall(_read(root, rel)):
                marks.setdefault(step, rel)
        found[t] = marks
        for step in sorted(declared - set(marks)):
            problems.append("%s: startup step %s is declared but this terminal implements no `startup-step: %s` marker" %
                            (t, step, step))
        for step in sorted(set(marks) - declared):
            problems.append("%s: %s carries `startup-step: %s`, which store.json::startup.steps does not declare "
                            "(declare it and implement it in %s too, or remove it)" %
                            (t, marks[step], step, [x for x in names if x != t][0]))
    a, b = names
    for step in sorted(set(found[a]) ^ set(found[b])):
        have, lack = (a, b) if step in found[a] else (b, a)
        problems.append("parity: %s has startup step %s (%s) and %s does not" % (have, step, found[have][step], lack))
    return problems


def main(argv):
    if argv and argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if len(argv) > 1:
        print(__doc__, file=sys.stderr)
        return 2
    root = os.path.abspath(argv[0]) if argv else os.path.dirname(HERE)
    problems = problems_of(root)
    for p in problems:
        print("FAIL: %s" % p, file=sys.stderr)
    if problems:
        print("FAIL: terminal parity: %d problem(s) -- the two Cloud Terminals have drifted" % len(problems), file=sys.stderr)
        return 1
    print("OK: terminal parity -- both terminals bake and verify the same CLIs and greeting, and implement the same startup steps")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
