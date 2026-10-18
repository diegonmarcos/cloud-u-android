#!/bin/sh
# CI smoke: start the pinned Linux my-webserver on THIS terminal's build.json port and require
# the Linux UI at / (tree, viewer, toolbar markers; sha256 == cloud-u-linux's browse.html).
# One checker for both terminals: ab_cloud-terminal-store/webserver-ui-check.py. Needs network
# (CI only: it downloads the pinned release asset); an unreachable asset FAILS, never skips.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
echo "── Linux webserver UI [$(basename "$DIR")] ──"
exec python3 "$DIR/../ab_cloud-terminal-store/webserver-ui-check.py" "$DIR"
