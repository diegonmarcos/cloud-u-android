#!/usr/bin/env python3
"""Derive both sides of cloud-code's Cordova bridge (#562, the #543 trap).

NEEDED     every service name the web bundle hands the bridge:
           exec(ok, err, SERVICE, action, args) / cordova.exec(...), SERVICE a
           string literal or a same-file `const NAME = '...'`.
REGISTERED every <feature name> in the plugin.xml of a plugin that the build
           installs: package.json::cordova.plugins (what `cordova prepare`
           restores) minus build.json::upstream.flavor.removed_plugins, each
           mapped to its directory through package.json's file: dependency.
Calls made from INSIDE a removed plugin's own directory are not needed: that
JavaScript is never loaded when the plugin is not installed.

Prints one line per fact; the shell tester decides. No service name is written
here (both sides are derived), so a missing native side cannot be papered over
by editing a list.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
build = json.load(open(os.path.join(root, "build.json")))
pkg = json.load(open(os.path.join(root, "package.json")))
removed = set(build["upstream"]["flavor"]["removed_plugins"])
web_roots = build["tests"]["cordova_surface"]["web_roots"]

deps = {}
deps.update(pkg.get("dependencies", {}))
deps.update(pkg.get("devDependencies", {}))

# ── REGISTERED ───────────────────────────────────────────────────────────────
installed = [p for p in pkg["cordova"]["plugins"] if p not in removed]
registered = {}          # service -> plugin id
removed_dirs = []
unmapped = []
for pid in pkg["cordova"]["plugins"]:
    spec = deps.get(pid, "")
    if not spec.startswith("file:"):
        if pid not in removed:
            unmapped.append(pid)  # a registry plugin: its plugin.xml is not in this tree
        continue
    pdir = os.path.join(root, spec[len("file:"):])
    if pid in removed:
        removed_dirs.append(os.path.normpath(pdir))
        continue
    xml = os.path.join(pdir, "plugin.xml")
    if not os.path.isfile(xml):
        print("ERROR plugin %s has no plugin.xml at %s" % (pid, os.path.relpath(xml, root)))
        raise SystemExit(2)
    for feat in ET.parse(xml).getroot().iter():
        if feat.tag.endswith("feature") and feat.get("name"):
            registered.setdefault(feat.get("name"), pid)

# ── NEEDED ───────────────────────────────────────────────────────────────────
ALIAS = re.compile(r"""([A-Za-z_$][\w$]*)\s*=\s*(?:cordova\s*\.\s*)?require\s*\(\s*['"]cordova/exec['"]\s*\)""")
CONST = re.compile(r"""\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*['"]([^'"]+)['"]""")


def split_args(text, start):
    """Top-level arguments of the call whose '(' is at text[start-1]."""
    args, depth, cur, i, quote = [], 0, [], start, None
    while i < len(text):
        c = text[i]
        if quote:
            cur.append(c)
            if c == "\\":
                cur.append(text[i + 1]); i += 2; continue
            if c == quote:
                quote = None
        elif c in "'\"`":
            quote = c; cur.append(c)
        elif c in "([{":
            depth += 1; cur.append(c)
        elif c in ")]}":
            if depth == 0:
                args.append("".join(cur).strip()); return args
            depth -= 1; cur.append(c)
        elif c == "," and depth == 0:
            args.append("".join(cur).strip()); cur = []
        else:
            cur.append(c)
        i += 1
    return args


needed, unresolved, files, skipped = {}, [], 0, []
for wr in web_roots:
    for dirpath, dirs, names in os.walk(os.path.join(root, wr)):
        dirs[:] = [d for d in dirs if d not in ("node_modules", "android", "ios")]
        if "plugin.xml" in names:
            pid = ET.parse(os.path.join(dirpath, "plugin.xml")).getroot().get("id")
            if pid not in installed:
                skipped.append("%s (%s)" % (os.path.relpath(dirpath, root), pid))
                dirs[:] = []
                continue
        for n in names:
            if not n.endswith((".js", ".ts", ".mjs")):
                continue
            path = os.path.join(dirpath, n)
            text = open(path, encoding="utf-8", errors="replace").read()
            files += 1
            consts = dict(CONST.findall(text))
            # The bridge is cordova.exec, or a name bound to require("cordova/exec")
            # in this file. A local helper that merely CALLS the bridge (pluginContext's
            # exec(resolve, reject, action, args)) is not the bridge: its own call site
            # is found instead, with the real service name.
            names = ["cordova\\s*\\.\\s*exec"] + [re.escape(a) for a in ALIAS.findall(text)]
            call = re.compile(r"(?<![\w$.])(?:%s)\s*\(" % "|".join(names))
            for m in call.finditer(text):
                args = split_args(text, m.end())
                if len(args) < 4:
                    continue  # acode.exec("run"), RegExp#exec — not the bridge
                svc = args[2]
                lit = re.fullmatch(r"""['"]([^'"]+)['"]""", svc)
                name = lit.group(1) if lit else consts.get(svc)
                rel = os.path.relpath(path, root)
                if name:
                    needed.setdefault(name, rel)
                else:
                    unresolved.append("%s: %s" % (rel, svc))

if files < 100 or len(needed) < 5 or len(registered) < 5:
    print("ERROR derivation is empty: %d files, %d needed, %d registered" % (files, len(needed), len(registered)))
    raise SystemExit(2)

for svc in sorted(needed):
    if svc in registered:
        print("COVERED %s -> %s" % (svc, registered[svc]))
    else:
        print("MISSING %s (called from %s) is registered by no installed plugin" % (svc, needed[svc]))
for u in unresolved:
    print("DYNAMIC %s" % u)
for d in skipped:
    print("NOTLOADED %s — not installed by this flavour, its JavaScript never runs" % d)
for pid in unmapped:
    print("REGISTRY %s (npm plugin, plugin.xml not vendored)" % pid)
print("DERIVED %d files, %d service names needed, %d registered by %d installed plugins, %d removed by the flavour"
      % (files, len(needed), len(registered), len(installed), len(removed)))
