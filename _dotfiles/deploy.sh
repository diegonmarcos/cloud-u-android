#!/bin/sh
# _dotfiles/deploy.sh — the ONE entrypoint for this repo's declarative dotfiles.
#
# Convention (_dotfiles): every repo keeps its dotfile declarations in a single
# top-level _dotfiles/ folder:
#   _dotfiles/git/{src,dist}    gitconfig, gitattributes, gitignore, gitmodules, LICENSE, hooks/
#   _dotfiles/apps/{src,dist}   claude, vscode, obsidian, github, githooks, root files (.mcp.json, .gitleaks.toml)
#
# Usage:
#   _dotfiles/deploy.sh            render src -> dist and install into the repo root (idempotent)
#   _dotfiles/deploy.sh --check    same render, then exit 1 if anything tracked changed (CI drift gate)
#
# The renderer is the repo's existing dotfiles builder (9_others/build.sh, or
# the my-ai dotfiles engine for repos without one); this script only fixes the
# entrypoint and the paths.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CHECK=0
case "${1:-}" in
    --check) CHECK=1 ;;
    ""|deploy) ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

render() {
    if [ -f "$ROOT/9_others/build.sh" ]; then
        bash "$ROOT/9_others/build.sh" ship
    else
        sh "$ROOT/9_others/src/deploy-dotfiles.sh" "$HERE/apps/src" "$HERE/apps/dist/dotfiles" "$ROOT"
    fi
}

if [ "$CHECK" = 0 ]; then render; exit 0; fi

cd "$ROOT"
git diff --quiet && git diff --cached --quiet || {
    echo "deploy.sh --check: working tree has tracked changes; run it on a clean tree" >&2; exit 2; }
render >/dev/null
if git diff --quiet; then echo "deploy.sh --check: OK (no drift)"; exit 0; fi
echo "deploy.sh --check: DRIFT — rendered output differs from committed files:" >&2
git diff --stat >&2
git checkout -- .
exit 1
