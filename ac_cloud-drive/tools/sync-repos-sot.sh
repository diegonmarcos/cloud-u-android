#!/usr/bin/env bash
# #1073 refresh the vendored copy of the repo registry from its declared url (build.json
# storage.repos_source.url), re-pin its sha256 and regenerate data/drive-git-repos.json.
# A dev/CI-time step, never run on the phone.
set -euo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
URL="$(node -e 'console.log(require(process.argv[1]).storage.repos_source.url)' "$APP/build.json")"
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
api="$(printf '%s' "$URL" | sed -E 's#^https://raw.githubusercontent.com/([^/]+)/([^/]+)/(.+)/([^/]+)$#https://api.github.com/repos/\1/\2/contents/\4?ref=\3#')"
# the contents API is always fresh; raw.githubusercontent.com is CDN-cached for minutes after a push
curl -fsSL --max-time 30 -H 'Accept: application/vnd.github.raw' ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} "$api" -o "$tmp" || curl -fsSL --max-time 30 "$URL" -o "$tmp"
node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));if(r._schema!=="cloud-repos/v1"||!Array.isArray(r.repos))process.exit(1)' "$tmp"
cp "$tmp" "$APP/data/repos.sot.json"; cp "$tmp" "$ROOT/ab_cloud-libs-shared/libs/repos/repos.sot.json"
sha="$(sha256sum "$tmp" | cut -d' ' -f1)"
sed -i -E "s/(\"sha256\": \")[0-9a-f]{64}(\")/\1$sha\2/" "$APP/build.json"
node "$APP/tools/gen-drive-git-repos.js"
echo "repos.sot.json refreshed from $URL ($sha)"
