#!/usr/bin/env bash
# install.sh <tool>... — install each release binary tools.json pins.
#
# WHY THIS EXISTS. Thirty-odd workflows installed sops with
#     curl -fsSL <url> | sudo tee /usr/local/bin/sops >/dev/null
# in a `run:` step with no `shell:`, which GitHub runs as `bash -e` WITHOUT
# pipefail. When GitHub answered HTTP 500, curl -f failed, tee succeeded, the
# pipeline returned 0, and an EMPTY /usr/local/bin/sops was made executable --
# an empty executable runs as an empty script and exits 0, so `sops --version`
# passed too. The step went green and the build died much later on a missing
# SOPS_AGE_KEY, which was never the problem.
#
# So nothing here is piped: the download lands in a file, the file must match
# the pinned sha256, and only then is it installed and executed once. Every
# failure stops this script at the line that failed.
#
# INSTALL_TOOLS_PINS / INSTALL_TOOLS_DEST exist for the tester
# (1_cicd/src/scripts/test/install-pinned-tools.test.sh), which drives this
# exact file against a server that answers 500 and against a wrong checksum.
set -euo pipefail

pins="${INSTALL_TOOLS_PINS:-$(cd "$(dirname "$0")" && pwd)/tools.json}"
dest="${INSTALL_TOOLS_DEST:-/usr/local/bin}"
[ "$#" -gt 0 ] || { echo "usage: install.sh <tool>... (names from $pins)" >&2; exit 2; }
sudo=""; [ -w "$dest" ] || sudo="sudo"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for t in "$@"; do
    # -e: a tool tools.json does not name is an error, not a null URL.
    url="$(jq -er --arg t "$t" '.[$t].url' "$pins")"
    want="$(jq -er --arg t "$t" '.[$t].sha256' "$pins")"
    check="$(jq -er --arg t "$t" '.[$t].check' "$pins")"
    member="$(jq -r --arg t "$t" '.[$t].tar_member // empty' "$pins")"

    dl="$tmp/$t.download"
    # --retry-all-errors: a transient 500 is retried; a persistent one still fails.
    curl -fsSL --retry 3 --retry-all-errors -o "$dl" "$url"
    got="$(sha256sum "$dl" | cut -d' ' -f1)"
    [ "$got" = "$want" ] || {
        echo "::error::$t: sha256 of $url is $got, tools.json pins $want -- refusing to install it" >&2
        exit 1
    }

    bin="$dl"
    if [ -n "$member" ]; then
        tar -C "$tmp" -xzf "$dl" "$member"
        bin="$tmp/$member"
    fi
    $sudo install -m 0755 "$bin" "$dest/$t"
    # shellcheck disable=SC2086 -- check is one or more argv words from tools.json
    "$dest/$t" $check
done
