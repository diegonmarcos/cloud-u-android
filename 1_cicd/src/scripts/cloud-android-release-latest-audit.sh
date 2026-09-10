#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-release-latest-audit                               ║
# ║                                                                  ║
# ║ No publisher may take the "latest" pointer from the rolling      ║
# ║ release.                                                         ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# THE FAILURE THIS EXISTS TO CATCH.
# Our rolling release is tagged the literal word `latest`, and every install
# URL baked into a phone that is already in the owner's hand goes through
# /releases/latest/download/<asset>. That route resolves by RECENCY, not by tag
# name. So any release created afterwards becomes "latest" and every one of
# those URLs starts answering 404 until the next rolling publish re-pins it.
# The breakage flaps rather than sticking, which is why it read as an
# intermittent phone fault for days rather than as a publishing bug.
#
# `gh release create` recomputes the pointer unless it is told not to. A
# release that is an immutable per-tag artifact must therefore say
# --latest=false; the one release that is deliberately the rolling head says
# --latest. A call site that says NEITHER is a hijacker.
#
# WHY THIS IS AN ENGINE AND NOT A `git grep` IN THE TESTER.
# The predecessor of this check searched for the literal bash array idiom
#
#     flags=("$GITHUB_REF_NAME"
#
# under `1_cicd/src/scripts` and `*/build.sh`. That found the 27 engines which
# happen to be written that way, verified all 27, and reported green — while
# ab_cloud-libs-shared/libs/firewall/publish-firestack.sh, which is neither a
# build.sh nor written in that idiom, went on creating an unpinned release on
# every publish. It was firestack-aar-20260910.114222 that was MEASURED holding
# the pointer at 11:57:46Z while cloud-nixdroid.apk answered 404 through it.
# The check that was supposed to prevent that had already passed on the commit
# that introduced it.
#
# So the subject set is derived from the CALL, not from the shape of the source
# text around it, and the classification reads the whole logical command.
#
# TELLING A CALL FROM PROSE.
# Naive text search cannot, and getting this wrong in either direction is what
# this file is about. The two shapes that are NOT call sites, both of which
# occur many times in this repository:
#
#     log "gh release create $GITHUB_REF_NAME ← $asset"   # a log message
#     contents: write   # gh release create + tag push    # a YAML comment
#
# Both are removed by deleting double-quoted strings and then comment tails
# before asking whether the line invokes anything. Quoted strings go FIRST, so
# a `#` inside a message cannot be mistaken for the start of a comment.
#
# The flag analysis then runs on the ORIGINAL text, because stripping quotes
# would also remove the `"${flags[@]}"` that names the array carrying the
# flags, and the array is where --latest=false lives in the build.sh engines.
#
# EXIT
#   0  every call site declares its intent
#   1  at least one call site can steal the pointer
#   3  no call site found at all — the publish path moved and this audit has
#      silently stopped having subjects. A check with no subjects must fail,
#      not pass; that is the failure mode this repository keeps rediscovering.
set -eu

ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"

command -v awk >/dev/null 2>&1 || { echo "awk is required" >&2; exit 3; }
command -v git >/dev/null 2>&1 || { echo "git is required" >&2; exit 3; }

# ── the files to audit ─────────────────────────────────────────────
# Asked of git rather than of the filesystem, so an untracked scratch copy of
# an engine cannot add or remove a subject.
#
# EXCLUDED, each for a reason and not for convenience:
#   z_archive/            dead code, kept for reading only
#   */test/*              testers quote these commands as fixtures and prose
#   <app>/.github/        VENDORED upstream CI. ac_cloud-vault carries
#                         Bitwarden's own workflow tree; GitHub only ever runs
#                         the repository-root .github/workflows, so a release
#                         command in there is not one of our publishers and
#                         pinning it would be editing someone else's CI.
_subjects() {
    git -C "$ROOT" grep -l -F 'gh release create' -- \
        '*.sh' '*.yml' \
        ':(exclude)z_archive/**' \
        ':(exclude)*/test/*' \
        ':(exclude)*/.github/**' \
        2>/dev/null || true
}

_classify() {
    awk '
    # Everything after here works on two views of the same line: `bare` with
    # quoted strings and comments removed, used only to decide "is this a
    # call"; and the raw line, which carries the flags.
    function strip(s,   prev) {
        prev = ""
        while (s != prev) { prev = s; sub(/"[^"]*"/, "", s) }
        sub(/#.*$/, "", s)
        return s
    }
    function flush(   eff, v) {
        if (cmd == "") return
        eff = cmd
        # `gh release create "${flags[@]}"` carries its flags in an array built
        # a few lines earlier. Resolve the name back to its assignment so the
        # array idiom is judged on what it actually passes.
        if (match(eff, /\$\{[A-Za-z_][A-Za-z0-9_]*\[@\]\}/)) {
            v = substr(eff, RSTART + 2, RLENGTH - 6)
            if (v in arr) eff = eff " " arr[v]
        }
        if (eff ~ /--latest=false/)        verdict = "PINNED"
        else if (eff ~ /--latest([^=]|$)/) verdict = "ROLLING"
        else                               verdict = "HIJACKER"
        printf "%s\t%s:%d\n", verdict, FILENAME, start
        cmd = ""
    }
    # PER FILE, NOT PER RUN. Without this reset the remembered arrays leak
    # across the file boundary, and since every engine names its array `flags`,
    # ONE still-pinned copy vouched for every copy audited after it — the audit
    # reported green with a genuinely unpinned engine in the set. Watched
    # failing: un-pinning ac_cloud-browser alone was not detected until this
    # line existed, because ac_cloud-calendar had been read first.
    FNR == 1 { delete arr; cmd = "" }

    { raw = $0; bare = strip(raw) }

    # Remember array assignments so flush() can resolve "${name[@]}".
    match(raw, /[A-Za-z_][A-Za-z0-9_]*=\(/) {
        nm = substr(raw, RSTART, RLENGTH - 2)
        sub(/^.*local[ \t]+/, "", nm)
        arr[nm] = arr[nm] " " raw
    }

    {
        # A command continued with a trailing backslash is ONE logical call;
        # firestack spells its create across seven lines and the --latest flag
        # of any such call could sit on any of them.
        if (cmd != "") { cmd = cmd " " raw; if (raw !~ /\\$/) flush(); next }
        if (bare ~ /gh release create/) {
            start = FNR; cmd = raw
            if (raw !~ /\\$/) flush()
        }
    }
    END { flush() }
    ' "$@"
}

cd "$ROOT"
SUBJECTS="$(_subjects)"
[ -n "$SUBJECTS" ] || {
    echo "no file in this repository calls 'gh release create' — the publish path has moved and this audit has no subjects" >&2
    exit 3
}

# shellcheck disable=SC2086
RESULT="$(_classify $SUBJECTS)"
[ -n "$RESULT" ] || {
    echo "found files naming 'gh release create' but no call site in any of them — refusing to pass vacuously" >&2
    exit 3
}

case "${1:-}" in
    --list) printf '%s\n' "$RESULT" | LC_ALL=C sort; exit 0 ;;
esac

HIJACKERS="$(printf '%s\n' "$RESULT" | awk -F'\t' '$1 == "HIJACKER" { print $2 }' | LC_ALL=C sort)"
TOTAL="$(printf '%s\n' "$RESULT" | awk 'END { print NR }')"

if [ -n "$HIJACKERS" ]; then
    echo "these create a release without saying whether it is the latest, so it can steal /releases/latest/download/:"
    printf '%s\n' "$HIJACKERS" | awk '{ print "    " $0 }'
    exit 1
fi

echo "all $TOTAL release-creation sites declare --latest or --latest=false"
exit 0
