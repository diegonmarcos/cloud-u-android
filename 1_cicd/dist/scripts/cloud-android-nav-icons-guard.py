# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-nav-icons-guard.py ───
#!/usr/bin/env python3
"""
cloud-android-nav-icons-guard — #1160: every nav-bar icon of every app comes from ONE catalog
(libs:ui-icons UiIcons), never from a material icon an app picked for itself.

DECLARATION  1_cicd/src/data/nav-icons.json (catalog path, semantic names, entry points, exceptions)

  I1  the catalog parses, holds the semantic names (inbox, profile, configs, ai, search,
      settings, ...) and the brand (untinted) entries, and has no empty table
  I2  a NAV DECLARATION is an Android source (app or shared lib, not the two definer libs, not a test,
      not a wasm page) that builds nav items: it calls `islandEntries`, `navEntry(`,
      `BottomNavEntry(` or `viewItems(`. In it:
        - nothing builds items from a lambda or by hand: no `islandEntries {` / `islandEntries(` with an
          argument (the wasm-only overload), no `BottomNavEntry(` constructor, no `viewItems(`
          (the drawable-id feed); the items come from `islandEntries()` or `navEntry(`, i.e. from the catalog;
        - a nav file proper (its name contains "Nav": MailBottomNav.kt, C3BottomNav.kt, ...) imports no
          `androidx.compose.material.icons` and draws no `Icons.*` glyph.
      Comments are blanked first. An exception (nav-icons.json::exceptions) names the file and the reason.
  I3  every ui.sections[].icon (the bar) and every NON-BLANK ui.sections[].pages[].icon (any depth) of an app
      that has a nav declaration is a catalog name (a blank page icon is #1157's rule, held by SubPageIconGuardTest)
  I4  an exception names a file that exists and that really breaks I2 (it cannot outlive its reason)
  I5  vacuity: at least one nav declaration was found and checked

USAGE  cloud-android-nav-icons-guard.py [ROOT]
EXIT   0 ok · 1 at least one violation
"""
import glob, json, os, re, sys

DATA = "1_cicd/src/data/nav-icons.json"
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".cxx", "test", "tests", "androidTest", "testDebug", "web"}
ENTRY = re.compile(r"\bislandEntries\b|\bnavEntry\s*\(|\bBottomNavEntry\s*\(|\bviewItems\b")


def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), t, flags=re.S)
    return re.sub(r"//[^\n]*", "", t)


# The same places the workflow's path filter names (guard-triggers.json), so the filter and the reads cannot drift.
ROOTS = ("*/app/src/main", "*/src/main", "*/hub/src/main", "ac_cloud-notes/packages/frontend/apps/android/App",
         "ab_cloud-libs-shared/libs/*/src/main")


def kotlin_sources(root):
    seen = set()
    for pat in ROOTS:
        for base in glob.glob(os.path.join(root, pat)):
            for dirpath, dirs, files in os.walk(base):
                dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
                for f in files:
                    full = os.path.join(dirpath, f)
                    if f.endswith(".kt") and full not in seen:
                        seen.add(full)
                        yield full


def violations(code, nav_file):
    """The I2 breaches in one nav declaration's blanked source."""
    out = []
    if nav_file and re.search(r"^\s*import\s+androidx\.compose\.material\.icons\b", code, re.M):
        out.append("imports a material icon (androidx.compose.material.icons)")
    if nav_file and re.search(r"\bIcons\.(Filled|Outlined|Rounded|Sharp|TwoTone|AutoMirrored|Default)\b", code):
        out.append("draws a material icon (Icons.*)")
    if re.search(r"\bislandEntries\s*(\{|\(\s*[^)\s])", code):
        out.append("builds items with the lambda islandEntries overload (a glyph the app picked)")
    if re.search(r"\bBottomNavEntry\s*\(", code):
        out.append("constructs a BottomNavEntry by hand (use islandEntries() or navEntry())")
    if re.search(r"\bviewItems\b", code):
        out.append("feeds the bar drawable ids through viewItems (use islandEntries())")
    return out


def app_of(rel, spec):
    for prefix, app in spec.get("lib_apps", {}).items():
        if rel.startswith(prefix):
            return app
    return rel.split("/")[0]


def sections_icons(bj):
    ui = (bj.get("ui") or {})
    out = []

    def walk(ps, path):
        for p in ps or []:
            out.append((path + p.get("id", "?"), p.get("icon", "")))
            walk(p.get("pages"), path + p.get("id", "?") + "/")

    for s in ui.get("sections") or []:
        out.append((s.get("id", "?"), s.get("icon", "")))
        walk(s.get("pages"), s.get("id", "?") + "/")
    return out


def main(root):
    bad = []
    spec = json.load(open(os.path.join(root, DATA), encoding="utf-8"))
    cat_path = os.path.join(root, spec["catalog"])
    if not os.path.isfile(cat_path):
        print("  FAIL  I1 the icon catalog %s does not exist" % spec["catalog"])
        return 1
    cat = strip_comments(open(cat_path, encoding="utf-8").read())
    names = set(re.findall(r'"([\w-]+)"\s+to\s+', cat))
    for n in spec["semantic"]:
        if n not in names:
            bad.append("I1 the catalog lacks the semantic name %r" % n)
    for n in spec["brand"]:
        brand_block = cat[cat.find("brandTable"):] if "brandTable" in cat else ""
        if not re.search(r'"%s"\s+to\s+' % re.escape(n), brand_block):
            bad.append("I1 %r is not a brand (untinted) entry of the catalog" % n)
    if len(names) < 100:
        bad.append("I1 the catalog holds only %d names, this guard is reading the wrong table" % len(names))

    exceptions = spec.get("exceptions", {})
    definers = tuple(spec["definers"])
    decls = []
    for src in sorted(kotlin_sources(root)):
        rel = os.path.relpath(src, root).replace(os.sep, "/")
        if rel.startswith(definers):
            continue
        code = strip_comments(open(src, encoding="utf-8", errors="replace").read())
        if ENTRY.search(code):
            decls.append((rel, code))

    seen_apps = set()
    checked = 0
    for rel, code in decls:
        v = violations(code, "Nav" in os.path.basename(rel))
        if rel in exceptions:
            if not v:
                bad.append("I4 exception %s no longer breaks I2: delete it from nav-icons.json" % rel)
            continue
        checked += 1
        for x in v:
            bad.append("I2 %s %s" % (rel, x))
        seen_apps.add(app_of(rel, spec))
    for rel in exceptions:
        if not os.path.isfile(os.path.join(root, rel)):
            bad.append("I4 exception %s names a file that does not exist" % rel)

    for app in sorted(a for a in seen_apps if a.startswith(("ac_", "aa_"))):
        bjp = os.path.join(root, app, "build.json")
        if not os.path.isfile(bjp):
            continue
        for where, icon in sections_icons(json.load(open(bjp, encoding="utf-8"))):
            if (icon or "/" not in where) and icon not in names:
                bad.append("I3 %s ui.sections %s icon %r is not in the libs:ui-icons catalog" % (app, where, icon))
    if checked == 0:
        bad.append("I5 no nav declaration was found: this guard is unrun, not passing")

    for b in bad:
        print("  FAIL  " + b)
    print("nav-icons guard: %d nav declaration(s) checked, %d catalog names, %d violation(s)" % (checked, len(names), len(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")))
