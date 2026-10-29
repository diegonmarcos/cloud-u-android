#!/bin/sh
# render-readme.sh - render this repo's README.md from _dotfiles/readme/ (#1303).
#   _dotfiles/readme/render-readme.sh           write README.md
#   _dotfiles/readme/render-readme.sh --check   exit 1 when README.md differs byte-for-byte from the render
# GENERATED from _git-repos-master 9_others/templates/readme/render-readme.sh (node repos-sot.js sync-readmes).
# DO NOT EDIT here. Hand-owned input: _dotfiles/readme/readme.json. Everything else in this folder is a vendored copy.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec node "$here/readme-render.cjs" "$here/../.." "$@"
