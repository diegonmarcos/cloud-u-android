#!/usr/bin/env bash
# gradle shim installed by .github/actions/gradle-cache-hygiene (see its action.yml).
# Runs the real gradle; on a dependency-resolution failure retries ONCE with
# --refresh-dependencies; if that fails on resolution too, deletes the resolution
# metadata so the failing job cannot save a negative lookup into the CI cache.
real="@REAL_GRADLE@"
if [ -n "${GRADLE_CACHE_HYGIENE_ACTIVE:-}" ]; then exec "$real" "$@"; fi
export GRADLE_CACHE_HYGIENE_ACTIVE=1

pattern='Could not find [^ ]+\.(aar|jar|pom|module)|Could not resolve all (files|dependencies|artifacts)|Could not resolve [a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:|Could not (GET|HEAD) |status code 429|Too Many Requests|Received status code 5[0-9][0-9]'
gh_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
err="$(mktemp "${RUNNER_TEMP:-/tmp}/gradle-hygiene-err.XXXXXX")"

run() {
  "$real" "$@" 2> >(tee "$err" >&2)
  local rc=$?
  wait $! 2>/dev/null
  return $rc
}

run "$@"; rc=$?
if [ $rc -eq 0 ] || ! grep -Eq "$pattern" "$err"; then rm -f "$err"; exit $rc; fi

echo "::warning title=gradle dependency resolution failed::retrying once with --refresh-dependencies (cached negative lookups or Maven 429)" >&2
sleep 20
run --refresh-dependencies "$@"; rc=$?
if [ $rc -ne 0 ] && grep -Eq "$pattern" "$err"; then
  echo "::warning title=gradle dependency resolution failed twice::deleting $gh_home/caches/modules-2/metadata-* so this job's cache save cannot keep the failed lookup" >&2
  "$real" --stop >/dev/null 2>&1 || true
  for m in "$gh_home"/caches/modules-2/metadata-*; do
    [ -d "$m" ] && rm -rf -- "$m"
  done
fi
rm -f "$err"
exit $rc
