#!/usr/bin/env python3
"""Shrink-keep guard: every by-name resource lookup in a resource-shrinking module has a keep rule.

WHY. Cloud Notes crashed at launch (fixed in 1b01309f): MainActivity resolved its bottom-nav
icons with getIdentifier("ic_nav_$name", "drawable", ...). The release build's shrinkResources
cannot see a name assembled at run time, stripped every ic_nav_* drawable, getIdentifier
returned 0 and painterResource(0) threw. Nothing in CI connected "this module looks resources up
by name" with "this module shrinks resources".

WHAT. For every gradle script that turns shrinkResources on (shrinkResources true,
isShrinkResources = true / = !flag), the module directory plus every :libs:X it depends on
(transitively, ab_cloud-libs-shared/libs/X) is scanned for getIdentifier(...) calls in main
sources. Each call's name argument is turned into a pattern:
    "ic_nav_$name" / "ic_nav_${x}" / "ic_" + x   ->  @<type>/ic_nav_*  (resp. ic_*)
    "onboarding_logo"                           ->  @<type>/onboarding_logo
A call whose name is not a string literal (a variable) cannot be read, so it must carry a
    // shrink-keep: @drawable/foo_* @string/bar
comment on one of the 6 lines above it naming what it can resolve. Calls into the "android"
package are framework resources and are skipped.

Every pattern must be covered by a tools:keep glob in a res/raw/*.xml (any */raw/*.xml) of the
module or of one of its libs. tools:shrinkMode="strict" does not excuse a missing keep: strict
mode is exactly the mode in which by-name lookups lose their resources.

Exit 1 naming each uncovered lookup. Usage: cloud-android-shrink-keep-guard.py [ROOT]
"""
import fnmatch
import os
import re
import sys

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else
                       os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
LIBS = os.path.join(ROOT, "ab_cloud-libs-shared", "libs")
SKIP_DIRS = {".git", "build", "node_modules", "test", "androidTest", "testDebug", "testRelease",
             "intermediates", ".gradle", "dist", "jvmTest", "commonTest", "androidUnitTest"}
GRADLE_NAMES = ("build.gradle", "build.gradle.kts", "build-extras.gradle")
SHRINK_RE = re.compile(r"(?m)^[^/\n]*\b(?:shrinkResources\s+true|isShrinkResources\s*=\s*(?:true|!))")
LIB_RE = re.compile(r"""project\(\s*['"]:libs:([\w.-]+)['"]""")
CALL_RE = re.compile(r"""getIdentifier\(\s*(.+?)\s*,\s*"(\w+)"\s*,\s*([^)]*?)\s*\)""")
ANNOT_RE = re.compile(r"shrink-keep:\s*((?:@\w+/[\w*]+[\s,]*)+)")
KEEP_RE = re.compile(r"""tools:keep\s*=\s*"([^"]*)\"""")


def walk(top):
    for d, dirs, files in os.walk(top):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for f in files:
            yield os.path.join(d, f)


def read(p):
    try:
        with open(p, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def lib_closure(gradle_text):
    seen, todo = [], LIB_RE.findall(gradle_text)
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        d = os.path.join(LIBS, name)
        if not os.path.isdir(d):
            continue
        seen.append(name)
        for g in ("build.gradle", "build.gradle.kts"):
            todo += LIB_RE.findall(read(os.path.join(d, g)))
    return [os.path.join(LIBS, n) for n in sorted(seen)]


def name_pattern(arg):
    """'"ic_nav_$name"' -> 'ic_nav_*'; '"ic_" + x' -> 'ic_*'; a non-literal -> None."""
    m = re.match(r'^"((?:[^"\\]|\\.)*)"\s*(\+.*)?$', arg)
    if not m:
        return None
    lit = re.sub(r"\$\{[^}]*\}|\$\w+", "*", m.group(1))
    if m.group(2):
        lit += "*"
    return re.sub(r"\*+", "*", lit)


def calls(dirs):
    for top in dirs:
        for p in walk(top):
            if not p.endswith((".kt", ".java")):
                continue
            lines = read(p).splitlines()
            for i, line in enumerate(lines):
                s = line.strip()
                if s.startswith(("//", "*", "/*")) or "getIdentifier(" not in s:
                    continue
                m = CALL_RE.search(line)
                where = "%s:%d" % (os.path.relpath(p, ROOT), i + 1)
                above = "\n".join(lines[max(0, i - 6):i + 1])
                ann = ANNOT_RE.search(above)
                if ann:
                    yield where, re.findall(r"@\w+/[\w*]+", ann.group(1)), None
                    continue
                if not m:
                    yield where, [], "getIdentifier call this guard cannot parse; add a // shrink-keep: comment"
                    continue
                arg, rtype, pkg = m.groups()
                if pkg.strip() == '"android"':
                    continue
                pat = name_pattern(arg)
                if pat is None:
                    yield where, [], ("name %s is not a string literal; add a // shrink-keep: @%s/<pattern> "
                                      "comment above it" % (arg, rtype))
                    continue
                yield where, ["@%s/%s" % (rtype, pat)], None


def keeps(dirs):
    out = []
    for top in dirs:
        for p in walk(top):
            if p.endswith(".xml") and os.path.basename(os.path.dirname(p)) == "raw":
                for v in KEEP_RE.findall(read(p)):
                    out += [(g.strip(), os.path.relpath(p, ROOT)) for g in v.split(",") if g.strip()]
    return out


def covered(required, globs):
    return any(fnmatch.fnmatchcase(required, g) for g, _ in globs)


def main():
    failures, report = [], []
    for top in sorted(os.listdir(ROOT)):
        if top.startswith(".") or not os.path.isdir(os.path.join(ROOT, top)):
            continue
        for p in walk(os.path.join(ROOT, top)):
            if os.path.basename(p) not in GRADLE_NAMES:
                continue
            text = read(p)
            if not SHRINK_RE.search(text):
                continue
            module = os.path.dirname(p)
            dirs = [module] + lib_closure(text)
            globs = keeps(dirs)
            found = list(calls(dirs))
            report.append("%s: shrinks resources; %d by-name lookup(s), keep globs %s" % (
                os.path.relpath(p, ROOT), len(found), sorted({g for g, _ in globs}) or "none"))
            for where, required, problem in found:
                if problem:
                    failures.append("%s [%s]: %s" % (where, os.path.relpath(p, ROOT), problem))
                    continue
                for r in required:
                    if not covered(r, globs):
                        failures.append(
                            "%s [%s]: resolves %s by name but no tools:keep in a res/raw/*.xml of the module "
                            "or its libs covers it; shrinkResources will strip it and getIdentifier returns 0"
                            % (where, os.path.relpath(p, ROOT), r))
    print("\n".join(report))
    if failures:
        for f in failures:
            print("::error::" + f)
        print("shrink-keep guard: %d uncovered by-name lookup(s)" % len(failures))
        return 1
    print("shrink-keep guard: every by-name lookup in a shrinking module has a keep rule")
    return 0


if __name__ == "__main__":
    sys.exit(main())
