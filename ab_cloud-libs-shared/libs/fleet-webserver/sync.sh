#!/usr/bin/env bash
# Sync the verbatim Linux web UI into vendor/, or verify it has not drifted.
#
#   sync.sh --from <cloud-u-linux checkout>   copy the files, rewrite vendor/VENDOR.sha256
#   sync.sh --check [--from <checkout>]       fail unless vendor/ matches VENDOR.sha256
#                                             (and, with --from, the Linux repo's bytes)
#
# One source of truth: cloud-u-linux/da_my-webserver/src. vendor/ is never edited by
# hand; the Rust build (build.rs) refuses a vendor/ that does not match VENDOR.sha256.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
V="$HERE/vendor"
# <vendor name>  <path under da_my-webserver/src in cloud-u-linux>
FILES=(
  "browse.html lib/browse.html"
  "github-markdown-dark.css lib/github-markdown-dark.css"
  "js-yaml.min.js lib/js-yaml.min.js"
  "marked.min.js lib/marked.min.js"
  "my-webserver.cjs my-webserver.cjs"
)
mode=sync; from=""
while [ $# -gt 0 ]; do
  case "$1" in
    --check) mode=check ;;
    --from) from="$2"; shift ;;
    *) echo "usage: sync.sh [--check] [--from <cloud-u-linux>]" >&2; exit 2 ;;
  esac; shift
done
src() { echo "$from/da_my-webserver/src/$1"; }
if [ "$mode" = sync ]; then
  [ -n "$from" ] || { echo "sync.sh: --from <cloud-u-linux checkout> required" >&2; exit 2; }
  for e in "${FILES[@]}"; do set -- $e; cp "$(src "$2")" "$V/$1"; done
  ( cd "$V" && sha256sum $(for e in "${FILES[@]}"; do set -- $e; echo "$1"; done) > VENDOR.sha256 )
  c="$(git -C "$from" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "cloud-u-linux da_my-webserver/src @ $c" > "$V/SOURCE"
  echo "synced $(wc -l < "$V/VENDOR.sha256") files from $from ($c)"
  exit 0
fi
( cd "$V" && sha256sum --check --strict VENDOR.sha256 )
# a hash list that forgot a file would pass the line above: every mirrored file must be listed
for e in "${FILES[@]}"; do
  set -- $e
  grep -q "  $1\$" "$V/VENDOR.sha256" || { echo "VENDOR.sha256 does not list $1" >&2; exit 1; }
done
if [ -n "$from" ]; then
  for e in "${FILES[@]}"; do
    set -- $e
    cmp -s "$V/$1" "$(src "$2")" || { echo "DRIFT: vendor/$1 differs from the Linux repo's $2" >&2; exit 1; }
  done
  echo "vendor/ is byte-identical to $from"
fi
