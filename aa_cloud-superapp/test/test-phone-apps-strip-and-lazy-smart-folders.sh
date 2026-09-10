#!/usr/bin/env bash
# Tester: Phone > Apps -- every Quickmarks line is ONE sideways-scrollable row
# (#260), and Smart Folders load lazily off the main thread (#261).
#
# THE TWO FAILURES THIS EXISTS TO KEEP FIXED.
#
#   1. QUICKMARKS ROWS WRAPPED ONTO SECOND LINES. The owner asked for a single
#      scrollable line more than once and it stayed wrapped, because the cause
#      is not where the symptom points. Nothing on the page was a flow layout;
#      each section walked tiles.chunked(UI_PHONE_GRID_COLUMNS) and added one
#      horizontal LinearLayout PER CHUNK to a vertical parent. The second line
#      was not overflow, it was a second View, so no amount of tile resizing
#      could have removed it. T1-T5 assert the chunking is gone from the
#      Quickmarks path, that a HorizontalScrollView carries each line, and that
#      the tiles size themselves in a way that survives inside one.
#
#   2. THE PAGE FROZE ON OPEN WAITING FOR SMART FOLDERS. Four install_source
#      rules in build.json call PackageManager.getInstallSourceInfo once per
#      installed app, five ranking rules each walk a multi-day usage, network
#      or battery history, and all of it ran on the main thread. Deferring it
#      to a posted frame -- which is what the code did -- only chooses which
#      frame janks. T6-T13 assert the selection happens on a background
#      thread, that the section starts collapsed and expands itself, and that
#      the result path cannot write into a dead view hierarchy.
#
# WHY EVERY ASSERTION READS COMMENT-STRIPPED SOURCE. Both fragments explain, in
# prose and by name, the constructs they no longer use -- SuitePhoneAppsFragment
# says "used to walk tiles.chunked(columns)" and quotes the weighted
# LayoutParams it replaced. A plain grep for a banned construct matches the very
# comment proving it is gone. That false failure is the mirror image of the
# false pass this suite has shipped before, and the fix is the same one
# test-phone-quickmark-placeholders.sh uses: strip comments, then let the
# assertion mean what it says.
#
# Usage: ./test-phone-apps-strip-and-lazy-smart-folders.sh   (static only, no network, no device)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> the repository root
APP="$ROOT/aa_cloud-superapp"
SUITE="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
PHONE="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneAppsFragment.kt"
BUILD_JSON="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# FAIL CLOSED on tooling and on every input file, before any assertion runs. A
# missing file or a missing tool must never become a test result. Nothing below
# suppresses stderr and nothing below is reachable with an input missing.
for tool in python3 grep jq; do
    command -v "$tool" >/dev/null || {
        echo "FATAL: $tool is not on PATH -- refusing to report a verdict"; exit 2; }
done
for f in "$SUITE" "$PHONE" "$BUILD_JSON"; do
    [ -f "$f" ] || { echo "FATAL: missing input $f -- refusing to report a verdict"; exit 2; }
done

# ── the scanner ───────────────────────────────────────────────────────────
# Prints ONE function's body with comments stripped, so an assertion is scoped
# to the code that implements a thing rather than to the whole file. Scoping is
# the point: "the folder dialog still chunks into a grid" and "Quickmarks no
# longer chunks" are both true, and a file-wide grep cannot say so.
SCANNER="$(mktemp)" || exit 2
trap 'rm -f "$SCANNER"' EXIT
cat > "$SCANNER" <<'PY'
import re, sys

path, indent, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
source = open(path, encoding="utf-8").read()

pad = " " * indent
start = re.search(r"\n" + pad + r"(?:private |internal )?fun " + re.escape(name) + r"\b", source)
if not start:
    print("__NOT_FOUND__")
    sys.exit(0)

rest = source[start.start() + 1:]
# The next declaration at the SAME indent ends this one. Anything more deeply
# indented is still inside it.
nxt = re.search(r"\n" + pad + r"(private |internal |override |fun |companion |@|\}\s*$)", rest[1:])
body = rest[: nxt.start() + 1] if nxt else rest

# Comments out, in the order that keeps a block comment from leaking: /** */
# blocks first, then whole-line * continuations, then trailing //. A `//` inside
# a string literal would be mangled too -- neither of these files has one, and a
# tester that silently tolerated one would be guessing.
body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
body = re.sub(r"(?m)^\s*\*.*$", "", body)
body = re.sub(r"(?m)//.*$", "", body)
print(body)
PY

# EVERY BODY IS RESOLVED UP FRONT, AT TOP LEVEL, AND NEVER INSIDE $( ).
#
# The first draft of this file had `body()` print the body and `exit 2` when the
# function was missing, called as `X="$(body ...)"`. That exit only ends the
# SUBSHELL the substitution runs in: the script carried on, the FATAL text
# landed inside X instead of on the terminal, and every assertion then failed
# for the wrong reason with the real one invisible. That is precisely the shape
# this suite keeps getting burned by -- a control-flow failure wearing a
# verdict's clothes -- so the fatal path runs where `exit` means exit, and the
# assertions below only ever read a file that is already known to be good.
BODIES="$(mktemp -d)" || exit 2
trap 'rm -rf "$SCANNER" "$BODIES"' EXIT

load() {  # load <key> <file> <indent> <name>
    if ! python3 "$SCANNER" "$2" "$3" "$4" > "$BODIES/$1"; then
        echo "FATAL: the scanner failed on $4 in $2 -- refusing to report a verdict"; exit 2
    fi
    if grep -qxF '__NOT_FOUND__' "$BODIES/$1"; then
        echo "FATAL: $4 is not in $(basename "$2") -- this tester would be asserting on code"
        echo "       it cannot see. If the function was renamed, rename it here too; if it was"
        echo "       deleted, the behaviour it implemented went with it."
        exit 2
    fi
}

body() { cat "$BODIES/$1"; }   # already validated by load()

load strip      "$SUITE" 4 tileStrip
load buildPage  "$SUITE" 4 buildPage
load usage      "$SUITE" 4 usageSection
load appTile    "$SUITE" 4 makeAppTile
load folderTile "$SUITE" 4 makeFolderTile
load cellWidth  "$SUITE" 4 cellWidth
load section    "$SUITE" 4 addSmartFoldersSection
load async      "$PHONE" 8 renderSmartFoldersAsync
load compute    "$PHONE" 8 computeSmartFolders
load invalidate "$PHONE" 8 invalidateCache

echo "== T1: each Quickmarks line is a HorizontalScrollView strip =="
STRIP="$(body strip)"
if printf '%s\n' "$STRIP" | grep -qF 'HorizontalScrollView(ctx)'; then
    ok "tileStrip builds a HorizontalScrollView"
else
    bad "tileStrip does not build a HorizontalScrollView -- a line cannot scroll sideways"
fi
if printf '%s\n' "$STRIP" | grep -qF 'orientation = LinearLayout.HORIZONTAL'; then
    ok "tileStrip puts every tile in ONE horizontal row"
else
    bad "tileStrip's row is not horizontal -- the tiles would stack vertically"
fi
if printf '%s\n' "$STRIP" | grep -qF 'clipToPadding = false'; then
    ok "the strip does not clip its final tile at the page padding"
else
    bad "clipToPadding is not disabled -- the last app can be unreachable, which is the same bug"
fi

echo "== T2: the Quickmarks render path no longer chunks into rows =="
# THE ASSERTION THAT WOULD HAVE FAILED BEFORE #260. buildPage drew the curated
# groups and usageSection drew Active/Last Apps; both walked .chunked(columns)
# and added one row View per chunk. Neither may do so again.
declare -A KEY=([buildPage]=buildPage [usageSection]=usage \
                [makeAppTile]=appTile [makeFolderTile]=folderTile)
for fn in buildPage usageSection; do
    if printf '%s\n' "$(body "${KEY[$fn]}")" | grep -qF '.chunked('; then
        bad "$fn still chunks its tiles into rows -- that IS the second line"
    else
        ok "$fn no longer chunks its tiles into rows"
    fi
done
# Both must actually go through the strip, or "no chunking" could just mean the
# section stopped rendering.
for fn in buildPage usageSection; do
    if printf '%s\n' "$(body "${KEY[$fn]}")" | grep -qF 'tileStrip(ctx'; then
        ok "$fn renders its apps through tileStrip"
    else
        bad "$fn does not call tileStrip -- its apps are laid out some other way"
    fi
done

echo "== T3: strip tiles size themselves in a way that survives a scrolling row =="
# A HorizontalScrollView measures its child with an UNSPECIFIED width spec, so
# there is no excess width to hand out and a LayoutParams(0, _, weight) child
# keeps its zero. A weighted tile in a strip is an invisible tile.
for fn in makeAppTile makeFolderTile; do
    TILE="$(body "${KEY[$fn]}")"
    if printf '%s\n' "$TILE" | grep -qE 'LayoutParams\(0,[^)]*, *1f\)'; then
        bad "$fn still sizes its cell with a weight -- inside a strip that collapses to zero width"
    else
        ok "$fn does not size its cell with a weight"
    fi
    if printf '%s\n' "$TILE" | grep -qF 'LayoutParams(cellWidth(ctx)'; then
        ok "$fn sizes its cell from cellWidth()"
    else
        bad "$fn does not size its cell from cellWidth() -- the strip's cell geometry is undefined"
    fi
done

echo "== T4: a long app name cannot force a second line =="
# The label is the classic cause of a row growing. It is constrained rather than
# allowed to dictate the cell.
TILE="$(body appTile)"
LABEL_LINES="$(printf '%s\n' "$TILE" | grep -c 'maxLines = 1')"
if [ "$LABEL_LINES" -ge 2 ]; then
    ok "every text line in an app tile is capped at one line ($LABEL_LINES sites)"
else
    bad "only $LABEL_LINES capped text line(s) in makeAppTile -- a long name can grow the row"
fi
if printf '%s\n' "$TILE" | grep -qF 'TruncateAt.END'; then
    ok "an over-long label is ellipsised rather than wrapped"
else
    bad "no ellipsize on the tile label -- a long name has nowhere to go but a second line"
fi

echo "== T5: the strip's density stays data-driven =="
# UI_PHONE_GRID_COLUMNS used to decide where the line broke. There is no line
# break now, so it decides how many tiles are visible at rest -- but it must
# still be the thing deciding, not a number typed into the Kotlin.
CELL="$(body cellWidth)"
if printf '%s\n' "$CELL" | grep -qF 'BuildConfig.UI_PHONE_GRID_COLUMNS'; then
    ok "cellWidth() derives from build.json::ui.phone_grid_columns"
else
    bad "cellWidth() ignores UI_PHONE_GRID_COLUMNS -- the columns knob no longer reaches the page"
fi
COLUMNS="$(jq -r '.ui.phone_grid_columns' "$BUILD_JSON")" || {
    echo "FATAL: jq failed reading ui.phone_grid_columns"; exit 2; }
if printf '%s' "$COLUMNS" | grep -qE '^[1-9][0-9]*$'; then
    ok "build.json still declares a positive phone_grid_columns ($COLUMNS)"
else
    bad "ui.phone_grid_columns is '$COLUMNS' -- cellWidth() would divide by it"
fi

echo "== T6: the merged page loads Smart Folders asynchronously =="
PAGE="$(body buildPage)"
if printf '%s\n' "$PAGE" | grep -qE 'PhoneAppsFragment\.renderSmartFolders\('; then
    bad "buildPage still calls the SYNCHRONOUS renderSmartFolders -- the page waits on it again"
else
    ok "buildPage does not call the synchronous renderSmartFolders"
fi
if printf '%s\n' "$PAGE" | grep -qF 'addSmartFoldersSection(ctx'; then
    ok "buildPage installs the lazy Smart Folders section"
else
    bad "buildPage does not install the lazy section -- Smart Folders reach the page some other way"
fi

echo "== T7: the expensive selection runs off the main thread =="
ASYNC="$(body async)"
if printf '%s\n' "$ASYNC" | grep -qF 'Thread {'; then
    ok "renderSmartFoldersAsync does its work on a background Thread"
else
    bad "renderSmartFoldersAsync starts no Thread -- the package-manager IPCs are back on the main thread"
fi
if printf '%s\n' "$ASYNC" | grep -qF 'computeSmartFolders(appContext'; then
    ok "the selection runs against the application Context, not the fragment's Activity"
else
    bad "the background work captures the fragment's Context -- that is a leak for the run of the thread"
fi
# Off the main thread is only half of it: the RESULT has to come back to it.
# Views may only be touched from the thread that made them, so a render left on
# the worker is not a slow page, it is a CalledFromWrongThreadException. Caught
# by mutating body.post{} to run{} and finding this block still green.
if printf '%s\n' "$ASYNC" | grep -qF 'body.post {'; then
    ok "the result is posted back to the main thread before any View is touched"
else
    bad "the render is not posted back -- Views would be built on the worker thread and crash"
fi
# And the selection itself must build no Views, or moving it off the main thread
# would trade a slow page for a crash.
COMPUTE="$(body compute)"
VIEWY="$(printf '%s\n' "$COMPUTE" | grep -cE 'addView|LinearLayout\(|TextView\(|ImageView\(')"
if [ "$VIEWY" -eq 0 ]; then
    ok "computeSmartFolders creates no Views -- safe to call off the main thread"
else
    bad "computeSmartFolders touches $VIEWY View construct(s) -- creating Views off the main thread crashes"
fi

echo "== T8: the section starts collapsed and expands itself =="
SECTION="$(body section)"
# THIS BLOCK WAS VACUOUS ON ITS FIRST DRAFT, and was caught by flipping the
# section's initial visibility to VISIBLE and watching the tester stay green.
# It grepped the whole function for `visibility = View.GONE`, which also
# matches paint()'s ternary and the status line's own hide -- so the one
# statement it existed to pin was the one statement it could not see. The
# assertions below name the state instead: the section is collapsed at birth
# because `expanded` starts false and visibility is a pure function of it, and
# nothing may set it visible outside that function.
if printf '%s\n' "$SECTION" | grep -qF 'var expanded = false'; then
    ok "the section's initial state is collapsed"
else
    bad "the section does not start collapsed -- #261 asks for exactly that"
fi
if printf '%s\n' "$SECTION" | grep -qF 'section.visibility = if (expanded) View.VISIBLE else View.GONE'; then
    ok "the section's visibility is driven solely by that state"
else
    bad "the section visibility is not a function of the expanded flag -- it can show while collapsed"
fi
if printf '%s\n' "$SECTION" | grep -qE '^[[:space:]]*visibility = View\.VISIBLE'; then
    bad "the section view is constructed VISIBLE -- it is on screen before any state says so"
else
    ok "no view in the section is constructed already-visible"
fi
if printf '%s\n' "$SECTION" | grep -qF 'expanded = true'; then
    ok "the section expands itself when the result arrives"
else
    bad "nothing ever expands the section -- it would stay shut forever"
fi

echo "== T9: a result arriving after the user left cannot touch a dead view =="
if printf '%s\n' "$ASYNC" | grep -qF 'body.isAttachedToWindow'; then
    ok "renderSmartFoldersAsync checks the target view is still attached"
else
    bad "no attach check before the background result writes into the view -- this is the standard crash"
fi
if printf '%s\n' "$SECTION" | grep -qF 'isAdded'; then
    ok "the callback checks the fragment is still added before touching its state"
else
    bad "the callback does not check isAdded -- it mutates a destroyed fragment's view state"
fi

echo "== T10: the auto-expand does not override the user =="
# "Expand when ready" must not mean "expand over whatever they just did".
if printf '%s\n' "$SECTION" | grep -qF 'userChose = true'; then
    ok "a tap on the header records that the state is the user's"
else
    bad "nothing records a manual collapse -- the background result would fight the user"
fi
if printf '%s\n' "$SECTION" | grep -qF 'if (!userChose) expanded = true'; then
    ok "the auto-expand is skipped once the user has chosen"
else
    bad "the auto-expand does not consult the user's choice"
fi

echo "== T11: empty and failed are told apart, and from still-loading =="
# A section that sits collapsed and silent is indistinguishable from one still
# working. Three outcomes, three different things on the header.
for token in '"loading…"' '"none"' '"unavailable"'; do
    if printf '%s\n' "$SECTION" | grep -qF "$token"; then
        ok "the header can say $token"
    else
        bad "no $token state -- an empty or failed fetch reads as still loading"
    fi
done
if printf '%s\n' "$ASYNC" | grep -qF 'onDone(computed?.isNotEmpty())'; then
    ok "the async result distinguishes rendered / empty / failed"
else
    bad "the async result collapses its three outcomes -- the caller cannot tell them apart"
fi

echo "== T12: refreshing drops the smart-folder cache with everything else =="
# sCachedSmart is derived from sCachedApps. Leaving it behind would redraw
# yesterday's Stores and Rank folders over a freshly enumerated All Apps.
INVALIDATE="$(body invalidate)"
if printf '%s\n' "$INVALIDATE" | grep -qF 'sCachedSmart = emptyMap()'; then
    ok "invalidateCache() clears the smart-folder cache"
else
    bad "invalidateCache() leaves sCachedSmart behind -- refresh would redraw a stale section"
fi

echo "== T13: a second visit does not re-run the thousand IPCs =="
# Rotation and re-entry rebuild the fragment. Without a cache hit that is the
# whole cost paid again, every time, which is the bug wearing a different hat.
if printf '%s\n' "$ASYNC" | grep -qF 'sCachedSmart[exclude]'; then
    ok "renderSmartFoldersAsync serves a repeat visit from the cache"
else
    bad "no cache lookup -- every rotation re-runs the package-manager scan"
fi

echo
echo "-- test-phone-apps-strip-and-lazy-smart-folders: $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
