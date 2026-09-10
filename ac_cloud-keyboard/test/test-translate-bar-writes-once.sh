#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ The Translate bar writes the host field once, and only its own   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THESE FOUR. Every one of them is a defect this keyboard actually shipped,
# and all four are silent: nothing crashes, nothing logs, the owner just watches
# their text go wrong.
#
#   701653aaa  the live translation landed in the field twice, then three times,
#              because the bar retracted its previous output BY CHARACTER COUNT
#              and committed the new one anyway when the retract could not be
#              confirmed. The repair was to own one composing region instead.
#   3500d6e60  sliding a finger along the space bar with the bar open moved the
#              caret in the application BEHIND the bar and took the composing
#              region with it, so the next translation overwrote a word the owner
#              had typed in the app. The gesture handlers talked to the host's
#              connection before offering the gesture to the bar.
#
# The assertions are structural — where a call sits relative to another call —
# because that ORDER is what the fix is. A wording check would pass over a
# reordering that puts the defect straight back.
#
# No ripgrep: testers here have passed on its absence rather than on their
# assertions. awk and grep only, and a missing file is fatal, not empty output.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
BAR="$ROOT/ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate/TranslateBarView.kt"
GESTURES="$ROOT/ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

for f in "$BAR" "$GESTURES"; do
    [ -f "$f" ] || { echo "FAIL   $f is missing — every assertion below would read an empty file and pass"; exit 1; }
done

# body <file> <signature substring>
# The function's own braces, from its signature to the matching close. Comments
# and string literals are blanked first so a brace inside either cannot shift the
# depth — an off-by-one there silently widens the window past the code being
# checked, which is how a passing grep came to prove nothing in this repository.
body() {
    awk -v sig="$2" '
        function strip(s) {
            gsub(/"([^"\\]|\\.)*"/, "\"\"", s)
            sub(/\/\/.*$/, "", s)
            return s
        }
        !started && index($0, sig) { started = 1 }
        started {
            print
            line = strip($0)
            n = gsub(/\{/, "{", line); depth += n
            n = gsub(/\}/, "}", line); depth -= n
            if (seen_open || n > 0 || depth > 0) seen_open = 1
            if (seen_open && depth <= 0) exit
        }
    ' "$1"
}

# The extraction itself has to be provable, or every assertion built on it is
# reading whatever it happened to get. An empty body is fatal.
check_body() {
    local text="$1" what="$2"
    if [ -z "$text" ]; then
        fail "$what — could not extract the function body; the signature has moved and every check on it would read nothing"
        return 1
    fi
    return 0
}

# ── 1. apply() must not commit on top of a region it already owns ────────────
APPLY="$(body "$BAR" 'private fun apply(mode: String)')"
if check_body "$APPLY" "apply()"; then
    OWNED_BRANCH="$(printf '%s\n' "$APPLY" | awk '
        /if \(output == Output.OWNED\)/ { inside = 1; next }
        inside && /^[[:space:]]*\} else/ { exit }
        inside { print }')"
    if [ -z "$OWNED_BRANCH" ]; then
        fail "apply() no longer branches on output == Output.OWNED — the state that stops the second copy is gone"
    elif ! printf '%s\n' "$OWNED_BRANCH" | grep -q 'releaseOutput('; then
        fail "apply()'s OWNED branch does not call releaseOutput() — finishing the composing region IS the apply"
    elif printf '%s\n' "$OWNED_BRANCH" | grep -qE 'commitText\(|replaceInField\('; then
        fail "apply() commits text while it already owns a composing region — that is the translation landing twice (701653aaa)"
    else
        pass "apply() finishes the region it owns instead of committing a second copy"
    fi
fi

# ── 2. pushOutput() revises by composing region, never by character count ────
PUSH="$(body "$BAR" 'private fun pushOutput(out: String)')"
if check_body "$PUSH" "pushOutput()"; then
    if ! printf '%s\n' "$PUSH" | grep -q 'setComposingText('; then
        fail "pushOutput() no longer uses setComposingText — the atomic replace is the only revision with no length to get wrong"
    elif printf '%s\n' "$PUSH" | grep -qE 'deleteSurroundingText\(|commitText\('; then
        fail "pushOutput() deletes or commits to revise its output — retracting by character count is what ate the owner's words (701653aaa)"
    else
        pass "pushOutput() revises the composing region it owns and nothing else"
    fi
fi

# ── 3/4. the gestures reach the bar before they reach the application ────────
# The order is the fix. onCaretSlide returning true means the bar consumed the
# gesture; anything talking to `connection` above that line is editing the field
# behind the bar, which is silent damage to a document nobody is looking at.
gesture_offers_bar_first() {
    local signature="$1" label="$2"
    local text offer first_connection
    text="$(body "$GESTURES" "$signature")"
    check_body "$text" "$label" || return
    offer="$(printf '%s\n' "$text" | grep -n 'latinIME.onCaretSlide(' | head -1 | cut -d: -f1)"
    first_connection="$(printf '%s\n' "$text" | grep -n 'connection\.' | head -1 | cut -d: -f1)"
    if [ -z "$offer" ]; then
        fail "$label never offers the gesture to the bar — it edits the application behind it (3500d6e60)"
    elif [ -n "$first_connection" ] && [ "$first_connection" -lt "$offer" ]; then
        fail "$label touches the host connection at line $first_connection of its body, before offering the gesture to the bar at line $offer (3500d6e60)"
    else
        pass "$label offers the gesture to the bar before touching the host field"
    fi
}

gesture_offers_bar_first 'private fun onMoveCursorHorizontally(rawSteps: Int)' "the space-bar caret slide"
gesture_offers_bar_first 'override fun onMoveDeletePointer(steps: Int)'        "the backspace drag"

if [ "$FAILURES" -eq 0 ]; then
    echo "PASS — the Translate bar writes the host field once, and only the span it owns."
    exit 0
fi
echo "FAIL — see above."
exit 1
