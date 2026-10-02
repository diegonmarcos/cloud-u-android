# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-compose-ratchet.py ───
#!/usr/bin/env python3
"""Compose ratchet (#773): count the View-based UI each app and shared lib still has,
and fail when a count goes UP — or goes down without the baseline following it.

Every input is 1_cicd/src/data/compose-migration.json. That file declares which classes
make something a screen, which mark it as Compose, which make a class a custom View, and,
per unit, the scope, the first-party roots and the baseline counts. This script owns the
counting rules only.

A UNIT is a top-level app directory (aa_*/ab_*/ac_*) or one shared lib under
ab_cloud-libs-shared/libs/. Units are DISCOVERED, not listed: a unit missing from the plan
has an implicit baseline of zero, so a new app or lib written in Views fails here on its
first push instead of joining the migration backlog unnoticed.

Four counts per unit, all from main source sets (tests, build output and declared upstream
trees are not first-party UI):
  view_screens   screen classes (Activity / Fragment / Dialog / PopupWindow, and any class
                 extending one of those) whose file hosts no Compose
  layout_xml     res/layout*/*.xml files
  view_ui_files  source files that build or inflate Views and host no Compose
  custom_views   classes extending a View type (AbstractComposeView excluded)

Usage:
  cloud-android-compose-ratchet.py                 check every unit, print the report
  cloud-android-compose-ratchet.py --list UNIT     print the files behind UNIT's counts (or `all`)
  cloud-android-compose-ratchet.py --root DIR      scan another tree (the tester's sandbox)
"""
import argparse
import json
import os
import re
import sys

PLAN = "1_cicd/src/data/compose-migration.json"
SELF = "1_cicd/src/scripts/cloud-android-compose-ratchet.py"
METRICS = ("view_screens", "layout_xml", "view_ui_files", "custom_views")
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "z_archive", "test", "tests",
             "androidTest", "testDebug", "testRelease", "sharedTest", "intermediates"}
SOURCE_EXT = (".kt", ".java")

CLASS_KT = re.compile(r"\b(?:class|object)\s+(\w+)")
CLASS_JAVA = re.compile(r"\bclass\s+(\w+)(?:\s*<[^>{]*>)?\s+extends\s+([\w.]+)")
COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
STRING = re.compile(r'"(?:\\.|[^"\\\n])*"')


def strip(text):
    """Comments and string literals out, so a KDoc that mentions `TextView(` counts nothing."""
    return STRING.sub('""', COMMENT.sub("", text))


def kotlin_supertypes(text, start):
    """The supertype names of the class whose name ends at `start`, or []."""
    i, n = start, len(text)

    def skip_ws(j):
        while j < n and text[j] in " \t\r\n":
            j += 1
        return j

    def skip_balanced(j, open_c, close_c):
        depth = 0
        while j < n:
            if text[j] == open_c:
                depth += 1
            elif text[j] == close_c:
                depth -= 1
                if depth == 0:
                    return j + 1
            j += 1
        return j

    i = skip_ws(i)
    if i < n and text[i] == "<":
        i = skip_ws(skip_balanced(i, "<", ">"))
    # `private constructor(...)`, `@Inject constructor(...)`
    m = re.match(r"(?:@\w+\s*)*(?:(?:private|internal|protected|public)\s+)?constructor\s*", text[i:])
    if m:
        i += m.end()
    if i < n and text[i] == "(":
        i = skip_ws(skip_balanced(i, "(", ")"))
    if i >= n or text[i] != ":":
        return []
    i += 1
    names, depth, buf = [], 0, []
    while i < n:
        c = text[i]
        if depth == 0 and c in "{\n" and not "".join(buf).rstrip().endswith(","):
            if c == "{" or "".join(buf).strip():
                break
        if c in "(<":
            depth += 1
        elif c in ")>":
            depth -= 1
        buf.append(c)
        i += 1
    for part in re.split(r",(?![^(<]*[)>])", "".join(buf)):
        m = re.match(r"\s*([\w.]+)", part)
        if m and m.group(1) != "by":
            names.append(m.group(1).split(".")[-1])
    return names


def classes(path, text):
    """(name, [supertypes]) for every class declared in one source file."""
    out = []
    if path.endswith(".java"):
        for m in CLASS_JAVA.finditer(text):
            out.append((m.group(1), [m.group(2).split(".")[-1]]))
        return out
    for m in CLASS_KT.finditer(text):
        out.append((m.group(1), kotlin_supertypes(text, m.end())))
    return out


def walk(root, rel_dirs, excludes):
    """Every main-source-set file under the unit's roots, minus its declared excludes."""
    for rel in rel_dirs:
        base = os.path.join(root, rel)
        if os.path.isfile(base):
            yield rel
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
            relpath = os.path.relpath(dirpath, root)
            if any(relpath == e or relpath.startswith(e + os.sep) for e in excludes):
                dirnames[:] = []
                continue
            for name in sorted(filenames):
                yield os.path.join(relpath, name)


def discover(root, plan):
    """unit id -> directory. Top-level app dirs plus every shared lib."""
    units = {}
    for name in sorted(os.listdir(root)):
        if re.match(plan["unit_dir_pattern"], name) and os.path.isdir(os.path.join(root, name)):
            units[name] = name
    libs = os.path.join(root, plan["shared_libs_dir"])
    if os.path.isdir(libs):
        for name in sorted(os.listdir(libs)):
            if os.path.isdir(os.path.join(libs, name)):
                units["libs:" + name] = os.path.join(plan["shared_libs_dir"], name)
    return units


def scan(root, plan):
    rules = plan["rules"]
    screen_bases = set(rules["screen_bases"])
    view_bases = set(rules["view_bases"])
    compose_host = re.compile(rules["compose_host_marker"])
    compose_code = re.compile(rules["compose_code_marker"])
    view_marker = re.compile(rules["view_builder_marker"])
    view_marker_ambiguous = re.compile(rules["view_builder_marker_ambiguous"])
    screen_marker = re.compile(rules["view_screen_marker"])
    declared = {u["id"]: u for u in plan["units"]}
    units = discover(root, plan)

    files = {}
    for uid, udir in units.items():
        spec = declared.get(uid, {})
        if spec.get("scope") == "out":
            continue
        roots = [os.path.join(udir, r) for r in spec.get("roots", ["."])]
        excludes = [os.path.normpath(os.path.join(udir, e)) for e in spec.get("exclude", [])]
        if udir == os.path.dirname(plan["shared_libs_dir"]):
            excludes.append(plan["shared_libs_dir"])  # each lib is its own unit
        rows = []
        for rel in walk(root, [os.path.normpath(r) for r in roots], excludes):
            if rel.endswith(SOURCE_EXT):
                with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as fh:
                    text = strip(fh.read())
                rows.append((rel, text))
            elif re.search(r"(^|/)res/layout[^/]*/[^/]+\.xml$", rel):
                rows.append((rel, None))
        files[uid] = rows

    def closure(parents, seed):
        kinds = set(seed)
        grew = True
        while grew:
            grew = False
            for name, supers in parents.items():
                if name not in kinds and supers & kinds:
                    kinds.add(name)
                    grew = True
        return kinds

    # Inheritance is resolved INSIDE one unit. Simple names collide across a fleet this size
    # (matrix alone declares dozens of `Error` classes), and a fleet-wide name map once made a
    # sealed `Error` result type a screen. A base class shared ACROSS units is named in the plan.
    result = {}
    for uid, rows in files.items():
        parents = {}
        for rel, text in rows:
            if text is not None:
                for name, supers in classes(rel, text):
                    parents.setdefault(name, set()).update(supers)
        # A fork's own screens extend the upstream's base classes, which live outside the
        # unit's first-party roots; the plan names those bases per unit.
        screens = closure(parents, screen_bases | set(declared.get(uid, {}).get("screen_bases", [])))
        views = closure(parents, view_bases) - closure(parents, {"AbstractComposeView"})

        found = {m: [] for m in METRICS}
        for rel, text in rows:
            if text is None:
                found["layout_xml"].append(rel)
                continue
            hosts_compose = bool(compose_host.search(text))
            is_compose = hosts_compose or bool(compose_code.search(text))
            # Button( / Switch( / Chip( name a Compose function as readily as a View, so they
            # count only in a file with no Compose code in it.
            draws_views = bool(view_marker.search(text)) or (
                not is_compose and bool(view_marker_ambiguous.search(text)))
            for name, supers in classes(rel, text):
                if set(supers) & views:
                    found["custom_views"].append("%s %s" % (rel, name))
                # A screen counts while its file still puts Views on it — a fragment that hosts
                # one ComposeView among forty hand-built rows is not migrated. A trampoline
                # Activity that finishes in onCreate has no UI to migrate and never counts.
                if set(supers) & screens and (
                        draws_views or (not hosts_compose and screen_marker.search(text))):
                    found["view_screens"].append("%s %s" % (rel, name))
            if draws_views:
                found["view_ui_files"].append(rel)
        result[uid] = found
    return units, declared, result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.normpath(os.path.join(os.path.dirname(__file__), "../../..")))
    ap.add_argument("--list", metavar="UNIT")
    args = ap.parse_args()

    with open(os.path.join(args.root, PLAN), encoding="utf-8") as fh:
        plan = json.load(fh)
    units, declared, result = scan(args.root, plan)

    unknown = sorted(set(declared) - set(units))
    # A root that no longer exists counts nothing, which reads exactly like a finished
    # migration. A file moved by an upstream bump must fail here, not go quietly green.
    missing = ["%s: root %s does not exist — fix units[%s].roots in %s" % (uid, r, uid, PLAN)
               for uid, spec in declared.items() if uid in units and spec.get("scope") != "out"
               for r in spec.get("roots", [])
               if not os.path.exists(os.path.join(args.root, units[uid], r))]
    if args.list:
        for uid in sorted(result) if args.list == "all" else [args.list]:
            print("== " + uid)
            for metric, items in result.get(uid, {}).items():
                print("%s (%d)" % (metric, len(items)))
                for item in items:
                    print("  " + item)
        return 0

    errors = list(missing)
    for uid in unknown:
        errors.append("%s: declared in %s but no such unit exists — delete its entry" % (uid, PLAN))

    lines = ["| unit | scope | " + " | ".join(METRICS) + " |",
             "|---|---|" + "---|" * len(METRICS)]
    totals = dict.fromkeys(METRICS, 0)
    for uid in sorted(units):
        spec = declared.get(uid, {})
        scope = spec.get("scope", "undeclared")
        if scope == "out":
            lines.append("| %s | out | %s |" % (uid, " | ".join("-" for _ in METRICS)))
            continue
        counts = {m: len(result[uid][m]) for m in METRICS}
        baseline = spec.get("baseline", {})
        cells = []
        for m in METRICS:
            now, was = counts[m], baseline.get(m, 0)
            totals[m] += now
            cells.append(str(now) if now == was else "%d (baseline %d)" % (now, was))
            if now > was:
                errors.append("%s: %s went UP %d -> %d. New UI is Compose; run `%s --list %s` to see "
                              "what counts." % (uid, m, was, now, SELF, uid))
            elif now < was:
                errors.append("%s: %s went down %d -> %d — tighten the ratchet: set units[%s].baseline.%s "
                              "to %d in %s." % (uid, m, was, now, uid, m, now, PLAN))
        if any(counts.values()) or uid in declared:
            lines.append("| %s | %s | %s |" % (uid, scope, " | ".join(cells)))
    lines.append("| **total** | | %s |" % " | ".join(str(totals[m]) for m in METRICS))

    report = "## Compose ratchet — View-based UI still in the fleet\n\n" + "\n".join(lines) + "\n"
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(report)
    for e in errors:
        print("::error::" + e)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
