#!/usr/bin/env python3
"""cloud-android-wasm-purity-guard -- #876: the code that compiles to Kotlin/Wasm stays free of Android,
and the amount of it only goes up.

The web pages (ab_cloud-libs-shared/web -> <app>/web, Compose Multiplatform on wasmJs) compile the
pure-Compose sources of the shared libs from <lib>/src/commonMain/kotlin. That directory is also added
to the lib's Android source set (`android.sourceSets.main.kotlin.srcDirs += 'src/commonMain/kotlin'`),
so one file serves both targets -- which only works while it names nothing Android.

Every input is 1_cicd/src/data/wasm-migration.json (forbidden patterns, baselines). Checks:
  W1  no forbidden reference in any commonMain source or web-root source (comments and string
      literals are not code: a KDoc that says `android.util.Log` is fine)
  W2  RATCHET: the non-blank Kotlin lines under each lib's src/commonMain, in total and per lib,
      equal the baseline. Fewer = code moved back to Android only (a regression); more = progress
      that must be written into the baseline in the same commit, so it cannot slide back.
      A lib with commonMain and no entry has an implicit baseline of 0.
  W3  a lib with src/commonMain is compiled by the web root: named in
      ab_cloud-libs-shared/web/web.json::libs (and every web.libs entry has a commonMain)
  W4  the lib's Android build.gradle adds src/commonMain/kotlin to its main source set (otherwise
      the Android build silently stops compiling the file)

USAGE  cloud-android-wasm-purity-guard.py [ROOT]      (default: the repository root)
EXIT   0 pure and ratcheted - 1 failure(s)
"""
import json, os, re, sys

DATA = "1_cicd/src/data/wasm-migration.json"
LIBS = "ab_cloud-libs-shared/libs"
SHARED_BUILD = "ab_cloud-libs-shared/web/web.json"
GRADLE_LINE = re.compile(r"sourceSets\.main\.kotlin\.srcDirs\s*\+=\s*['\"]src/commonMain/kotlin['\"]")
COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
STRING = re.compile(r'"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"')


def code(text):
    """Comments and string literals blanked (newlines kept), so only code is judged."""
    blank = lambda m: re.sub(r"[^\n]", " ", m.group(0))
    return STRING.sub(lambda m: '""', COMMENT.sub(blank, text))


def kotlin_files(base):
    for d, dirs, files in os.walk(base):
        dirs[:] = [x for x in dirs if x not in ("build", ".gradle", ".kotlin", "node_modules")]
        for f in sorted(files):
            if f.endswith(".kt"):
                yield os.path.join(d, f)


def lines_of(path):
    return sum(1 for l in open(path, encoding="utf-8", errors="replace") if l.strip())


def web_roots(root):
    """Every directory whose Kotlin is wasm-only code: the shared web root and each <app>/web."""
    out = []
    shared = os.path.join(root, "ab_cloud-libs-shared/web")
    if os.path.isdir(shared):
        out.append(shared)
    for d in sorted(os.listdir(root)):
        w = os.path.join(root, d, "web")
        if re.match(r"^a[abc]_", d) and os.path.isdir(w) and os.path.isfile(os.path.join(w, "settings.gradle.kts")):
            out.append(w)
    return out


def main(root):
    plan = json.load(open(os.path.join(root, DATA)))
    forbidden = [(k, re.compile(v)) for k, v in plan["forbidden"].items()]
    baseline = plan.get("baseline", {}).get("libs", {})
    fails = []

    def fail(rule, msg):
        fails.append("FAIL %s %s" % (rule, msg))

    scan = {}   # lib -> commonMain dir
    libs_dir = os.path.join(root, LIBS)
    for lib in sorted(os.listdir(libs_dir)):
        cm = os.path.join(libs_dir, lib, "src/commonMain")
        if os.path.isdir(cm):
            scan[lib] = cm

    roots = [(os.path.relpath(p, root), p) for p in scan.values()]
    for w in web_roots(root):
        roots.append((os.path.relpath(w, root), w))
    for label, base in roots:
        for f in kotlin_files(base):
            body = code(open(f, encoding="utf-8", errors="replace").read())
            for n, line in enumerate(body.split("\n"), 1):
                for name, rx in forbidden:
                    if rx.search(line):
                        fail("W1", "%s:%d references %s -- wasm code cannot name Android" % (os.path.relpath(f, root), n, name))

    total = 0
    for lib, cm in sorted(scan.items()):
        n = sum(lines_of(f) for f in kotlin_files(cm))
        total += n
        want = baseline.get(lib, 0)
        if n < want:
            fail("W2", "%s: %d commonMain lines, baseline %d -- code left commonMain; the ratchet only goes up" % (lib, n, want))
        elif n > want:
            fail("W2", "%s: %d commonMain lines, baseline %d -- raise baseline.libs.%s to %d in %s (progress is written down in the commit that makes it)" % (lib, n, want, lib, n, DATA))
    for lib, want in sorted(baseline.items()):
        if lib not in scan and want:
            fail("W2", "%s: baseline %d but src/commonMain is gone -- the ratchet only goes up" % (lib, want))
    want_total = plan.get("baseline", {}).get("total")
    if want_total is not None and want_total != total:
        fail("W2", "total commonMain lines are %d, baseline.total %d -- the per-lib baselines and the total move together in %s" % (total, want_total, DATA))

    web = json.load(open(os.path.join(root, SHARED_BUILD))).get("libs") or []
    for lib in sorted(scan):
        if lib not in web:
            fail("W3", "%s has src/commonMain but is not in %s::libs -- the wasm build would never compile it" % (lib, SHARED_BUILD))
    for lib in web:
        if lib not in scan:
            fail("W3", "%s::libs names %s, which has no src/commonMain" % (SHARED_BUILD, lib))

    for lib in sorted(scan):
        g = os.path.join(libs_dir, lib, "build.gradle")
        if not os.path.isfile(g) or not GRADLE_LINE.search(open(g, encoding="utf-8").read()):
            fail("W4", "%s/build.gradle does not add src/commonMain/kotlin to android.sourceSets.main.kotlin.srcDirs" % lib)

    print("wasm purity: %d lib(s) with commonMain, %d line(s); %d web root(s); %d forbidden pattern(s)" % (len(scan), total, len(web_roots(root)), len(forbidden)))
    for f in fails:
        print(f)
    print("wasm purity: %s" % ("HELD" if not fails else "%d failure(s)" % len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    r = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))
    sys.exit(main(r))
