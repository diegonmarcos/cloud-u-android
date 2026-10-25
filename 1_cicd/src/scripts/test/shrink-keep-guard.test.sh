#!/bin/sh
# Mutation test for cloud-android-shrink-keep-guard.py: on a shrinking module that looks a drawable up BY NAME
# (the shape of the 1b01309f launch crash, Cloud Notes' old getIdentifier("ic_nav_$name")), the guard must FAIL
# when the keep file is removed, and pass with it. Cloud Notes itself no longer resolves any resource by name
# (#1164: the shared nav draws from libs:ui-icons), so the module is a fixture built here; the tree is not touched.
set -eu
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-shrink-keep-guard.py"
APP=fixture_app/app
KEEP=$APP/src/main/res/raw/keep_nav_icons.xml
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/$APP/src/main/java" "$T/$APP/src/main/res/raw" "$T/ab_cloud-libs-shared/libs"
cat > "$T/$APP/build.gradle" <<'G'
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
        }
    }
}
G
cat > "$T/$APP/src/main/java/Nav.kt" <<'K'
fun icon(name: String): Int = resources.getIdentifier("ic_nav_$name", "drawable", packageName)
K
cat > "$T/$KEEP" <<'X'
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@drawable/ic_nav_*" />
X

python3 "$GUARD" "$T" > "$T/ok.log" || { cat "$T/ok.log"; echo "FAIL: guard rejects the unmutated module"; exit 1; }
grep -q 'app/build.gradle: shrinks resources; 1 by-name' "$T/ok.log" || { cat "$T/ok.log"; echo "FAIL: guard did not see the getIdentifier lookup"; exit 1; }

rm "$T/$KEEP"
if python3 "$GUARD" "$T" > "$T/mutant.log"; then
  cat "$T/mutant.log"; echo "FAIL: guard passed with $KEEP removed (the 1b01309f launch crash)"; exit 1
fi
grep -q '@drawable/ic_nav_\*' "$T/mutant.log" || { cat "$T/mutant.log"; echo "FAIL: guard failed but did not name @drawable/ic_nav_*"; exit 1; }
cat "$T/mutant.log"
echo "PASS: removing the keep file of a by-name lookup is caught"
