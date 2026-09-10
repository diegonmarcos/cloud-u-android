#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ i18n-guard.test — prove the guard fails, not just that it runs   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard that has only ever been watched succeeding is
# indistinguishable from a guard that returns 0 unconditionally; this
# repository shipped an assertion last week that compared an expression to
# itself and printed green. So every case below BREAKS the repository in one
# specific way, demands the guard notice that exact way, and puts it back.
#
# The break happens in a throwaway copy of the tree, never in the working
# tree — a tester that mutates checked-out files loses somebody's work the
# first time it is interrupted.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-i18n-guard.py"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# One pristine copy, made once. Every case copies from it, breaks the copy and
# throws it away, so no case can leak state into the next.
#
# Copied from the WORKING TREE rather than from the git index, on purpose: the
# guard has to be provable against the edit somebody is about to commit, not
# only against what is already committed.
PRISTINE="$WORK/pristine"
mkdir -p "$PRISTINE"
( cd "$ROOT" && find . \
       \( -name .git -o -name build -o -name z_archive -o -name node_modules \) -prune \
       -o \( -name strings.xml -path '*/src/main/res/values*' \) -print0 \
       -o -path './1_cicd/src/i18n-policy.json' -print0 \
       -o -path './1_cicd/src/scripts/*.py' -print0 ) \
  | tar -C "$ROOT" --null -T - -cf - | tar -C "$PRISTINE" -xf -

# run_guard <sandbox> -> prints the guard's combined output, returns its status
run_guard() {
    ( cd "$1" && CLOUD_ANDROID_ROOT="$1" python3 "$GUARD" 2>&1 )
}

sandbox() {
    local dir="$WORK/case$RANDOM$RANDOM"
    cp -a "$PRISTINE" "$dir"
    printf '%s' "$dir"
}

# expect_caught <label> <regex the failure line must match> <mutator...>
# The regex is the point of the test: a guard that fails for some OTHER reason
# is not evidence that it caught THIS one.
expect_caught() {
    local label="$1" want="$2"; shift 2
    local dir out status
    dir="$(sandbox)"
    "$@" "$dir"
    out="$(run_guard "$dir")"; status=$?
    rm -rf "$dir"
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

ES_SUPERAPP="aa_cloud-superapp/app/src/main/res/values-es/strings.xml"
ES_MAIL="ac_cloud-mail/app/src/main/res/values-es/strings.xml"

drop_key() { python3 - "$2/$ES_SUPERAPP" "$1" <<'PY'
import re, sys
path, key = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
out = re.sub(r'\n *<string name="%s".*?</string>' % re.escape(key), "", text, count=1, flags=re.S)
assert out != text, "test bug: %s not found in %s" % (key, path)
open(path, "w", encoding="utf-8").write(out)
PY
}

swap_specifier_indices() { python3 - "$2/$ES_MAIL" "$1" <<'PY'
import re, sys
path, key = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
m = re.search(r'(<string name="%s"[^>]*>)(.*?)(</string>)' % re.escape(key), text, re.S)
assert m, "test bug: %s not found in %s" % (key, path)
# Renumber every positional index to 1: the arguments are still all present and
# the string still formats, it just silently prints argument one twice.
body = re.sub(r"%(\d+)\$", "%1$", m.group(2))
open(path, "w", encoding="utf-8").write(text[:m.start(2)] + body + text[m.end(2):])
PY
}

delete_locale() { rm -f "$1/$ES_SUPERAPP"; }

add_undeclared_module() {
    mkdir -p "$1/ac_cloud-brandnew/app/src/main/res/values"
    cat > "$1/ac_cloud-brandnew/app/src/main/res/values/strings.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Cloud Brand New</string>
</resources>
XML
}

orphan_key() {
    python3 - "$1/$ES_SUPERAPP" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
open(path, "w", encoding="utf-8").write(
    text.replace("</resources>", '    <string name="ghost_key">Fantasma</string>\n</resources>'))
PY
}

expect_caught "a key removed from values-es is caught" \
    'missing key `control_title`' \
    drop_key control_title

expect_caught "a reordered format specifier is caught" \
    'format specifiers changed' \
    swap_specifier_indices settings_update_status_downloading

expect_caught "deleting the whole Spanish locale is caught" \
    'no values-es/strings.xml' \
    delete_locale

expect_caught "a NEW app with no locale cannot slip in undeclared" \
    'ac_cloud-brandnew/app owns a values/strings.xml but is not declared' \
    add_undeclared_module

expect_caught "a Spanish key that translates nothing is caught" \
    'ghost_key. translates nothing' \
    orphan_key

if [ "$FAILURES" -eq 0 ]; then
    echo "PASS — the guard fails on every way a translation can go missing."
    exit 0
fi
echo "FAIL — see above."
exit 1
