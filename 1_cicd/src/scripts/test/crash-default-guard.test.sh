#!/usr/bin/env bash
# #1122 the crash-default guard must be RED for each way an app can lose the default handler, and GREEN on a
# conforming tree. A fixture tree is planted per case; the real tree is checked by the workflow's next step.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/../cloud-android-crash-default-guard.py"
fails=0
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/ab_cloud-libs-shared/libs/core/src/main/java/com/diegonmarcos/superapp/core" "$r/ac_cloud-c3-webserver/server/src" "$r/ac_cloud-a/app/src/main"
  echo '{"not_linked":{}}' > "$r/1_cicd/src/data/crash-default-guard.json"
  echo '<manifest><application><provider android:name="x.CoreInitProvider" android:exported="false"/></application></manifest>' > "$r/ab_cloud-libs-shared/libs/core/src/main/AndroidManifest.xml"
  printf 'class CoreInitProvider { fun f(){ CrashReports.installHandler(c); CrashReports.flushLocal(c) } }\n' > "$r/ab_cloud-libs-shared/libs/core/src/main/java/com/diegonmarcos/superapp/core/CoreInitProvider.kt"
  mkdir -p "$r/ab_cloud-libs-shared/libs/crash/src/main"
  echo "api project(':libs:crash')" > "$r/ab_cloud-libs-shared/libs/core/build.gradle"
  echo 'buildConfigField "int", "CRASH_SINK_PORT", "1"' > "$r/ab_cloud-libs-shared/libs/crash/build.gradle"
  echo '<manifest><application><activity android:name="x.CrashActivity"/></application></manifest>' > "$r/ab_cloud-libs-shared/libs/crash/src/main/AndroidManifest.xml"
  echo 'path: "/__app__/api/crashes"' > "$r/ac_cloud-c3-webserver/server/src/app.rs"
  echo '{"android":{"application_id":"a.b"},"modules":{"libs:core":{"dir":"x"}}}' > "$r/ac_cloud-a/build.json"
  echo '<manifest/>' > "$r/ac_cloud-a/app/src/main/AndroidManifest.xml"
  echo "$r"
}
expect() { # name want-exit root
  python3 "$GUARD" "$3" > /tmp/crash-default-guard.$$ 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat /tmp/crash-default-guard.$$; fails=$((fails+1)); fi
  rm -rf "$3"
}
expect "a conforming tree is green" 0 "$(mk)"
r="$(mk)"; echo '{"android":{"application_id":"a.b"},"modules":{}}' > "$r/ac_cloud-a/build.json"; expect "an app that does not link libs:core is red" 1 "$r"
r="$(mk)"; echo '{"android":{"application_id":"a.b"},"modules":{"libs:core":{}},"crash_default":false}' > "$r/ac_cloud-a/build.json"; expect "crash_default=false is red" 1 "$r"
r="$(mk)"; echo '<manifest><application><provider android:name="x.CoreInitProvider" tools:node="remove"/></application></manifest>' > "$r/ac_cloud-a/app/src/main/AndroidManifest.xml"; expect "removing the provider in an app manifest is red" 1 "$r"
r="$(mk)"; echo 'class CoreInitProvider {}' > "$r/ab_cloud-libs-shared/libs/core/src/main/java/com/diegonmarcos/superapp/core/CoreInitProvider.kt"; expect "core no longer installing the handler is red" 1 "$r"
r="$(mk)"; : > "$r/ac_cloud-c3-webserver/server/src/app.rs"; expect "the web server dropping the sink route is red" 1 "$r"
r="$(mk)"; echo '{"not_linked":{"ac_cloud-a":"x"}}' > "$r/1_cicd/src/data/crash-default-guard.json"; expect "a stale not_linked entry is red" 1 "$r"
r="$(mk)"; echo "api project(':libs:fleetconfig-model')" > "$r/ab_cloud-libs-shared/libs/core/build.gradle"; expect "core dropping :libs:crash is red" 1 "$r"
r="$(mk)"; echo 'CRASH_SINK_PORT' > "$r/ab_cloud-libs-shared/libs/core/build.gradle"; expect "the sink port left out of libs:crash is red" 1 "$r"
r="$(mk)"; echo '<manifest/>' > "$r/ab_cloud-libs-shared/libs/crash/src/main/AndroidManifest.xml"; expect "libs:crash without the crash screen is red" 1 "$r"
r="$(mk)"; echo '{"android":{"application_id":"a.b"}}' > "$r/ac_cloud-a/build.json"; echo 'include(":libs:core")' > "$r/ac_cloud-a/settings.gradle.kts"; expect "an app that includes libs:core in its settings is green" 0 "$r"
r="$(mk)"; echo '{"android":{"application_id":"a.b"}}' > "$r/ac_cloud-a/build.json"; echo '// include(":libs:core")' > "$r/ac_cloud-a/settings.gradle.kts"; expect "a commented-out settings include is red" 1 "$r"
rm -f /tmp/crash-default-guard.$$
[ "$fails" -eq 0 ] && echo "crash-default-guard.test: all green" || { echo "crash-default-guard.test: $fails FAILED"; exit 1; }
