#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ enhance-silence-guard.test — prove the guard fails, not just     ║
# ║ that it runs                                                     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard that has only ever been watched succeeding is
# indistinguishable from a guard that returns 0 unconditionally. This
# repository shipped an assertion last week that compared an expression to
# itself and printed green, and another whose search window ran past the code
# it meant to check — so one case below deliberately renames the entry point
# and demands the guard notice it is now reading nothing.
#
# Every case BREAKS the repository in one specific way, demands the guard
# notice that exact way, and throws the copy away. The break happens in a
# throwaway copy of the tree, never in the working tree.
#
# No ripgrep anywhere: it was established that the CI runner does not have it,
# and testers here have passed on its absence rather than on their assertions.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-enhance-silence-guard.py"
SOURCE="ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/latin/TextEnhancer.kt"
ES="ab_cloud-libs-shared/libs/keyboard/src/main/res/values-es/strings.xml"
EN="ab_cloud-libs-shared/libs/keyboard/src/main/res/values/strings.xml"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# One pristine copy, made once, taken from the WORKING TREE rather than the git
# index: the guard has to be provable against the edit somebody is about to
# commit, not only against what is already committed.
PRISTINE="$WORK/pristine"
mkdir -p "$PRISTINE/$(dirname "$SOURCE")" "$PRISTINE/$(dirname "$EN")" \
         "$PRISTINE/$(dirname "$ES")" "$PRISTINE/$(dirname "$GUARD")"
cp "$ROOT/$SOURCE" "$PRISTINE/$SOURCE"
cp "$ROOT/$EN"     "$PRISTINE/$EN"
cp "$ROOT/$ES"     "$PRISTINE/$ES"
cp "$ROOT/$GUARD"  "$PRISTINE/$GUARD"

run_guard() { ( cd "$1" && CLOUD_ANDROID_ROOT="$1" python3 "$GUARD" 2>&1 ); }

sandbox() { local dir="$WORK/case$RANDOM$RANDOM"; cp -a "$PRISTINE" "$dir"; printf '%s' "$dir"; }

# expect_caught <label> <regex the failure line must match> <mutator...>
# The regex is the point: a guard that fails for some OTHER reason is not
# evidence that it caught THIS one.
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
        sed 's/^/         /' <<<"$out" | head -10
        return
    fi
    ok "$label"
    grep -E "$want" <<<"$out" | head -1 | sed 's/^/       ↳ /'
}

# ── the tree as it stands must be clean ────────────────────────────
out="$(run_guard "$PRISTINE")"
if [ $? -eq 0 ]; then
    ok "working tree passes: $(tail -1 <<<"$out")"
else
    fail "working tree does not pass its own guard:"
    sed 's/^/         /' <<<"$out" | head -14
fi

# ── mutators ───────────────────────────────────────────────────────
edit_source() { python3 - "$2/$SOURCE" "$1" <<'PY'
import sys
path, kind = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

if kind == "silent_return":
    # The defect itself, put back: a guard clause in the entry point that walks
    # away without a word. Written the way somebody in a hurry would write it.
    anchor = "        val id = seq.incrementAndGet()"
    assert anchor in text, "test bug: cannot find where to insert the silent return"
    text = text.replace(anchor,
        "        if (target.text.length > 100000) return\n" + anchor, 1)

elif kind == "reuse_message":
    # Two exits collapsed onto one sentence: the owner can no longer tell an
    # empty reply from a reply that changed nothing.
    assert "R.string.enhance_empty_reply" in text
    text = text.replace("R.string.enhance_empty_reply, AiRouter.provider(context).label",
                        "R.string.enhance_unchanged", 1)

elif kind == "new_quiet_reason":
    # A second exit sneaking out through the quiet door.
    old = 'return@post ended(context, "run $id", "field moved while enhancing", R.string.enhance_stale)'
    assert old in text, "test bug: cannot find the stale exit"
    text = text.replace(old, 'return@post endedQuietly("run $id", "field moved, not worth a message")', 1)

elif kind == "drop_format_argument":
    # The message still formats %1$s; the call no longer passes it. getString
    # throws at exactly the moment the owner needed to be told something.
    old = "R.string.enhance_offline, AiRouter.provider(context).label"
    assert old in text
    text = text.replace(old, "R.string.enhance_offline", 1)

elif kind == "rename_entry":
    # The guard's search window run past the code it means to check. It must
    # say so rather than reporting a clean sweep of nothing.
    text = text.replace("fun run(context: Context, connection: RichInputConnection, style: AiRouter.Style)",
                        "fun start(context: Context, connection: RichInputConnection, style: AiRouter.Style)")

elif kind == "comment_out_attribution":
    # An exit whose only attribution is a comment mentioning the helper.
    old = 'return@execute ended(context, "run $id", "reply is identical to the source", R.string.enhance_unchanged)'
    assert old in text
    text = text.replace(old, "return@execute  // ended(context, R.string.enhance_unchanged)", 1)

else:
    raise SystemExit("test bug: unknown mutation %s" % kind)

open(path, "w", encoding="utf-8").write(text)
PY
}

drop_spanish() { python3 - "$2/$ES" "$1" <<'PY'
import re, sys
path, key = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
out = re.sub(r'\n *<string name="%s".*?</string>' % re.escape(key), "", text, count=1, flags=re.S)
assert out != text, "test bug: %s not found in %s" % (key, path)
open(path, "w", encoding="utf-8").write(out)
PY
}

duplicate_english() { python3 - "$2/$EN" "$1" <<'PY'
import re, sys
path, key = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
twin = re.search(r'<string name="enhance_unchanged">(.*?)</string>', text, re.S).group(1)
out = re.sub(r'(<string name="%s">).*?(</string>)' % re.escape(key),
             lambda m: m.group(1) + twin + m.group(2), text, count=1, flags=re.S)
assert out != text, "test bug: %s not found" % key
open(path, "w", encoding="utf-8").write(out)
PY
}

expect_caught "a new silent return in the entry point is caught" \
    'silent exit — .return. with no' \
    edit_source silent_return

expect_caught "an exit whose only attribution is a comment is caught" \
    'silent exit' \
    edit_source comment_out_attribution

expect_caught "two exits sharing one sentence is caught" \
    'already reports exit at line' \
    edit_source reuse_message

expect_caught "a new reason claimed for a quiet exit is caught" \
    'not on the allowed list' \
    edit_source new_quiet_reason

expect_caught "a message left without the argument it formats is caught" \
    'formats 1 argument\(s\) but the call passes 0' \
    edit_source drop_format_argument

expect_caught "the guard losing sight of the entry point is caught" \
    'cannot find the enhance entry point' \
    edit_source rename_entry

expect_caught "an exit message with no Spanish is caught" \
    'enhance_nothing_readable. has no entry in .*values-es' \
    drop_spanish enhance_nothing_readable

expect_caught "two exit messages reading identically is caught" \
    'says exactly what' \
    duplicate_english enhance_nothing_readable

if [ "$FAILURES" -eq 0 ]; then
    echo "PASS — the guard fails on every way an exit can go silent or ambiguous."
    exit 0
fi
echo "FAIL — see above."
exit 1
