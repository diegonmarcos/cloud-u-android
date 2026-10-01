#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #755 — one Android Gradle Plugin pin: build.gradle's                     ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# #744 moved this fork to AGP 8.7, but the upstream nix build vendored with it
# (flake.nix + nix/gradle.lock, a gradle2nix lock) still pinned AGP 7.4.2 —
# a second build path that could no longer build the app, that no CI step ran
# (ship runs the engine with BYPASS_NIX=1), and that nothing flagged. It was
# deleted, not regenerated. A re-vendor from upstream brings it straight back,
# so this goes red on ANY file in the tree naming an AGP other than the one
# build.gradle actually builds with.
#
# Static, offline, no Android SDK: it reads source only.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

echo "── #755 single AGP pin assertions [$(basename "$DIR")] ──"

pin="$(grep -o "com\.android\.tools\.build:gradle:[0-9][0-9A-Za-z.-]*" "$DIR/build.gradle" | head -n1)"
if [ -n "$pin" ]; then ok "build.gradle pins $pin"; else bad "build.gradle names no AGP version"; fi

stale="$(grep -rnoI --exclude-dir=build --exclude-dir=.gradle \
    "com\.android\.tools\.build:gradle:[0-9][0-9A-Za-z.-]*" "$DIR" | grep -vF ":$pin")"
if [ -z "$stale" ]; then
    ok "no other file pins a different AGP"
else
    bad "files pin an AGP other than build.gradle's ($pin):"
    echo "$stale"
fi

[ "$fails" -eq 0 ] || exit 1
