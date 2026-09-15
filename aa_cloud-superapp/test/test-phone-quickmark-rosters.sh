#!/usr/bin/env bash
# Tester: #249 PART 1 -- Phone > Apps > Quickmarks, the ROSTER of the three
# sections the owner respecified, and the "Others" folder under each.
#
# WHAT THIS PINS THAT NOTHING ELSE DID. The surface already had testers for the
# MECHANISM -- test-phone-quickmark-placeholders proves a curated entry renders
# even when the package is absent, test-phone-apps-strip-and-lazy-smart-folders
# proves the rows scroll sideways and the Smart Folders load lazily, and
# test-camera-tiles proves one specific package sits in one specific group. Not
# one of them says WHICH APPS the AI, Inboxes and Data Apps sections hold. The
# rosters were edited into build.json by hand and nothing could tell whether a
# later edit dropped an app, reordered a section, or quietly put a Cloud
# Terminal back into AI. That is what this file is for.
#
# THE REMOVAL IS AN ASSERTION, NOT AN ABSENCE. "AI: remove the Cloud Terminal
# apps" is the half of the ticket a roster comparison would pass by accident
# the moment somebody re-added one under a new id, so T3 checks the shape of
# the ids rather than a list of the ones that used to be there: nothing in a
# Quickmark may be one of the fleet's own packages. A roster equality test
# alone would go green on a roster that is simply wrong in a new way.
#
# ORDER IS PART OF THE SPEC. SuitePhoneAppsFragment renders the curated array
# unsorted -- array position IS render position, which test-camera-tiles T12
# independently pins -- so the owner's order is data, and comparing as sets
# would discard it. Every roster below is compared as an ORDERED sequence.
#
# NOT COVERED HERE, DELIBERATELY: the ticket's Data Apps line also names
# "Google Password". No package id for it is declared anywhere in this
# repository and Google Password Manager ships no standalone launcher package
# of its own -- it is a surface inside com.google.android.gms. Curating a
# guessed id would not fail quietly: AppInstall.start sends a placeholder tap
# to market://details?id=<pkg>, so a wrong id becomes a permanent tile that
# offers to install an application that does not exist, which is exactly the
# failure test-phone-quickmark-placeholders T2 exists to prevent. It is left
# undeclared and reported as a blocker rather than guessed into the data.
#
# Usage: ./test-phone-quickmark-rosters.sh   (static only, no network, no device)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> ~/git/cloud-u-android
APP="$ROOT/aa_cloud-superapp"
BUILD_JSON="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# FAIL CLOSED on tooling and inputs before any assertion runs -- a missing file
# or a missing jq must never be reported as a passing verdict.
for tool in jq python3; do
    command -v "$tool" >/dev/null || {
        echo "FATAL: $tool is not on PATH -- refusing to report a verdict"; exit 2; }
done
[ -r "$BUILD_JSON" ] || { echo "FATAL: cannot read $BUILD_JSON"; exit 2; }
jq -e . "$BUILD_JSON" >/dev/null || { echo "FATAL: $BUILD_JSON is not valid JSON"; exit 2; }

# The Quickmarks groups live on the PHONE section, selected by its id. Finding
# the section by array index would break the moment a section is inserted
# above it, and this ticket inserts things.
QUICKMARKS='.ui.sections[] | select(.id == "phone") | .phone_app_groups'

# A group's packages are a mixed array: some entries are a bare package string,
# others an object carrying the label the placeholder tile needs. Both forms
# are legal input to the fragment, so both are normalised here rather than one
# being declared the wrong one.
pkgs_of_group() {  # $1 = group title
    jq -r --arg t "$1" "$QUICKMARKS"'[] | select(.title == $t) | .packages[]
           | if type == "string" then . else .pkg end' "$BUILD_JSON"
}
pkgs_of_folder() { # $1 = group title, $2 = folder label
    jq -r --arg t "$1" --arg f "$2" "$QUICKMARKS"'[] | select(.title == $t)
           | .folders[]? | select(.label == $f) | .packages[]
           | if type == "string" then . else .pkg end' "$BUILD_JSON"
}

# Compare an ORDERED sequence against the ticket's wording.
expect_seq() {  # $1 = what, $2 = expected (newline separated), $3 = actual
    if [ "$2" = "$3" ]; then
        ok "$1 is exactly the roster the owner specified, in his order"
    else
        bad "$1 does not match the specified roster
      expected: $(echo "$2" | tr '\n' ' ')
      actual:   $(echo "$3" | tr '\n' ' ')"
    fi
}

echo "== T1: each respecified section is a Quickmarks group, found by TITLE and unique =="
# #249 inserts groups into this same array, so every index after an insertion
# point shifts. Title is the only stable handle, and it is only a handle while
# it is unique.
for title in "AI" "Inboxes" "Data Apps"; do
    n="$(jq -r --arg t "$title" "$QUICKMARKS"'[] | select(.title == $t) | .title' "$BUILD_JSON" | wc -l)"
    if [ "$n" -eq 1 ]; then
        ok "exactly one Quickmarks group is titled '$title'"
    else
        bad "$n Quickmarks groups are titled '$title' -- selecting it by title is ambiguous or impossible"
    fi
done

echo "== T2: AI holds Claude, Gemini, Termux, Nix and nothing else =="
expect_seq "AI" "$(printf '%s\n' \
    com.anthropic.claude \
    com.google.android.apps.bard \
    com.termux \
    com.termux.nix)" "$(pkgs_of_group 'AI')"
expect_seq "AI > Others" "$(printf '%s\n' \
    com.foxdebug.acode \
    com.rhmsoft.edit)" "$(pkgs_of_folder 'AI' 'Others')"

echo "== T3: no Quickmark curates one of the fleet's OWN apps =="
# "Remove the Cloud Terminal apps" stays true only if it is checked as a rule.
# The fleet's packages are cld.* and com.diegonmarcos.* -- the Cloud Terminals
# that this ticket removed from AI were cld.termux and cld.termux.nix, and a
# roster equality check would not notice a different one arriving tomorrow.
# Captured BEFORE the pipe to sort, so jq's own exit status survives: an
# "absent" built out of a jq that failed is the vacuous pass this whole file is
# written to avoid, and "no results" and "the query broke" look identical once
# both are the empty string.
OURS_RAW="$(jq -r "$QUICKMARKS"'[] | (.packages[]?, (.folders[]?.packages[]?))
        | if type == "string" then . else .pkg end
        | select(startswith("cld.") or startswith("com.diegonmarcos."))' "$BUILD_JSON")" \
    || { echo "FATAL: the Quickmarks query failed -- refusing to read its silence as a pass"; exit 2; }
OURS="$(printf '%s' "$OURS_RAW" | sort -u)"
if [ -z "$OURS" ]; then
    ok "no cld.* or com.diegonmarcos.* package is curated into any Quickmarks group"
else
    bad "the fleet's own package(s) are curated as Quickmarks: $(echo "$OURS" | tr '\n' ' ')-- Phone is the third-party half of the surface"
fi

echo "== T4: Inboxes holds the six the owner listed, and Others holds his six =="
expect_seq "Inboxes" "$(printf '%s\n' \
    com.google.android.gm \
    com.Slack \
    com.whatsapp.w4b \
    com.linkedin.android \
    com.instagram.android \
    com.google.android.apps.magazines)" "$(pkgs_of_group 'Inboxes')"
expect_seq "Inboxes > Others" "$(printf '%s\n' \
    ch.protonmail.android \
    app.sterna \
    com.mattermost.rn \
    com.beeper.android \
    org.telegram.messenger \
    com.devhd.feedly)" "$(pkgs_of_folder 'Inboxes' 'Others')"

echo "== T5: Data Apps holds the owner's list, and Others holds his six =="
expect_seq "Data Apps" "$(printf '%s\n' \
    com.google.android.apps.docs \
    com.google.android.calendar \
    com.google.android.contacts \
    com.microsoft.office.onenote \
    com.google.android.apps.photos \
    com.aspiro.tidal)" "$(pkgs_of_group 'Data Apps')"
expect_seq "Data Apps > Others" "$(printf '%s\n' \
    com.sec.android.app.myfiles \
    com.lonelycatgames.Xplore \
    me.proton.android.drive \
    md.obsidian \
    com.x8bit.bitwarden \
    com.viscouspot.gitsync)" "$(pkgs_of_folder 'Data Apps' 'Others')"

echo "== T6: every curated entry in these sections can caption a placeholder =="
# A not-installed tile has no launcher label to borrow, so it draws the label
# declared beside the package. An entry written as a bare string has none, and
# its placeholder would caption itself with a raw package id.
for title in "AI" "Inboxes" "Data Apps"; do
    BARE="$(jq -r --arg t "$title" "$QUICKMARKS"'[] | select(.title == $t)
            | (.packages[]?, (.folders[]?.packages[]?))
            | select(type == "string")' "$BUILD_JSON")" \
        || { echo "FATAL: the label query for '$title' failed -- refusing to read its silence as a pass"; exit 2; }
    if [ -z "$BARE" ]; then
        ok "every entry under '$title' carries a label for its not-installed tile"
    else
        bad "'$title' has label-less entries ($(echo "$BARE" | tr '\n' ' ')) -- a placeholder for one would caption itself with a package id"
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "-- test-phone-quickmark-rosters: $PASS passed, 0 failed --"; exit 0
else
    echo "-- test-phone-quickmark-rosters: $PASS passed, $FAIL failed --"; exit 1
fi
