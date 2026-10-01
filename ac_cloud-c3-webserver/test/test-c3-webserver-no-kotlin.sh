#!/bin/sh
# test-c3-webserver-no-kotlin.sh — the app is Rust and web; the only Kotlin
# is the glue Tauri generates.
#
# THE FAILURE THIS EXISTS FOR. The webserver's shell used to be three
# hand-written Kotlin files (an Activity, a Service, a process launcher). The
# owner asked for the whole app in Rust/Tauri. Tauri's Android build cannot
# avoid Kotlin entirely — its template commits a MainActivity and two gradle
# plugin classes, and its build scripts write the webview classes into the
# build — so "no Kotlin" is enforced as "no Kotlin beyond the declared glue":
#
#   build.json::tauri.glue               committed glue, every file pinned by sha256
#   build.json::tauri.generated_kotlin   names Tauri/wry write at build time,
#                                        accepted only while git does not track them
#   build.json::tauri.build_scripts      the only *.gradle.kts files allowed
#
# Anything else that is .kt, .java or a standalone .kts anywhere in the app
# fails, as does a glue file whose bytes moved or that is missing.
#
#   sh test-c3-webserver-no-kotlin.sh          the guard, then every mutation below
#   sh test-c3-webserver-no-kotlin.sh check    the guard alone (build.sh runs it
#                                              before and after the gradle build)
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"

guard() {
  python3 - "$1" <<'PY'
import hashlib, json, os, subprocess, sys

app = sys.argv[1]
tauri = json.load(open(os.path.join(app, "build.json")))["tauri"]
glue = {k: v for k, v in tauri["glue"].items() if not k.startswith("_")}
gen_dir = tauri["generated_kotlin"]["dir"]
gen_names = set(tauri["generated_kotlin"]["files"])
scripts = set(tauri["build_scripts"])
SKIP = {".git", "target", "build", ".gradle", "dist", "node_modules"}

def tracked(rel):
    r = subprocess.run(["git", "-C", app, "ls-files", "--error-unmatch", rel],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return r.returncode == 0

errors, seen = [], set()
for base, dirs, files in os.walk(app):
    dirs[:] = [d for d in dirs if d not in SKIP]
    for name in files:
        rel = os.path.relpath(os.path.join(base, name), app)
        if name.endswith(".gradle.kts"):
            if rel not in scripts:
                errors.append("%s: a gradle Kotlin script build.json::tauri.build_scripts does not declare" % rel)
            continue
        if not name.endswith((".kt", ".java", ".kts")):
            continue
        if rel in glue:
            seen.add(rel)
            got = hashlib.sha256(open(os.path.join(app, rel), "rb").read()).hexdigest()
            if got != glue[rel]:
                errors.append("%s: Tauri glue edited by hand (sha256 %s, declared %s)" % (rel, got, glue[rel]))
            continue
        if os.path.dirname(rel) == gen_dir and name in gen_names:
            if tracked(rel):
                errors.append("%s: build-generated Kotlin is committed; it must only ever come from the build" % rel)
            continue
        errors.append("%s: hand-written Kotlin/Java in an app that is Rust and web only" % rel)
for rel in sorted(set(glue) - seen):
    errors.append("%s: declared Tauri glue is missing — the pin names a file that is not there" % rel)

for e in errors:
    print("  FAIL " + e)
if errors:
    sys.exit(1)
print("  ok   %d glue files at their pinned bytes, no other Kotlin/Java" % len(glue))
PY
}

echo "== the app's Kotlin is exactly the declared Tauri glue =="
guard "$APP"
[ "${1:-}" = "check" ] && exit 0

echo "== mutations: each planted defect must turn the guard red =="
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0
PKG="src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver"

# mutate <name> <shell snippet run inside the copy> <path that must differ>
mutate() {
  name="$1"; snippet="$2"; probe="$3"
  rm -rf "$WORK/app"
  python3 -c 'import shutil,sys; shutil.copytree(sys.argv[1], sys.argv[2], ignore=shutil.ignore_patterns("target","build",".gradle","dist"))' "$APP" "$WORK/app"
  before="$(cat "$WORK/app/$probe" 2>/dev/null || echo '<absent>')"
  (cd "$WORK/app" && sh -c "$snippet")
  after="$(cat "$WORK/app/$probe" 2>/dev/null || echo '<absent>')"
  if [ "$before" = "$after" ]; then
    echo "  FAIL mutation $name did not apply ($probe unchanged)"; fails=$((fails + 1)); return 0
  fi
  if guard "$WORK/app" >/dev/null 2>&1; then
    echo "  FAIL mutation $name: the guard stayed green"; fails=$((fails + 1))
  else
    echo "  ok   mutation $name: red"
  fi
}

mutate kotlin-beside-glue "printf 'package x\nclass Extra\n' > $PKG/Extra.kt" "$PKG/Extra.kt"
mutate java-in-rust-crate "printf 'class Helper {}\n' > src-tauri/src/Helper.java" "src-tauri/src/Helper.java"
mutate glue-edited "printf '// hand-written\n' >> $PKG/MainActivity.kt" "$PKG/MainActivity.kt"
mutate glue-missing "rm src-tauri/gen/android/buildSrc/src/main/java/com/diegonmarcos/cloudwebserver/kotlin/RustPlugin.kt" \
  "src-tauri/gen/android/buildSrc/src/main/java/com/diegonmarcos/cloudwebserver/kotlin/RustPlugin.kt"
mutate unknown-generated "mkdir -p $PKG/generated && printf 'class Evil\n' > $PKG/generated/Evil.kt" "$PKG/generated/Evil.kt"
mutate undeclared-gradle-kts "printf 'println(1)\n' > src-tauri/gen/android/app/extra.gradle.kts" "src-tauri/gen/android/app/extra.gradle.kts"
mutate standalone-kts "printf 'println(1)\n' > server/tool.main.kts" "server/tool.main.kts"

# The two shapes the guard must ACCEPT, so it is not red for everything: a
# declared generated name, untracked, as the build leaves it.
rm -rf "$WORK/app"
python3 -c 'import shutil,sys; shutil.copytree(sys.argv[1], sys.argv[2], ignore=shutil.ignore_patterns("target","build",".gradle","dist"))' "$APP" "$WORK/app"
mkdir -p "$WORK/app/$PKG/generated"
printf 'abstract class TauriActivity\n' > "$WORK/app/$PKG/generated/TauriActivity.kt"
printf 'class WryActivity\n' > "$WORK/app/$PKG/generated/WryActivity.kt"
if guard "$WORK/app" >/dev/null 2>&1; then
  echo "  ok   control: build-generated TauriActivity.kt/WryActivity.kt are accepted"
else
  echo "  FAIL control: the guard rejects the files the build itself generates"; fails=$((fails + 1))
fi

[ "$fails" -eq 0 ] || { echo "$fails check(s) failed"; exit 1; }
echo "all mutations red, control green"
