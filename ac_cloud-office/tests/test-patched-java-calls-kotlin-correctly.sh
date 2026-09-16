#!/usr/bin/env bash
# JAVA WE ADD MUST REACH A KOTLIN PROPERTY THROUGH ITS ACCESSOR, NOT ITS FIELD.
#
# Run 35037615570 got all the way through the Gerrit fetch, the patch series,
# configure and most of Gradle — about eight minutes — and then died on three
# lines of our own patch:
#
#     CloudTextEnhance.java:162: error: cannot find symbol
#             if (!result.ok) {                 symbol: variable ok
#     CloudTextEnhance.java:166: error: error has private access in Result
#     CloudTextEnhance.java:170: error: text has private access in Result
#
# TextTools.Result is Kotlin — `class Result(val text: String?, val error: String?)`
# with `val ok: Boolean get() = text != null`. Kotlin compiles a non-const `val`
# to a PRIVATE field plus a public getter, and a computed `val` to a getter and
# no field at all. Java has to say getText(), getError(), getOk(). Every other
# consumer in this fleet is Kotlin, where `result.ok` is correct property syntax,
# so nothing else in the repository could have caught this.
#
# WHY A TESTER AND NOT JUST THE FIX. build.json::build.modules links the shared
# text-tools module BY REFERENCE — cloud-mail and the Cloud Keyboard build the
# same directory, and whoever changes a `val` there will never open this app's
# patch series. The cost of finding out is a full source build: a ~2 GB Gerrit
# clone and eight minutes of CI before javac says a word. This costs a second.
#
# WHAT IT CHECKS, precisely: for every .java file our patch series ADDS, it finds
# each local declared with a type from the linked Kotlin modules, and requires
# every member access on that local to be a call. It is deliberately narrow —
# receiver-typed, not a blanket grep for `.text` — so it cannot redden an
# unrelated Java field that happens to share a name.
#
# Usage: ./test-patched-java-calls-kotlin-correctly.sh          (offline)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo -e "  \033[0;32mok\033[0m: $1"; }
bad() { FAIL=$((FAIL+1)); echo -e "  \033[0;31mFAIL\033[0m: $1"; }

command -v python3 >/dev/null 2>&1 \
    || { echo "ERROR: python3 is not on PATH — refusing to report a verdict this run never computed" >&2; exit 2; }

OUT="$(python3 - "$APP" <<'PY'
import json, os, re, sys

app = sys.argv[1]
build_json = os.path.join(app, "build.json")
try:
    spec = json.load(open(build_json, encoding="utf-8"))
except Exception as exc:                       # noqa: BLE001 - reported, not swallowed
    print("DIE\tcannot read %s: %s" % (build_json, exc)); raise SystemExit(0)

# The Kotlin that our Java links against is exactly what build.json declares —
# never a path typed in here, or this tester would keep checking a module the
# build stopped using.
modules = (spec.get("build") or {}).get("modules") or {}
roots = []
for name, mod in modules.items():
    if not isinstance(mod, dict) or not mod.get("dir"):
        continue
    root = os.path.normpath(os.path.join(app, mod["dir"]))
    if not os.path.isdir(root):
        print("DIE\tbuild.json::build.modules.%s.dir does not exist: %s" % (name, root))
        raise SystemExit(0)
    roots.append((name, root))
if not roots:
    print("DIE\tbuild.json::build.modules declares no module with a dir — nothing to check against")
    raise SystemExit(0)

# class simple name -> {property: is_const}. Constructor `val`s and body `val`s
# both become getters; only `const val` is a real static field a Java caller may
# read directly.
DECL   = re.compile(r'^\s*(?:@\w+\s+)*(?:public\s+|internal\s+)?(?:data\s+|sealed\s+|open\s+|abstract\s+)*(?:class|object|interface)\s+(\w+)')
PROP   = re.compile(r'^\s*(?:@\w+\s+)*(?:private\s+|internal\s+|protected\s+)?(const\s+)?(?:va[lr])\s+(\w+)\s*[:=]')
CTORP  = re.compile(r'\b(?:va[lr])\s+(\w+)\s*:')

classes, kotlin_files = {}, 0
for _name, root in roots:
    for base, _dirs, files in os.walk(root):
        for f in sorted(files):
            if not f.endswith(".kt"):
                continue
            kotlin_files += 1
            current = None
            for line in open(os.path.join(base, f), encoding="utf-8", errors="replace"):
                d = DECL.match(line)
                if d:
                    current = d.group(1)
                    classes.setdefault(current, {})
                    # `class Result(val text: String?, val error: String?)`
                    for prop in CTORP.findall(line):
                        classes[current][prop] = False
                    continue
                if current is None:
                    continue
                p = PROP.match(line)
                if p:
                    classes[current][p.group(2)] = bool(p.group(1))

if kotlin_files == 0:
    print("DIE\tno .kt files under the declared modules — an empty rule set would pass anything")
    raise SystemExit(0)
print("INFO\t%d Kotlin file(s), %d type(s) with properties, from: %s"
      % (kotlin_files, len([c for c in classes if classes[c]]),
         ", ".join(n for n, _ in roots)))

# Java our patch series ADDS. A '+' line in a hunk for a .java path; the patch
# header's own "+++ b/..." is skipped by the hunk gate.
patch_dir = os.path.join(app, "patches")
added = {}
in_java, in_hunk = False, False
for pf in sorted(os.listdir(patch_dir)) if os.path.isdir(patch_dir) else []:
    if not pf.endswith(".patch"):
        continue
    for line in open(os.path.join(patch_dir, pf), encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if line.startswith("diff --git"):
            in_java = line.endswith(".java")
            in_hunk = False
            path = line.split(" b/")[-1] if in_java else None
            continue
        if line.startswith("@@"):
            in_hunk = True
            continue
        if in_java and in_hunk and line.startswith("+") and not line.startswith("+++"):
            added.setdefault(path, []).append(line[1:])

if not added:
    print("DIE\tthe patch series adds no .java file — this tester would pass vacuously")
    raise SystemExit(0)

findings, checked_vars = [], 0
for path, body in added.items():
    # `final TextTools.Result result = ...` / `TextTools.Result r;`
    typed = {}
    for line in body:
        for m in re.finditer(r'\b(?:final\s+)?(?:\w+\.)?(\w+)\s+(\w+)\s*(?:=|;)', line):
            simple, var = m.group(1), m.group(2)
            if simple in classes and classes[simple]:
                typed[var] = simple
    checked_vars += len(typed)
    for var, simple in sorted(typed.items()):
        for prop, is_const in sorted(classes[simple].items()):
            if is_const:
                continue
            # `var.prop` NOT followed by '(' is a field read of a Kotlin property.
            hit = re.compile(r'\b%s\.%s\b\s*(?!\()' % (re.escape(var), re.escape(prop)))
            for line in body:
                if hit.search(line):
                    findings.append((os.path.basename(path), var, simple, prop, line.strip()))

print("INFO\t%d Java file(s) added by the series, %d local(s) typed by a Kotlin class"
      % (len(added), checked_vars))
if checked_vars == 0:
    print("DIE\tno local in the added Java resolves to a declared Kotlin type — "
          "the rule matched nothing and a pass would mean nothing")
    raise SystemExit(0)
for f in findings:
    print("HIT\t%s\t%s.%s\t%s.%s\t%s" % (f[0], f[1], f[3], f[2], f[3], f[4]))
print("DONE\t%d" % len(findings))
PY
)"

echo "$OUT" | grep -q '^DIE' && { echo "$OUT" | sed -n 's/^DIE\t/ERROR: /p' >&2; exit 2; }

echo "== T1: the rule set was actually built from the declared modules =="
echo "$OUT" | sed -n 's/^INFO\t/  /p'
ok "rule set and patch inputs both non-empty (this tester fails closed on either)"

echo "== T2: no Java our patches add reads a Kotlin property as a field =="
HITS="$(echo "$OUT" | grep -c '^HIT' || true)"
if [ "$HITS" -eq 0 ]; then
    ok "every member access on a Kotlin-typed local is a call"
else
    echo "$OUT" | while IFS=$'\t' read -r tag file expr owner line; do
        [ "$tag" = HIT ] || continue
        bad "$file: $expr is a Kotlin property of $owner — javac needs the getter, not the field"
        echo "        $line" >&2
    done
    FAIL=$((FAIL + HITS))
fi

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
