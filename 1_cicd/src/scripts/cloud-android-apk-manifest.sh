#!/usr/bin/env bash
# cloud-android-apk-manifest.sh — emit the declared APK list as JSON.
#
# Source of truth: every app module's own build.json (ac_*/ and aa_cloud-superapp/).
# Output: 2_configs/apk-manifest.json — consumed by cloud-infra's caddy generator
# (1_cloud-configs/src/derive/cloud-data-config-derive.ts) to emit the per-APK
# public vhost `cloud-{name}.diegonmarcos.com` and private `cloud-{name}.mesh.diegonmarcos.com`.
# There is no hand-maintained APK list: add a module with a build.json and the
# next run lists it.
#
# Per-module optional declarations in build.json:
#   "apk": false                      opt out (not an installable APK, e.g. a server)
#   "web": {"kind": "container"|"wasm"|"webapp", "target": "<container|path>"}
#         what a signed-in request to the APK's public host loads. Absent =>
#         the host redirects to the landing page even when signed in.
#
# Usage: cloud-android-apk-manifest.sh [--check]   (--check: exit 1 on drift)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="$ROOT/2_configs/apk-manifest.json"
cd "$ROOT"
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
files=()
for d in ac_*/ aa_cloud-superapp/; do
  d="${d%/}"; [ -f "$d/build.json" ] && files+=("$d/build.json")
done
for f in "${files[@]}"; do
  jq -c --arg dir "$(dirname "$f")" 'select(.apk != false) | {
      name: (if (.name|startswith("cloud-")) then .name else "cloud-" + .name end),
      module: $dir,
      application_id: (.android.application_id // null),
      description: ((.description // "") | split("\n")[0] | .[0:160]),
      web: (.web // null)
    }' "$f"
done | jq -s '{
  _generated_by: "1_cicd/src/scripts/cloud-android-apk-manifest.sh",
  _doc: "GENERATED. Declared Android apps, from each module build.json. Do not edit; change the module build.json and re-run the generator.",
  schema: "apk-manifest/v1",
  apps: (sort_by(.name))
}' > "$tmp"
if [ "${1:-}" = "--check" ]; then
  cmp -s "$tmp" "$OUT" || { echo "apk-manifest.json is stale; run $0" >&2; exit 1; }
  echo "apk-manifest.json up to date"; exit 0
fi
cp "$tmp" "$OUT"
echo "wrote $OUT ($(jq '.apps|length' "$OUT") apps)"
