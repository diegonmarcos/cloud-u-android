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

# WHICH THREAD IS A GIVEN CALL ON. Reads a comment-stripped function body on
# stdin, takes the text of a call, and answers with the kind of the INNERMOST
# dispatch region enclosing that call site -- "off-main", "main" or "none".
#
# WHY THIS ANCHOR AND NOT A TOKEN. T7 shipped red in run 35053915837 asserting
# the literal strings `Thread {` and `body.post {`. Those were never the
# property; they were ONE mechanism's spelling of it. #261 replaced the bare
# Thread with `lifecycleScope.launch { withContext(Dispatchers.IO) { ... } }`,
# which keeps both properties AND adds the cancellation a raw Thread can never
# have -- and the token assertions went red on an improvement. A tester that
# fails when the code gets better is not protecting anything, and the next
# agent's cheapest way out of it is to delete the assertion.
#
# So the question asked here is the question the RUNTIME asks: what is the
# innermost construct that changed threads before this line runs? Nesting
# resolves the way Kotlin resolves it, innermost wins, which is why the old
# `Thread { body.post { render } }` and the new `launch { withContext(IO) {} }`
# BOTH answer correctly without either being named in an assertion. Drifting
# off this anchor requires moving a call across a dispatch boundary -- that is,
# actually breaking the property -- instead of merely renaming, reformatting or
# re-spelling the mechanism, which is all it took last time.
#
# ADDING A MECHANISM IS THE ONE EDIT THIS INVITES: a new way to leave the main
# thread goes in OFF_MAIN, a new way to return to it goes in MAIN. An unknown
# construct answers "none" and so FAILS CLOSED -- it never answers "main" by
# default, so a mechanism nobody taught this scanner cannot pass by silence.
DISPATCH="$(mktemp)" || exit 2
cat > "$DISPATCH" <<'PY'
import re, sys

# Constructs that MOVE WORK OFF the main thread.
OFF_MAIN = [
    r"withContext\s*\(\s*Dispatchers\.(?:IO|Default)\b[^)]*\)\s*\{",
    r"\blaunch\s*\(\s*Dispatchers\.(?:IO|Default)\b[^)]*\)\s*\{",
    r"\basync\s*\(\s*Dispatchers\.(?:IO|Default)\b[^)]*\)\s*\{",
    r"\bThread\s*\(?\s*\{",
    r"\bthread\s*\([^)]*\)\s*\{",
    r"\b\w*[Ee]xecutor\w*\s*\.\s*(?:submit|execute)\s*\(?\s*\{",
]
# Constructs that bring work BACK ON the main thread. lifecycleScope.launch
# with no dispatcher argument belongs here: lifecycleScope is contractually
# Dispatchers.Main.immediate, and that is precisely what makes the resumption
# after a withContext(IO) block a main-thread resumption.
MAIN = [
    r"\.\s*post(?:Delayed)?\s*\(?\s*\{",
    r"\brunOnUiThread\s*\(?\s*\{",
    r"withContext\s*\(\s*Dispatchers\.Main\b[^)]*\)\s*\{",
    r"\blifecycleScope\s*\.\s*launch\s*\{",
    r"\blaunch\s*\(\s*Dispatchers\.Main\b[^)]*\)\s*\{",
]

body, call = sys.stdin.read(), sys.argv[1]


def close_of(source, open_brace):
    """Index of the brace matching source[open_brace], skipping string literals."""
    depth, index, size = 0, open_brace, len(source)
    while index < size:
        char = source[index]
        if char == '"':
            index += 1
            while index < size and source[index] != '"':
                index += 2 if source[index] == "\\" else 1
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return -1


regions = []
for kind, patterns in (("off-main", OFF_MAIN), ("main", MAIN)):
    for pattern in patterns:
        for match in re.finditer(pattern, body):
            opening = match.end() - 1          # every pattern ends on its own {
            closing = close_of(body, opening)
            if closing > 0:
                regions.append((opening, closing, kind, " ".join(match.group(0).split())))

site = re.search(re.escape(call), body)
if not site:
    print("__CALL_NOT_FOUND__")
    sys.exit(0)

enclosing = [r for r in regions if r[0] < site.start() < r[1]]
if not enclosing:
    print("none\tno dispatch region at all -- it runs on the caller's thread")
else:
    innermost = max(enclosing, key=lambda region: region[0])
    print(innermost[2] + "\t" + innermost[3])
PY

# RESOLVED THE SAME WAY THE BODIES ARE, AND FOR THE SAME REASON: at top level,
# into a file, never inside $( ). `exit` inside a command substitution ends the
# subshell and nothing else -- see the note above load(). Writes
# "<kind><TAB><the construct>" and fails closed, loudly, when the call is not
# there at all: a tester that cannot find the thing it is judging must never
# report a verdict on it.
dispatch() {  # dispatch <slot> <body-key> <call-text>
    if ! body "$2" | python3 "$DISPATCH" "$3" > "$BODIES/@$1"; then
        echo "FATAL: the dispatch scanner failed on $3 -- refusing to report a verdict"; exit 2
    fi
    if grep -qxF '__CALL_NOT_FOUND__' "$BODIES/@$1"; then
        echo "FATAL: $3 is not called in the $2 body -- this tester cannot judge which thread"
        echo "       a call it cannot find runs on. If the call was renamed, rename it here"
        echo "       too; if it was deleted, the behaviour it implemented went with it."
        exit 2
    fi
}

where() { cut -d"$(printf '\t')" -f2 "$BODIES/@$1"; }   # the construct
kind()  { cut -d"$(printf '\t')" -f1 "$BODIES/@$1"; }   # off-main | main | none

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
trap 'rm -rf "$SCANNER" "$DISPATCH" "$BODIES"' EXIT

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

# The two thread-placement facts T7 judges, resolved here with the bodies.
dispatch ipc    async 'computeSmartFolders(appContext'
dispatch render async 'renderSmartFolderBody(body.context'

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

echo "== T7: the expensive selection runs off the main thread, the render back on it =="
ASYNC="$(body async)"
# THESE TWO ASSERTIONS USED TO NAME A MECHANISM AND THAT IS WHY THEY DRIFTED.
# They read `Thread {` and `body.post {`; #261 replaced both with a cancellable
# coroutine and they went red on an improvement (#364, run 35053915837). They
# now ask which thread the two calls that matter actually run on, via the
# dispatch scanner above -- see its header for why that cannot drift the same
# way. Both remain FATAL assertions; neither was loosened, and the mutation
# proof for both is in the commit message.
#
# PROPERTY 1 -- THE PACKAGE-MANAGER IPCs ARE NOT ON THE MAIN THREAD. Four
# install_source rules call getInstallSourceInfo once per installed app and
# five ranking rules each walk a multi-day history; on a phone with a few
# hundred apps that is ~1000 synchronous binder round trips. On the main
# thread it is not a slow page, it is an ANR.
if [ "$(kind ipc)" = "off-main" ]; then
    ok "the package-manager selection runs off the main thread -- innermost dispatch is \`$(where ipc)\`"
else
    bad "computeSmartFolders runs on the MAIN thread ($(kind ipc): $(where ipc)) -- ~1000 package-manager IPCs before a frame is the ANR this page was fixed for"
fi
if printf '%s\n' "$ASYNC" | grep -qF 'computeSmartFolders(appContext'; then
    ok "the selection runs against the application Context, not the fragment's Activity"
else
    bad "the background work captures the fragment's Context -- that is a leak for the run of the probe"
fi
# PROPERTY 2 -- THE RENDER IS BACK ON THE MAIN THREAD BEFORE IT TOUCHES A VIEW.
# Off the main thread is only half of it. Views may only be touched from the
# thread that made them, so a render left on the worker is not a slow page, it
# is a CalledFromWrongThreadException.
if [ "$(kind render)" = "main" ]; then
    ok "the View render is handed back to the main thread -- innermost dispatch is \`$(where render)\`"
else
    bad "renderSmartFolderBody runs on a non-main thread ($(kind render): $(where render)) -- Views built there crash with CalledFromWrongThreadException"
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

echo "== T14: a fetch started before a refresh cannot re-poison the cache =="
# invalidateCache() clearing the map is not enough on its own. A thread that
# started BEFORE the refresh is still running after it, holding a result built
# from the app list that was just discarded, and it writes that result into the
# cleared map when it finishes. The generation counter is what makes the clear
# stick: the thread captures it before starting and publishes only if it still
# matches.
if printf '%s\n' "$INVALIDATE" | grep -qF 'sCacheGeneration++'; then
    ok "invalidateCache() advances the cache generation"
else
    bad "invalidateCache() does not advance the generation -- an in-flight fetch can undo the clear"
fi
if printf '%s\n' "$ASYNC" | grep -qF 'val generation = sCacheGeneration'; then
    ok "the background fetch captures the generation before it starts"
else
    bad "the fetch does not capture a generation -- it cannot tell if it was invalidated mid-flight"
fi
if printf '%s\n' "$ASYNC" | grep -qF 'generation == sCacheGeneration'; then
    ok "the fetch publishes only while its generation is still current"
else
    bad "the fetch publishes unconditionally -- a stale result can land after the clear"
fi

echo "== T15: Cloud Apps and Cloud Libs bypass the page's master exclusion (#468) =="
# The merged Suite→Phone page hands every smart folder an `apps` list that has
# already had the constellation's own packages stripped (exclude=ourApps). Two
# folders are the ENTIRE constellation — Cloud Apps (fleet_kind app) and Cloud
# Libs (fleet_kind lib) — so the exclusion removed their whole subject before
# select() ever ran. The fix declares the exception next to the folder in
# build.json (include_constellation: true) and stops pre-filtering the master
# list: a folder that opts in selects over the FULL enumeration, every other
# folder keeps the excluded list exactly as before. THE FIRST ASSERTION OF THE
# TWO-PART OBLIGATION: these two folders must be declared to see everything.
COMPUTE_BYPASS="$(body compute)"
if printf '%s' "$COMPUTE_BYPASS" | grep -qF 'sf.includeConstellation'; then
    ok "computeSmartFolders decides the source list per folder from the opt-in flag"
else
    bad "computeSmartFolders does not consult sf.includeConstellation -- the exception is not wired in"
fi
# The opt-in must route the folder to the FULL list, and the fall-through must
# keep the excluded list. Asserting the `when` branches forces the two into one
# decision, so a folder cannot silently drift between them.
if printf '%s' "$COMPUTE_BYPASS" | grep -qF 'sf.includeConstellation -> all'; then
    ok "an opting folder selects from the full list, every other folder from the excluded one"
else
    bad "the per-folder decision is not wired -- opting in does not change the list"
fi
# The declaration lives in build.json next to the folders it applies to; these
# two must carry it, and it alone.
CLOUD_OPTS="$(jq -r '[.ui.phone_smart_folders[] | select(.id=="cloud_apps" or .id=="cloud_libs")] | map(select(.include_constellation == true)) | length' "$BUILD_JSON")" || {
    echo "FATAL: jq failed reading include_constellation for the cloud folders"; exit 2; }
if [ "$CLOUD_OPTS" -eq 2 ]; then
    ok "both cloud folders are declared include_constellation: true in build.json"
else
    bad "expected 2 cloud folders (cloud_apps, cloud_libs) with include_constellation:true, saw $CLOUD_OPTS -- the bypass is missing its declaration"
fi

echo "== T16: every OTHER smart folder still excludes the constellation (#468) =="
# THE SECOND ASSERTION OF THE TWO-PART OBLIGATION, the half that stops this being
# "fixed" by deleting the filter. The master exclusion exists for a reason: it
# keeps the fleet's own APKs out of every ordinary folder. Deleting it would make
# Cloud Apps and Cloud Libs work AND silently corrupt Samsung, Google, Stores and
# the usage/rank folders on the SAME page with the SAME exclude set. Asserting
# the filter still exists, still runs per folder, and still applies to folders
# that did not opt in.
# The exclusion must still be computed from the caller's exclude set ...
if printf '%s' "$COMPUTE_BYPASS" | grep -qF 'it.packageName !in exclude'; then
    ok "the excluded list is still derived by stripping the caller's exclude set"
else
    bad "the master exclusion is no longer computed -- deleting it would leak the fleet into every folder"
fi
# ... and a folder that did NOT opt in must be handed that excluded list (not the
# full one). The `else excluded` branch is what keeps the filter live for every
# folder that leaves the flag off.
if printf '%s' "$COMPUTE_BYPASS" | grep -qF 'else -> excluded'; then
    ok "a folder that does not opt in still selects over the excluded list"
else
    bad "no non-opting branch hands folders the excluded list -- the master filter is dead for everyone"
fi
# No folder outside the two cloud ones may declare the bypass. Every ordinary
# folder on the same page, with the same exclude set, must keep excluding.
NONCLOUD_OPTS="$(jq -r '[.ui.phone_smart_folders[] | select(.id!="cloud_apps" and .id!="cloud_libs") | select(.include_constellation == true)] | length' "$BUILD_JSON")" || {
    echo "FATAL: jq failed reading include_constellation for non-cloud folders"; exit 2; }
if [ "$NONCLOUD_OPTS" -eq 0 ]; then
    ok "no folder outside the two cloud ones declares the bypass"
else
    bad "$NONCLOUD_OPTS non-cloud folder(s) also set include_constellation -- the exception escaped its two intended folders"
fi

echo "== T17: Cloud Libs membership comes from the INSTALLED fleet libs, not the launchable enumeration (#474) =="
# A library APK ships no launcher activity, so it never appears in the
# launchable universe every other smart folder filters over. The Cloud Libs
# folder's subject is therefore the FLEET MANIFEST (constellation-fleet.json,
# kind=="lib") intersected with what PackageManager says is installed -- not
# the launchable list. (And #468 could never fix this: that bypass only stops
# the master exclusion from REMOVING the constellation's own packages; a lib
# was never IN the list to be removed.)
SMART="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneSmartFolders.kt"
load libSlots "$SMART" 4 installedLibSlots
LIBS_BODY="$(body libSlots)"
if printf '%s\n' "$LIBS_BODY" | grep -qF 'Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)'; then
    ok "the lib set is built from the central fleet manifest"
else
    bad "installedLibSlots does not read the fleet manifest -- where does the lib list come from?"
fi
if printf '%s\n' "$LIBS_BODY" | grep -qF 'it.kind == "lib"'; then
    ok "the lib set filters the fleet to kind==lib (never mixes in apps)"
else
    bad "installedLibSlots does not filter to kind==lib -- the folder would list apps too"
fi
if printf '%s\n' "$LIBS_BODY" | grep -qF 'getPackageInfo'; then
    ok "each lib is confirmed INSTALLED via the package manager"
else
    bad "installedLibSlots does not check the install state -- it could invent a declared-but-missing lib"
fi
if printf '%s\n' "$LIBS_BODY" | grep -qF 'return@mapNotNull null'; then
    ok "a declared-but-not-installed lib contributes nothing to the folder (not invented)"
else
    bad "no skip for an absent package -- the folder would list libs that are not on the device"
fi
if printf '%s\n' "$LIBS_BODY" | grep -qF 'activityComponent = null'; then
    ok "a lib is recorded as NOT launchable (null component)"
else
    bad "a lib is given a launcher component it does not have -- the tap would wrongly offer to launch it"
fi
if printf '%s\n' "$LIBS_BODY" | grep -qF 'R.drawable.ic_cloud_lib'; then
    ok "a lib without its own icon uses the ONE shared fallback (ic_cloud_lib)"
else
    bad "no single shared fallback icon is referenced -- the rule demands ONE, declared once"
fi

echo "== T18: a fleet_kind=lib folder sources from the installed-lib set, never the launchable list (#474) =="
# The part of the fix that would be hollow-green if the licensing above were
# dead: computeSmartFolders must ACTUALLY hand the lib folder the installed
# set. Deleting this branch is exactly the pre-#474 bug -- the folder would
# select over the launchable universe, match nothing, and drop to null (empty).
COMPUTE_LIBS="$(body compute)"
if printf '%s\n' "$COMPUTE_LIBS" | grep -qF 'sf.selectsInstalledLibs -> libSlots'; then
    ok "the lib folder is routed to the installed-lib set"
else
    bad "the lib folder falls back to the launchable enumeration -- it renders empty, the exact #474 bug"
fi
if printf '%s\n' "$COMPUTE_LIBS" | grep -qF 'installedLibSlots(ctx)'; then
    ok "computeSmartFolders reaches the installed-lib enumerator"
else
    bad "computeSmartFolders never calls the installed-lib enumerator -- the branch is licensing only"
fi
if printf '%s\n' "$COMPUTE_LIBS" | grep -qF 'sf.includeConstellation -> all'; then
    ok "an opting-in folder (Cloud Apps) still selects the full launchable list"
else
    bad "the opt-in folder no longer selects the full list -- Cloud Apps would be filtered wrong"
fi

echo "== T19: a lib tile taps to the in-app Constellation AppStore -- never a no-op, never leaving the app (#474/#156) =="
# A lib cannot be launched (no component). Its tap must OPEN SOMETHING useful
# in-app; a silent nil-receiver on startMainActivity or an external ACTION_VIEW
# would each be the exact regression #156 exists to stop.
load expTile "$PHONE" 8 makeExpandedAppTile
load constellationHop "$PHONE" 8 openConstellation
TILE="$(body expTile)"
if printf '%s\n' "$TILE" | grep -qF 'app.activityComponent'; then
    ok "the expanded tile reads the (now nullable) launch component"
else
    bad "the tile does not consult activityComponent -- it cannot tell a lib from a launchable app"
fi
if printf '%s\n' "$TILE" | grep -qF 'comp != null'; then
    ok "the tile branches on whether the app is launchable"
else
    bad "the tile does not branch on launchability -- it would launch a lib's null component"
fi
if printf '%s\n' "$TILE" | grep -qF 'openConstellation(ctx)'; then
    ok "a non-launchable tile opens the store instead of silently no-opping"
else
    bad "no non-launchable tap path exists -- a lib tap either crashes or does nothing"
fi
if printf '%s\n' "$TILE" | grep -qF 'ACTION_VIEW'; then
    bad "the lib path fires an external ACTION_VIEW -- that leaves the app, which #156 forbids"
else
    ok "no external leave-the-app intent in the lib path"
fi
HOP="$(body constellationHop)"
if printf '%s\n' "$HOP" | grep -qF 'openSectionPage("config", "constellation"'; then
    ok "opening the lib's store is the in-app Configs ▸ Constellation section page"
else
    bad "the store hop is not the in-app constellation page -- it is not opening the lib's entry"
fi

echo "== T20: every lib carries the canonical cloud-lib-{name}, from its ONE declaration (#474/#351) =="
# The lib names used to be three incompatible shapes ('Lib: {name}' with no
# cloud prefix, 'cloud-keyboard-libs', 'termux-boot'). #351 set one cloud-{name}
# rule for APPS; it never reached the libs. The lib rule is cloud-lib-{name}
# with consistent dashes, landed at the single declaration in data/regen.sh
# (never a 38-entry hand list, never a second display-name field -- the #380
# mistake). termux-boot (cld.termux.nix.boot) is genuinely NOT a cloudlib, so it
# is the one deliberate exception to the pattern, asserted not licensed away.
FLEET_JSON="$APP/data/constellation-fleet.json"
LIBS_BAD="$(jq -r '.apps[] | select(.kind=="lib") | select((.label | startswith("cloud-lib-") | not) and .package != "cld.termux.nix.boot") | "\(.id)=\(.label)"' "$FLEET_JSON")" || {
    echo "FATAL: jq failed reading lib labels"; exit 2; }
if [ -z "$LIBS_BAD" ]; then
    ok "every lib except the non-cloudlib termux-boot is named cloud-lib-{name}"
else
    bad "lib names drift off the canonical cloud-lib-{name} pattern: $LIBS_BAD" | head -1
fi
KB="$(jq -r '.apps[] | select(.package=="com.diegonmarcos.cloudkeyboardlibs") | .label' "$FLEET_JSON")" || {
    echo "FATAL: jq failed reading the keyboard-engines label"; exit 2; }
if [ "$KB" = "cloud-lib-keyboard-engines" ]; then
    ok "the keyboard-engines lib is named cloud-lib-keyboard-engines, one shape with the rest"
else
    bad "keyboard-engines reads '$KB' -- it must be cloud-lib-keyboard-engines (#474)"
fi
REGEN="$APP/data/regen.sh"
if grep -qF 'cloud-lib-' "$REGEN" && ! grep -qF '"Lib: "' "$REGEN"; then
    ok "regen.sh declares cloud-lib-{name} at the ONE lib declaration and emits no ad-hoc 'Lib: ' prefix"
else
    bad "regen.sh still emits the old lib-prefix shape -- the canonical name is not coming from the single declaration"
fi

echo
echo "-- test-phone-apps-strip-and-lazy-smart-folders: $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
