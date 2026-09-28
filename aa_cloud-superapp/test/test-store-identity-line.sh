#!/usr/bin/env bash
# Tester: ONE declared identity pattern for every Store row, and no field it
# cannot source (#631).
#
# THE FAILURE THIS EXISTS FOR. Store ▸ Cloud built its version/sha/size line per
# row, so the same three facts came out in as many shapes as there were call
# sites — `v0.1.1-dev (sha-abc1234)  ·  41.2 MB` on the collapsed row, a second
# composition on the expanded one, a third in the detail sheet — and two of the
# three fields were WRONG about what they showed:
#
#   1. the version was the INSTALLED APK versionName, which for a comms fork is
#      upstream numbering and not the fleet version at all, and which for our
#      own apps already carries `(sha-…)` inside it, so the row printed the sha
#      twice at two lengths;
#   2. the bracketed number was the APK versionCode, and ours is minutes since
#      2026 (app/build.gradle::codeFinal). Printed bare beside a version name it
#      reads as a release number;
#   3. a row with no known byte count ran 0 through the size formatter and
#      printed `0 KB` — a fabricated size, which is worse than admitting none.
#
# The fix is one formatter every surface calls, with an honest marker per field.
# Nothing here touches a device: every assertion is over declared data or over
# engine source, which is all this container can honestly read.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null || { echo "ERROR: jq required" >&2; exit 2; }

# Module dirs from build.json::modules, the same map settings.gradle resolves —
# a hardcoded path here would be a second statement of where a shared module
# lives, which is the class of defect this tester is about.
updir="$(jq -r '.modules["libs:updater"].dir // empty' "$BJ")"
stdir="$(jq -r '.modules["libs:appstore"].dir // empty' "$BJ")"
[ -n "$updir" ] && [ -n "$stdir" ] || {
    echo "ERROR: build.json::modules is missing libs:updater or libs:appstore" >&2; exit 2; }
FMT="$APP/$updir/src/main/java/com/diegonmarcos/superapp/updater/FleetIdentity.kt"
ROW="$APP/$stdir/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"
SHEET="$APP/$stdir/src/main/java/com/diegonmarcos/superapp/appstore/ApkDetailSheet.kt"
for f in "$FMT" "$ROW" "$SHEET"; do
    [ -f "$f" ] || { echo "ERROR: not found: $f" >&2; exit 2; }
done

# ── T1 — the pattern exists, in one place, and names all three fields ────────
if grep -E 'enum class Field \{ VERSION, SHA, SIZE \}' "$FMT" >/dev/null \
   && grep -E 'val (FULL|SCAN): Set<Field>' "$FMT" >/dev/null; then
    ok "T1 FleetIdentity declares the pattern as an ordered field set (version, sha, size)"
else
    bad "T1 FleetIdentity no longer declares the pattern — a row with no declared field order composes its own again (#631)"
fi

# ── T2 — every field has an honest marker, and none of them is empty ─────────
miss=""
for k in NO_VERSION NO_SHA NO_SIZE; do
    grep -E "const val $k = \"[^\"]+\"" "$FMT" >/dev/null || miss="$miss $k"
done
if [ -z "$miss" ]; then
    ok "T2 each of the three fields has a non-empty honest marker for when it cannot be sourced"
else
    bad "T2 missing honest marker(s):$miss — a field with no marker prints a gap, a zero or a guess (#624)"
fi

# ── T3 — 0 bytes is NOT a size, decided in the one place ─────────────────────
if grep -E 'if \(bytes > 0L\) "size \$\{human\(bytes\)\}" else NO_SIZE' "$FMT" >/dev/null; then
    ok "T3 size() refuses 0 bytes and says so instead of formatting it"
else
    bad "T3 size() no longer gates on a real byte count — an unknown size renders as 0 KB, a size the page never measured (#631 mutation b)"
fi

# ── T4 — a non-hex digest is not a sha ──────────────────────────────────────
# Fleet.State.UpdateAvailable carries the literal "release" on the one path
# where no .sha256 sidecar answered and the size compare decided instead.
if grep -E 'takeIf \{ it.isNotBlank\(\) && it.all \{ c -> c in "0123456789abcdefABCDEF" \} \}' "$FMT" >/dev/null; then
    ok "T4 only a hex digest renders after the word sha; the release sentinel reads as not published"
else
    bad "T4 the sha field no longer checks that it HAS a digest — the literal \"release\" prints as one (#631 mutation b)"
fi

# ── T5 — the version part labels every number, and OUR version is the
#         declared one, not the installed APK string ─────────────────────────
if grep -E 'declared code \$code' "$FMT" >/dev/null \
   && grep -E 'apk build \$buildCode' "$FMT" >/dev/null; then
    ok "T5 both numbers are labelled: the declared code and the wall-clock apk build"
else
    bad "T5 the version part no longer distinguishes the declared code from the wall-clock apk build — a build number reads as a release version (#631 mutation c)"
fi
if grep -E 'declaredVersionName' "$FMT" >/dev/null \
   && ! grep -E '(Installed|UpdateAvailable)\)\?\.versionName' "$FMT" >/dev/null; then
    ok "T5b the version comes from the manifest declaration, never from the installed APK versionName"
else
    bad "T5b the version is read off the installed package again — for a fork that is upstream numbering, not ours (#631)"
fi

# ── T6 — no surface composes its own version/sha/size string ────────────────
# The two row lines and the detail sheet must ASK. Anything matching a hand-made
# version or size shape in those files is a second pattern.
second=""
grep -nE '"v\$\{?[a-z]' "$ROW" "$SHEET" >/dev/null 2>&1 && second="$second version-literal"
grep -nE '"sha \$\{?[a-z]' "$ROW" "$SHEET" >/dev/null 2>&1 && second="$second sha-literal"
grep -nE '%\.[01]f MB' "$ROW" "$SHEET" >/dev/null 2>&1 && second="$second size-format"
if [ -z "$second" ]; then
    ok "T6 neither row line nor the detail sheet builds a version, sha or size string of its own"
else
    bad "T6 a second identity format lives in the row or the sheet:$second — one pattern means one place (#631 mutation a)"
fi

# ── T7 — both row surfaces, and the sheet, read the one formatter ────────────
for pair in "$ROW:FleetIdentity.SCAN" "$ROW:FleetIdentity.FULL" \
            "$SHEET:FleetIdentity.version" "$SHEET:FleetIdentity.size"; do
    f="${pair%%:*}"; needle="${pair##*:}"
    if grep -F "$needle" "$f" >/dev/null; then
        ok "T7 $(basename "$f") reads $needle"
    else
        bad "T7 $(basename "$f") does not read $needle — a surface that stopped asking is a surface that went back to composing (#631)"
    fi
done

# ── T8 — the manifest carries OUR version, and only where declared ──────────
FLEET_JSON="$APP/data/constellation-fleet.json"
[ -f "$FLEET_JSON" ] || { echo "ERROR: not found: $FLEET_JSON" >&2; exit 2; }
withname="$(jq '[.apps[] | select((.version_name // "") != "")] | length' "$FLEET_JSON")"
total="$(jq '.apps | length' "$FLEET_JSON")"
if [ "$withname" -gt 0 ] && [ "$withname" -le "$total" ]; then
    ok "T8 the fleet manifest declares version_name on $withname of $total rows"
else
    bad "T8 the fleet manifest declares no version_name at all — every row falls back to the marker, so regen.sh stopped emitting OUR version (#631)"
fi
# The superapp itself must be one of them: it is the one app whose declared pair
# this repo owns outright, so an absent entry there is regen.sh broken, not a
# fork keeping upstream numbering.
if [ "$(jq -r '[.apps[] | select(.id == "aa_cloud-superapp") | .version_code] | first // 0' "$FLEET_JSON")" -gt 0 ]; then
    ok "T8b the host row carries its declared version_code"
else
    bad "T8b aa_cloud-superapp has no version_code in the manifest — the identity line cannot show OUR version for the app drawing it (#631)"
fi
# And a row that declares nothing must say nothing: an empty string or a 0 in
# the data would defeat the marker before the UI ever sees it.
if [ "$(jq '[.apps[] | select(has("version_name") and .version_name == "")] | length' "$FLEET_JSON")" -eq 0 ] \
   && [ "$(jq '[.apps[] | select(has("version_code") and .version_code == 0)] | length' "$FLEET_JSON")" -eq 0 ]; then
    ok "T8c no row carries an empty version_name or a 0 version_code — undeclared means absent"
else
    bad "T8c a row carries an empty/zero declared version — that is a placeholder the UI cannot tell from a real one (#631)"
fi

echo
echo "store-identity-line: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
