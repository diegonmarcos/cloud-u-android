#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ i18n-guard.test.sh — does the guard actually catch a lost base   ║
# ║ language, or does it only print a green line?                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# A guard that runs green on a correct tree proves nothing: a script with the
# check deleted runs green on a correct tree too. So every case here BREAKS the
# rule in a copy of the repository and asserts the guard exits non-zero AND says
# why. The break is always the realistic one — a locale filter dropped while
# somebody reformats a build file, or retargeted to another language — never a
# syntax error the build would have caught first.
#
# The sandbox carries the resource files, the policy, the guard and the module
# build files, and nothing else. Copying the whole repository per case would move
# gigabytes; copying the three kinds of file the guard reads is a few hundred
# kilobytes, and it also documents exactly what the guard's answer depends on.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-i18n-guard.py"
FAILURES=0

ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── one pristine copy, cloned per case ──────────────────────────────
# The locale directories the sandbox needs are DERIVED from the policy rather
# than listed here: the day a module asks for a required translation again, its
# values-<locale>/ has to travel into the sandbox or the guard would report it
# missing in every case and every assertion below would pass for the wrong
# reason.
PRISTINE="$WORK/pristine"
mkdir -p "$PRISTINE"

# One walk in python rather than find | grep | tar: this script runs on the
# owner's phone as well as in CI, and busybox tar has no --null, so a pipeline
# built on it fails there with a short read and every case below then reports a
# missing file instead of a verdict.
python3 - "$ROOT" "$PRISTINE" <<'PY' || { echo "could not build the sandbox" >&2; exit 1; }
import json, os, shutil, sys

root, dest = sys.argv[1], sys.argv[2]
policy = json.load(open(os.path.join(root, "1_cicd/src/i18n-policy.json")))

keep_dirs = {"values"}
for rule in policy["modules"].values():
    if isinstance(rule, dict):
        for locale in rule.get("require", []):
            keep_dirs.add("values" if locale == "default" else "values-" + locale)

SKIP = {".git", "build", "z_archive", "node_modules", ".gradle"}
copied = 0
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in SKIP]
    rel = os.path.relpath(dirpath, root)
    parts = rel.split(os.sep)
    in_resources = (len(parts) >= 4
                    and parts[-4:-1] == ["src", "main", "res"]
                    and parts[-1] in keep_dirs)
    for name in filenames:
        wanted = ((in_resources and name.endswith(".xml"))
                  or name in ("build.gradle", "build.gradle.kts")
                  or (rel == os.path.join("1_cicd", "src") and name == "i18n-policy.json")
                  or (rel == os.path.join("1_cicd", "src", "scripts") and name.endswith(".py")))
        if not wanted:
            continue
        target = os.path.join(dest, rel, name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        # copyfile, not copy2: copying metadata drags the extended attributes
        # along, and setxattr is denied under Android's app-private storage.
        # Only the bytes matter — the guard is invoked as `python3 <path>`.
        shutil.copyfile(os.path.join(dirpath, name), target)
        copied += 1
print("sandbox: %d file(s) the guard can read" % copied)
PY

# mktemp rather than a counter: the dir is read back through a command
# substitution, so anything the function increments happens in a subshell and is
# lost — every case would land in the same directory and copy on top of the one
# before it.
sandbox() {
    local dir
    dir="$(mktemp -d "$WORK/case.XXXXXX")" || return 1
    cp -a "$PRISTINE/." "$dir/" || return 1
    printf '%s\n' "$dir"
}

run_guard() { ( cd "$1" && CLOUD_ANDROID_ROOT="$1" python3 "$GUARD" 2>&1 ); }

expect_caught() {
    local label="$1" want="$2"
    shift 2
    local dir
    dir="$(sandbox)"
    "$@" "$dir" || { fail "$label — the mutator itself failed"; return; }
    local out status
    out="$(run_guard "$dir")"
    status=$?
    if [ "$status" -eq 0 ]; then
        fail "$label — guard exited 0; the break went through unnoticed"
        return
    fi
    if ! grep -qE "$want" <<<"$out"; then
        fail "$label — guard failed, but not about this. Wanted /$want/, got:"
        sed 's/^/         /' <<<"$out" | head -8
        return
    fi
    ok "$label"
    grep -E "$want" <<<"$out" | head -1 | sed 's/^/       ↳ /'
}

# ── the tree as committed must be clean ────────────────────────────
out="$(run_guard "$PRISTINE")"
if [ $? -eq 0 ]; then
    ok "committed tree passes: $(tail -1 <<<"$out")"
else
    fail "committed tree does not pass its own guard:"
    sed 's/^/         /' <<<"$out" | head -12
fi

GROOVY_APP="aa_cloud-superapp/app/build.gradle"
KOTLIN_RESCONFIG_APP="ac_cloud-mail/app/build.gradle.kts"
KOTLIN_FILTER_APP="ac_cloud-camera/app/build.gradle.kts"

# Drops the whole declaration line, the way a reformat or a merge resolution
# does. Whichever of the three spellings the module uses, the app is left with
# no filter and every values-es/ it merges reaches the device.
strip_filter() { python3 - "$2/$1" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
out = re.sub(
    r'\n[^\n]*(resConfigs\s+"en"'
    r'|resourceConfigurations\s*\+=\s*listOf\("en"\)'
    r'|localeFilters\s*\+=\s*listOf\("en"\))[^\n]*',
    "", text, count=1)
assert out != text, "test bug: no English locale filter found in %s" % path
open(path, "w", encoding="utf-8").write(out)
PY
}

# Keeps the declaration and changes only the language. This is the case that
# proves the guard reads the LOCALE and not merely the presence of a call: a
# filter set to "es" is a perfectly valid build file that pins the app to
# Spanish, which is the exact outcome the fleet rule exists to prevent.
retarget_filter() { python3 - "$2/$1" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
out, count = re.subn(
    r'((?:resConfigs\s+|resourceConfigurations\s*\+=\s*listOf\(|localeFilters\s*\+=\s*listOf\()")en"',
    r'\1es"', text, count=1)
assert count, "test bug: no English locale filter found in %s" % path
open(path, "w", encoding="utf-8").write(out)
PY
}

# The next app the owner builds. It owns user-facing text and says nothing about
# its base language, which is the silent condition the registry rule exists to
# turn into a stopped build.
add_undeclared_module() {
    mkdir -p "$1/ac_cloud-brandnew/app/src/main/res/values"
    cat > "$1/ac_cloud-brandnew/app/src/main/res/values/strings.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Cloud Brand New</string>
</resources>
XML
}

# Declared `base_language: en` with no build file to declare the filter in —
# somebody answering the registry failure above with the wrong word. `en` is a
# promise only a packaging module can keep; a library has to say `host`.
declare_en_without_build_file() {
    add_undeclared_module "$1"
    python3 - "$1/1_cicd/src/i18n-policy.json" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
anchor = '    "aa_cloud-superapp/app":'
assert anchor in text, "test bug: policy anchor moved"
open(path, "w", encoding="utf-8").write(text.replace(
    anchor,
    '    "ac_cloud-brandnew/app":                   { "base_language": "en" },\n' + anchor,
    1))
PY
}

# Names a module that does not exist, the residue of a rename or a deletion. A
# stale entry is not harmless: it is a rule nobody is checking, and the guard
# would keep reporting it as one of the modules it filters.
declare_missing_module() {
    python3 - "$1/1_cicd/src/i18n-policy.json" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
anchor = '    "aa_cloud-superapp/app":'
assert anchor in text, "test bug: policy anchor moved"
open(path, "w", encoding="utf-8").write(text.replace(
    anchor,
    '    "ac_cloud-deleted/app":                    { "base_language": "en" },\n' + anchor,
    1))
PY
}

expect_caught "a Groovy app that loses resConfigs is caught" \
    "$GROOVY_APP declares no en locale filter" \
    strip_filter "$GROOVY_APP"

expect_caught "a Kotlin app that loses resourceConfigurations is caught" \
    "$KOTLIN_RESCONFIG_APP declares no en locale filter" \
    strip_filter "$KOTLIN_RESCONFIG_APP"

expect_caught "a Kotlin app that loses localeFilters is caught" \
    "$KOTLIN_FILTER_APP declares no en locale filter" \
    strip_filter "$KOTLIN_FILTER_APP"

expect_caught "a filter retargeted from en to es is caught" \
    "$KOTLIN_FILTER_APP declares no en locale filter" \
    retarget_filter "$KOTLIN_FILTER_APP"

expect_caught "a Groovy filter retargeted from en to es is caught" \
    "$GROOVY_APP declares no en locale filter" \
    retarget_filter "$GROOVY_APP"

expect_caught "a NEW app with user-facing text cannot slip in undeclared" \
    "ac_cloud-brandnew/app owns a values/strings.xml but is not declared" \
    add_undeclared_module

expect_caught "base_language: en on a module with no build file is caught" \
    "ac_cloud-brandnew/app is declared .base_language: en. but owns no build" \
    declare_en_without_build_file

expect_caught "a policy entry for a module that no longer exists is caught" \
    "declares ac_cloud-deleted/app, which owns no" \
    declare_missing_module

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
    printf 'i18n guard tester: all cases pass — the guard catches a lost base language.\n'
    exit 0
fi
printf 'i18n guard tester: %d case(s) failed.\n' "$FAILURES"
exit 1
