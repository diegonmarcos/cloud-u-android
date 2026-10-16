#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ phone-apps-page-guard.test — prove the guard fails, not just     ║
# ║ that it runs                                                     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard that has only ever been watched succeeding is
# indistinguishable from a guard that returns 0 unconditionally. This
# repository has shipped an assertion that compared an expression to itself, a
# pipeline whose exit status was read through `tail`, and four testers that
# passed only because ripgrep was missing from the runner and their `rg` call
# failed open. Every case below BREAKS the page in one specific way, demands
# the guard notice that exact way, and throws the copy away. The break happens
# in a throwaway copy, never in the working tree.
#
# TWO OF THE CASES ARE ABOUT PROSE, and they are the reason the guard is a
# script rather than a grep. SuitePhoneAppsFragment.kt documents at length why
# `chunked` was the wrong shape — so a grep for `chunked` reads the explanation
# of the defect as the defect and goes red on the fix. The mirror of that is
# worse: a grep also reads a COMMENTED-OUT call as a live one, so commenting
# out the fix would keep it green. `a comment mentioning chunked is not a call`
# and `a commented-out tileStrip is not a call` pin both directions.
#
# No ripgrep anywhere: the runner has had it missing before, and testers here
# have passed on its absence rather than on their assertions.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-phone-apps-page-guard.py"
MANIFEST="1_cicd/src/data/phone-apps-page-guard.json"
SUITE="aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt"
PHONE="aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneAppsFragment.kt"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# One pristine copy, taken from the WORKING TREE rather than the git index: the
# guard has to be provable against the edit somebody is about to commit, not
# only against what is already committed.
PRISTINE="$WORK/pristine"

# WHICH FILES THE COPY HOLDS IS DERIVED FROM THE MANIFEST, NOT LISTED HERE. A
# region added to the manifest whose source the sandbox did not contain would
# make the guard say "does not exist — nothing to guard", every mutation aimed
# at it would fail for that reason instead of its own, and the new region would
# never once be exercised.
MANIFEST_FILES="$(python3 - "$ROOT/$MANIFEST" <<'MANIFEST_PY'
import json, sys
seen = []
for region in json.load(open(sys.argv[1], encoding="utf-8"))["regions"]:
    if region["source"] not in seen:
        seen.append(region["source"])
for path in seen:
    print(path)
MANIFEST_PY
)"
if [ -z "$MANIFEST_FILES" ]; then
    echo "FAIL   $MANIFEST names no sources — nothing to copy and nothing to prove."
    exit 1
fi
for relative in $MANIFEST_FILES "$GUARD" "$MANIFEST"; do
    if [ ! -f "$ROOT/$relative" ]; then
        echo "FAIL   $MANIFEST names $relative but it does not exist."
        exit 1
    fi
    mkdir -p "$PRISTINE/$(dirname "$relative")"
    cp "$ROOT/$relative" "$PRISTINE/$relative"
done

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
        sed 's/^/         /' <<<"$out" | head -12
        return
    fi
    ok "$label"
    grep -E "$want" <<<"$out" | head -1 | sed 's/^/       ↳ /'
}

# expect_clean <label> <mutator...> — the change must NOT trip the guard.
# Used for the edits that only LOOK like the defect to a text search.
expect_clean() {
    local label="$1"; shift
    local dir out status
    dir="$(sandbox)"
    "$@" "$dir"
    out="$(run_guard "$dir")"; status=$?
    rm -rf "$dir"
    if [ "$status" -ne 0 ]; then
        fail "$label — guard went red on something that is not the defect:"
        sed 's/^/         /' <<<"$out" | head -12
        return
    fi
    ok "$label"
}

# ── the tree as it stands must be clean ────────────────────────────
out="$(run_guard "$PRISTINE")"
if [ $? -eq 0 ]; then
    ok "working tree passes: $(tail -1 <<<"$out")"
else
    fail "working tree does not pass its own guard:"
    sed 's/^/         /' <<<"$out" | head -16
fi

# ── mutators ───────────────────────────────────────────────────────
edit_suite() { python3 - "$2/$SUITE" "$1" <<'KOTLIN'
import sys
path, kind = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

CURATED = "TileStrip(SuitePhoneAppsFragment.strip(group.title)) {"
USAGE   = "TileStrip(SuitePhoneAppsFragment.strip(title)) { for (a in apps) item(key = \"t:${a.pkg}\") { AppTile(a) } }"
SMART   = "PhoneAppsFragment.loadSmartFolders(ctx, ourApps)"

if kind == "wrap_curated_rows":
    # THE #260 DEFECT, PUT BACK: the strip replaced by tiles chunked into rows, so a group of thirteen apps draws
    # three stacked rows by construction.
    assert CURATED in text, "test bug: cannot find the curated strip"
    text = text.replace(CURATED, "packageTiles.chunked(BuildConfig.UI_PHONE_GRID_COLUMNS).forEach { rowChunk ->", 1)

elif kind == "wrap_usage_rows":
    # The same defect in the OTHER Quickmarks lines. The owner's "all apps line here" is plural; fixing only the
    # curated groups would leave these two wrapping on the same surface, which reads as not having fixed it.
    assert USAGE in text, "test bug: cannot find the usage strip"
    text = text.replace(USAGE, "apps.chunked(BuildConfig.UI_PHONE_GRID_COLUMNS).forEach { rowChunk -> Row { for (a in rowChunk) AppTile(a) } }", 1)

elif kind == "smart_folders_sync":
    # #261 undone: the page blocks on the fetch (runBlocking on the composition thread).
    assert SMART in text, "test bug: cannot find the smart-folders call"
    text = text.replace(SMART, "kotlinx.coroutines.runBlocking { " + SMART + " }", 1)

elif kind == "probe_outlives_the_view":
    # The coroutine stops being the composition's: produceState replaced by something the page does not cancel.
    old = "produceState<SmartState>(SmartState.Loading"
    assert old in text, "test bug: cannot find the produceState"
    text = text.replace(old, "produceStateOutsideTheComposition<SmartState>(SmartState.Loading", 1)

elif kind == "section_starts_expanded":
    # Collapsed-at-birth removed. This is the edit somebody makes while asking "why is this section hidden?", and it
    # puts the page back to waiting.
    old = "var expanded by remember { mutableStateOf(false) }"
    assert old in text, "test bug: cannot find the collapsed-at-birth state"
    text = text.replace(old, "var expanded by remember { mutableStateOf(true) }", 1)

elif kind == "strip_stops_scrolling":
    # TileStrip itself stops scrolling. Every caller still calls TileStrip, so every other rule in the manifest is
    # still satisfied and the page is still wrong - which is exactly why the strip is checked separately.
    old = "LazyRow(Modifier.fillMaxWidth().testTag(tag), content = content)"
    assert old in text, "test bug: cannot find the strip"
    text = text.replace(old, "LazyColumn(Modifier.fillMaxWidth().testTag(tag), content = content)", 1)

elif kind == "label_unbounded":
    # A long application name allowed to dictate the cell - the single most common cause of a tile spilling onto a
    # second line.
    assert "maxLines = 1" in text
    text = text.replace("maxLines = 1", "maxLines = 2")

elif kind == "rename_entry":
    # The guard's window run past the code it means to check. It must SAY it has lost the region rather than report
    # a clean sweep of nothing.
    text = text.replace("private fun SuitePhonePage(epoch: Int, refresh: () -> Unit)",
                        "private fun SuitePhonePageForSuite(epoch: Int, refresh: () -> Unit)", 1)

elif kind == "prose_mentions_chunked":
    # NOT A DEFECT. A comment that names the forbidden construct while explaining it. A grep goes red here; the
    # guard must not.
    assert CURATED in text
    text = text.replace(CURATED,
        "// Historical note: this used to be tiles.chunked(columns) with one row PER CHUNK.\n"
        "                    // It is a single sideways strip now.\n"
        "                    " + CURATED, 1)

elif kind == "commented_out_strip":
    # The mirror of the case above, and the one a grep gets wrong in the dangerous direction: the call is still in
    # the file, as text, but nothing runs it. The guard must treat it as absent.
    assert CURATED in text
    text = text.replace(CURATED, "// " + CURATED, 1)

else:
    raise SystemExit("test bug: unknown suite mutation %s" % kind)

open(path, "w", encoding="utf-8").write(text)
KOTLIN
}

edit_phone() { python3 - "$2/$PHONE" "$1" <<'KOTLIN'
import sys
path, kind = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

if kind == "fetch_on_main_thread":
    # The thousand package-manager binder round trips moved back onto the caller's thread: the suspend function
    # still suspends, but the work runs on whatever dispatcher called it - the UI thread.
    old = "withContext(Dispatchers.IO) {"
    assert old in text, "test bug: cannot find the IO hop"
    text = text.replace(old, "run {", 1)

elif kind == "fetch_back_on_a_raw_thread":
    # The #228 regression, not a slowness one: a second hand-rolled lazy-load engine beside the #286/#333 one, which
    # also silently drops cancellation because a raw Thread has none. Reads as an ordinary refactor in a diff.
    old = "            val generation = sCacheGeneration\n"
    assert old in text, "test bug: cannot find the generation capture"
    text = text.replace(old, old + "            Thread { }.start()\n", 1)

elif kind == "drop_stale_generation_check":
    # The cache poisoned by a fetch that began before Refresh: its result published after the invalidation.
    old = "generation == sCacheGeneration"
    assert old in text, "test bug: cannot find the generation check"
    text = text.replace(old, "true", 1)

else:
    raise SystemExit("test bug: unknown phone mutation %s" % kind)

open(path, "w", encoding="utf-8").write(text)
KOTLIN
}

# No mutation argument, so the sandbox directory arrives as $1.
empty_manifest() { python3 - "$1/$MANIFEST" <<'JSON'
import json, sys
# A manifest with no regions would let the guard sweep nothing and print a green
# tick for it — the "ran zero testers and passed" shape this suite exists to end.
path = sys.argv[1]
data = json.load(open(path, encoding="utf-8"))
data["regions"] = []
json.dump(data, open(path, "w", encoding="utf-8"), indent=2)
JSON
}

move_region_source() { python3 - "$2/$MANIFEST" "$1" <<'JSON'
import json, sys
# What a renamed or moved Kotlin file looks like to the manifest. The guard must
# say it has lost the code rather than sweep what is left and report it clean.
path, label = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding="utf-8"))
for region in data["regions"]:
    if region["label"] == label:
        region["source"] = region["source"].replace(".kt", "Moved.kt")
        break
else:
    raise SystemExit("test bug: no region labelled %s" % label)
json.dump(data, open(path, "w", encoding="utf-8"), indent=2)
JSON
}

# ── #260 — the rows must stay on one line ──────────────────────────
expect_caught "the curated Quickmarks rows wrapping again is caught" \
    "curated groups .#260.\]: .*calls 'chunked\('" \
    edit_suite wrap_curated_rows

expect_caught "the Active Apps / Last Apps rows wrapping again is caught" \
    "Active Apps and Last Apps rows .#260.\]: .*calls 'chunked\('" \
    edit_suite wrap_usage_rows

expect_caught "the strip itself losing its sideways scroll is caught" \
    "sideways-scrolling strip .*no longer contains 'LazyRow\('" \
    edit_suite strip_stops_scrolling

expect_caught "an unbounded tile label is caught" \
    "tile label .*no longer contains 'maxLines = 1'" \
    edit_suite label_unbounded

# ── #261 — the page must not wait on Smart Folders ─────────────────
expect_caught "the page blocking on the Smart Folders fetch is caught" \
    "curated groups .*calls 'runBlocking'" \
    edit_suite smart_folders_sync

expect_caught "the Smart Folders section starting expanded is caught" \
    "starts collapsed .*no longer contains 'var expanded by remember \{ mutableStateOf\(false\) \}'" \
    edit_suite section_starts_expanded

expect_caught "the Smart Folders fetch returning to the main thread is caught" \
    "off the main thread .*no longer contains 'withContext\(Dispatchers.IO\)'" \
    edit_phone fetch_on_main_thread

expect_caught "a second hand-rolled Thread engine coming back is caught" \
    "off the main thread .*calls 'Thread \{'" \
    edit_phone fetch_back_on_a_raw_thread

expect_caught "the probe outliving the view it paints into is caught" \
    "starts collapsed .*no longer contains 'produceState<SmartState>\(SmartState.Loading'" \
    edit_suite probe_outlives_the_view

expect_caught "dropping the stale-generation check is caught" \
    "off the main thread .*no longer contains 'generation == sCacheGeneration'" \
    edit_phone drop_stale_generation_check

# ── the guard must not be fooled by prose, in either direction ─────
expect_clean "a comment mentioning chunked is not a call" \
    edit_suite prose_mentions_chunked

expect_caught "a commented-out tileStrip is not a call" \
    "curated groups .#260.\]: .*no longer contains 'TileStrip\('" \
    edit_suite commented_out_strip

# ── the guard must not go quiet ────────────────────────────────────
expect_caught "the guard losing sight of a region is caught" \
    "cannot find the entry point" \
    edit_suite rename_entry

expect_caught "a manifest with no regions is caught" \
    "lists no regions" \
    empty_manifest

expect_caught "a region whose source file moved is caught" \
    "does not exist .+ nothing to guard" \
    move_region_source "Quickmarks — the curated groups (#260)"

if [ "$FAILURES" -eq 0 ]; then
    echo "PASS — the guard fails on every way this page can go back to wrapping or to blocking on Smart Folders."
    exit 0
fi
echo "FAIL — see above."
exit 1
