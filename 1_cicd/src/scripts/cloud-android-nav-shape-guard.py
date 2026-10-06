#!/usr/bin/env python3
"""
cloud-android-nav-shape-guard — #868: every app's navigation is ONE declaration
(build.json::ui) drawn by ONE library (libs:bottomnav), and an app cannot quietly
grow a second nav of its own.

DECLARATION  1_cicd/src/data/nav-shape.json (the rules, the forbidden widgets, and
             the exemption ledger: every not-yet-migrated app, each with its reason)

  N0  libs:bottomnav still ships NavDecl, PageTabs and PageTabsView
  N1  ui.bottom_nav is at most 5 ids, each one a ui.sections id; ui.default_section
      is one of them
  N2  the app compiles libs:bottomnav
  N3  no hand-rolled nav widget (BottomNavigationView, NavigationBar, NavigationRail,
      BottomAppBar, TabLayout, TabRow, ScrollableTabRow, PrimaryTabRow, a local
      @Composable BottomNav) in the app's own sources; comments are blanked first
  N4  the app's gradle bakes UI_BOTTOM_NAV and UI_SECTIONS_B64, and no section/page
      id literal in Kotlin names an id build.json does not declare
  N5  a section with two or more pages (or a page with two or more sub-pages) is
      drawn by PageTabs / PageTabsView
  N6  every exemption names a real app and carries a reason; an exempt app does not
      already pass N1-N5 (it migrated: delete its exemption)
  N7  parity: the island and the tab strips look the SAME in every app, because the look
      is libs:bottomnav's alone. A held app's island/strip call sites pass no colour
      scheme, palette, dimen, typography, elevation, size or inset; no source assigns
      such a property on BottomNavIslandView / PageTabsView; the app declares no
      island-ish colour/dimen/style resource of its own; and some source calls the
      lib's FleetChrome.apply (the window Cloud SuperApp has)

Rules N1-N5 and N7 hold for every app that is NOT exempt. The baseline is all-exempt, so
this is green today; each migration batch deletes its apps' entries.

USAGE  cloud-android-nav-shape-guard.py [ROOT]
EXIT   0 ok · 1 at least one violation
"""
import glob, json, os, re, sys

DATA = "1_cicd/src/data/nav-shape.json"
SKIP = {".git", "build", ".gradle", "node_modules", ".cxx", "test", "tests", "androidTest"}
DEP = re.compile(r"""(?:\bapi|[iI]mplementation)\b["')\s]*,?\s*\(?\s*project\(\s*['"]:libs:bottomnav['"]""")


def read(p):
    with open(p, encoding="utf-8", errors="replace") as h:
        return h.read()


def blank_comments(text, xml=False):
    """`text` with comment CONTENT replaced by spaces (offsets and newlines kept).
    Strings are left alone: an id literal is a string. Kotlin block comments nest."""
    out, i, n = list(text), 0, len(text)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        if xml:
            if text.startswith("<!--", i):
                end = text.find("-->", i)
                end = n if end == -1 else end + 3
                blank(i, end); i = end
            else:
                i += 1
            continue
        two = text[i:i + 2]
        if two == "//":
            end = text.find("\n", i); end = n if end == -1 else end
            blank(i, end); i = end
        elif two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if text[j:j + 2] == "/*":
                    depth, j = depth + 1, j + 2
                elif text[j:j + 2] == "*/":
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            blank(i, j); i = j
        elif text[i] == '"':
            if text.startswith('"""', i):
                end = text.find('"""', i + 3); i = n if end == -1 else end + 3
            else:
                j = i + 1
                while j < n and text[j] not in '"\n':
                    j += 2 if text[j] == "\\" else 1
                i = j + 1
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] not in "'\n":
                j += 2 if text[j] == "\\" else 1
            i = j + 1
        else:
            i += 1
    return "".join(out)


def walk(top, exts):
    for d, dirs, files in os.walk(top):
        dirs[:] = [x for x in dirs if x not in SKIP]
        for f in sorted(files):
            if f.endswith(exts):
                yield os.path.join(d, f)


def line_of(text, idx):
    return text.count("\n", 0, idx) + 1


def page_ids(pages):
    for p in pages or []:
        if isinstance(p, dict):
            yield p.get("id")
            yield from page_ids(p.get("pages"))


def needs_strip(sections):
    def multi(pages):
        pages = [p for p in (pages or []) if isinstance(p, dict)]
        return len(pages) >= 2 or any(multi(p.get("pages")) for p in pages)
    return any(multi(s.get("pages")) for s in sections if isinstance(s, dict))


def top_level(text, open_idx):
    """The text of the call whose `(` is at open_idx with everything nested (inner parens,
    brackets, braces, strings) blanked: what is left is the call's own top-level arguments."""
    out, depth, i, n = [], 0, open_idx, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] not in '"\n':
                j += 2 if text[j] == "\\" else 1
            out.append(" " * (j + 1 - i)); i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                break
        out.append(c if depth == 1 and c not in "([{" else " ")
        i += 1
    return "".join(out)


RES_NAME = re.compile(r"<(?:color|dimen|integer|fraction|style|attr|font|bool)\s+name=\"([^\"]+)\"")


def check_parity(root, app, spec, code, bad):
    """N7: nothing app-side can change how the island and the strips look."""
    n7 = spec["n7"]
    rel = lambda p: os.path.relpath(p, root)
    calls = re.compile(r"\b(%s)\s*\(" % "|".join(re.escape(c) for c in n7["calls"]))
    args = re.compile(r"(?<![\w.])(%s)\s*=(?!=)" % "|".join(re.escape(a) for a in n7["forbidden_args"]))
    # `v.insets = x` and a bare `insets = x` (inside an `apply { }`), but not a declaration (`val insets = x`)
    props = re.compile(r"(?:(?<=\.)|(?<![\w.])(?<!val )(?<!var ))(%s)\s*=(?!=)" % "|".join(re.escape(a) for a in n7["forbidden_props"]))
    for p, text in code.items():
        if not p.endswith(".kt"):
            continue
        for m in calls.finditer(text):
            if re.search(r"\bfun\s+$", text[max(0, m.start() - 8):m.start()]):
                continue  # a declaration, not a call
            hit = args.search(top_level(text, m.end() - 1))
            if hit:
                bad.append(f"N7 {app}: {rel(p)}:{line_of(text, m.start())} passes `{hit.group(1)}` to {m.group(1)} — "
                           f"the island and strips look is libs:bottomnav's alone (FleetChrome); an app passes entries, selection and callbacks")
        if any(v in text for v in n7["view_hosts"]):
            for m in props.finditer(text):
                bad.append(f"N7 {app}: {rel(p)}:{line_of(text, m.start())} assigns `{m.group(1)}` on an island/strip view — "
                           f"the property is gone on purpose; the look is libs:bottomnav's alone")
    names = re.compile(n7["resource_names"])
    for t in (os.path.join(root, app, "app", "src", "main", "res"), os.path.join(root, app, "src", "main", "res")):
        for p in walk(t, (".xml",)):
            if f"{os.sep}values" not in p:
                continue
            for m in RES_NAME.finditer(blank_comments(read(p), xml=True)):
                if names.search(m.group(1)):
                    bad.append(f"N7 {app}: {rel(p)} declares `{m.group(1)}` — an island/strip colour, dimen or style "
                               f"of its own; libs:bottomnav's res/values is the one declaration")
    if not any(n7["chrome_call"] in t for t in code.values()):
        bad.append(f"N7 {app}: no source calls {n7['chrome_call']}…) — every app wears the window Cloud SuperApp has "
                   f"(edge-to-edge, transparent bars); call the lib's FleetChrome from the main activity")


def check_app(root, app, ui, spec, bad):
    sections = [s for s in (ui.get("sections") or []) if isinstance(s, dict)]
    ids = {s.get("id") for s in sections}
    bar = ui.get("bottom_nav")

    # N1
    if not isinstance(bar, list) or not bar:
        bad.append(f"N1 {app}: build.json ui.bottom_nav is missing or empty")
        bar = []
    if len(bar) > spec["max_bottom"]:
        bad.append(f"N1 {app}: ui.bottom_nav has {len(bar)} ids, the island holds at most {spec['max_bottom']}")
    for b in bar:
        if b not in ids:
            bad.append(f"N1 {app}: ui.bottom_nav id {b!r} is not a ui.sections id")
    if not sections:
        bad.append(f"N1 {app}: build.json ui.sections is missing or empty")
    if ui.get("default_section") not in bar:
        bad.append(f"N1 {app}: ui.default_section {ui.get('default_section')!r} is not one of ui.bottom_nav")

    # N2
    gradles = [p for pat in ("build.gradle*", os.path.join("app", "build.gradle*"), "settings.gradle*")
               for p in glob.glob(os.path.join(root, app, pat))]
    try:
        cfg = json.load(open(os.path.join(root, app, "build.json")))
    except (OSError, ValueError):
        cfg = {}
    mods = cfg.get("modules") if isinstance(cfg.get("modules"), dict) else {}
    if not any(DEP.search(read(g)) for g in gradles) and "libs:bottomnav" not in mods:
        bad.append(f"N2 {app}: does not compile libs:bottomnav (no project(':libs:bottomnav') dependency, no build.json module)")

    # N4a
    app_gradles = [g for g in gradles if "settings" not in os.path.basename(g)]
    gtext = "\n".join(read(g) for g in app_gradles)
    for field in ("UI_BOTTOM_NAV", "UI_SECTIONS_B64"):
        if field not in gtext:
            bad.append(f"N4 {app}: build.gradle does not bake {field}")

    # sources
    tops = [os.path.join(root, app, "app", "src", "main"), os.path.join(root, app, "src", "main")]
    kt, xml = [], []
    for t in tops:
        kt += list(walk(t, (".kt", ".java")))
        xml += list(walk(t, (".xml",)))
    code = {p: blank_comments(read(p)) for p in kt}
    layouts = {p: blank_comments(read(p), xml=True) for p in xml if f"{os.sep}res{os.sep}layout" in p}
    rel = lambda p: os.path.relpath(p, root)

    # N3
    for p, text in {**code, **layouts}.items():
        for f in spec["forbidden"]:
            m = re.search(f["re"], text)
            if m:
                bad.append(f"N3 {app}: {rel(p)}:{line_of(text, m.start())} draws its own nav ({f['id']}) — "
                           f"the bar is libs:bottomnav's BottomNavIsland/BottomNavIslandView, the strips PageTabs/PageTabsView")

    # N4b
    decl_sections = ids
    decl_pages = {i for s in sections for i in page_ids(s.get("pages"))}
    calls = "|".join(re.escape(c) for c in spec["id_calls"])
    call_rx = re.compile(r"\b(?:%s)\(\s*\"([^\"\n]+)\"" % calls)
    page_rx = re.compile(r"\"page:([\w-]+)/([\w-]+)\"")
    for p, text in code.items():
        for m in call_rx.finditer(text):
            if m.group(1) not in decl_sections:
                bad.append(f"N4 {app}: {rel(p)}:{line_of(text, m.start())} names section {m.group(1)!r}, which build.json ui.sections does not declare")
        for m in page_rx.finditer(text):
            if m.group(1) not in decl_sections or m.group(2) not in decl_pages:
                bad.append(f"N4 {app}: {rel(p)}:{line_of(text, m.start())} names page:{m.group(1)}/{m.group(2)}, which build.json does not declare")

    # N5
    if needs_strip(sections) and not any(u in t for t in code.values() for u in spec["strip_users"]):
        bad.append(f"N5 {app}: a section has two or more pages but no source renders PageTabs/PageTabsView")

    # N7
    check_parity(root, app, spec, code, bad)


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    spec = json.load(open(os.path.join(root, DATA)))
    exempt = spec.get("exempt") or {}
    bad = []

    # N0
    lib = os.path.join(root, spec["lib"])
    for fname, symbol in sorted(spec["lib_symbols"].items()):
        # #876: the declaration and the strip live in src/commonMain (shared with the wasm page), the View hosts in src/main.
        hits = [p for sub in ("main", "commonMain") for p in walk(os.path.join(lib, "src", sub), (".kt",)) if os.path.basename(p) == fname]
        if not hits:
            bad.append(f"N0 {spec['lib']}: {fname} is gone — the nav declaration and strips live there")
        elif not any(symbol in read(p) for p in hits):
            bad.append(f"N0 {spec['lib']}: {fname} no longer declares `{symbol}`")

    apps = sorted(os.path.basename(os.path.dirname(b)) for b in glob.glob(os.path.join(root, "a[ac]_*", "build.json")))
    checked = 0
    for app in apps:
        try:
            ui = json.load(open(os.path.join(root, app, "build.json"))).get("ui") or {}
        except (OSError, ValueError) as e:
            bad.append(f"N6 {app}: build.json is unreadable ({e})")
            continue
        if app in exempt:
            if ui.get("bottom_nav"):
                # Declaring the key is not migrating (superapp has had one for a long time): the
                # exemption is stale only when the app would pass N1-N5 outright.
                mine = []
                check_app(root, app, ui, spec, mine)
                if not mine:
                    bad.append(f"N6 {app}: passes N1-N5 but is still exempt — it migrated; delete its entry in {DATA}")
            continue
        checked += 1
        check_app(root, app, ui, spec, bad)

    for app, why in sorted(exempt.items()):
        if app not in apps:
            bad.append(f"N6 {app}: exempt in {DATA} but there is no such app (a[ac]_*/build.json)")
        if not isinstance(why, str) or len(why.strip()) < spec["min_reason_chars"]:
            bad.append(f"N6 {app}: exemption needs a reason of at least {spec['min_reason_chars']} characters")

    for b in bad:
        print("FAIL     " + b)
    print(f"── {len(apps)} app(s): {checked} held to N1-N5 + N7, {len(apps) - checked} exempt; {len(bad)} violation(s) ──")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
