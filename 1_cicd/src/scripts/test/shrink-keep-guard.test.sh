#!/bin/sh
# Mutation test for cloud-android-shrink-keep-guard.py: the guard must FAIL when Cloud Notes'
# keep file (the 1b01309f fix for the launch crash) is removed, and pass with it.
# Works on a copy of the files the guard reads for that one module; the tree is not touched.
set -eu
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-shrink-keep-guard.py"
APP=ac_cloud-notes/packages/frontend/apps/android/App/app
KEEP=$APP/src/main/res/raw/keep_nav_icons.xml
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
( cd "$ROOT" && find "$APP/build.gradle" "$APP/src/main" \( -name build.gradle -o -name '*.kt' -o -name '*.java' -o -path '*/raw/*.xml' \) -print ) |
  while read -r f; do mkdir -p "$T/$(dirname "$f")"; cp "$ROOT/$f" "$T/$f"; done
mkdir -p "$T/ab_cloud-libs-shared/libs"

python3 "$GUARD" "$T" > "$T/ok.log" || { cat "$T/ok.log"; echo "FAIL: guard rejects the unmutated Cloud Notes module"; exit 1; }
grep -q 'App/app/build.gradle: shrinks resources; 1 by-name' "$T/ok.log" || { cat "$T/ok.log"; echo "FAIL: guard did not see Cloud Notes' getIdentifier lookup"; exit 1; }

rm "$T/$KEEP"
if python3 "$GUARD" "$T" > "$T/mutant.log"; then
  cat "$T/mutant.log"; echo "FAIL: guard passed with $KEEP removed (the 1b01309f launch crash)"; exit 1
fi
grep -q '@drawable/ic_nav_\*' "$T/mutant.log" || { cat "$T/mutant.log"; echo "FAIL: guard failed but did not name @drawable/ic_nav_*"; exit 1; }
cat "$T/mutant.log"
echo "PASS: removing Cloud Notes' keep file is caught"
