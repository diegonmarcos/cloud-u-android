#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #630 — Volumes is TWO sections that look like two, the fleet list is the  ║
# ║ WHOLE fleet, and no row offers a path Android guarantees will fail        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# THREE DEFECTS THE OWNER HIT ON THE DEVICE, all of them structural.
#
# 1. "SEPARATE TWO SECTIONS!! IS TWO SECTIONS!!" — #613 declared two sections and
#    then rendered both pill strips at the top of ONE shared body with ONE shared
#    selection, so the two headers were two captions over a single list. Declaring
#    a section and drawing one are different things, and only the second is visible.
#
# 2. Four fleet apps in the section, not the fleet. data/drive-constellation.json
#    WAS the list and carried four entries; the fleet manifest carries every app.
#    That is the #170/#380/#381 defect shape — a second, shorter list of the same
#    thing — and it reads as "the fleet has four apps".
#
# 3. Nothing opens. /storage/emulated/0/Android/data/<pkg>/ is readable ONLY by its
#    owning app since API 30; MANAGE_EXTERNAL_STORAGE is explicitly carved out of
#    Android/data and Android/obb, and Android 13+ closes the SAF route too. The
#    device is API 35, so the rows' "Not verifiable: cannot read <pkg>" and their
#    dead Open were the OS refusing correctly. A row must never offer an affordance
#    that cannot work; it states the wall and hands off to the app that CAN read it.
#
#   V1  two sections, each with its OWN header, strip, selection and rows.
#   V2  the fleet list is DERIVED from the constellation fleet manifest, uncapped.
#   V3  no Android/data path is declared openable or handed to the navigator.
#   MUT mutation-proof: one shared body, a capped/hand-picked list and an
#       Android/data path offered as openable each turn a check RED.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
SCREEN="$SRC/volumes/VolumesScreen.kt"
CLASSES="$SRC/volumes/VolumeClasses.kt"
GRADLE="$APP/app/build.gradle"
BJ="$APP/build.json"
OVERRIDES="$APP/data/drive-constellation.json"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$SCREEN" "$CLASSES" "$GRADLE" "$BJ" "$OVERRIDES" "$FLEET"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

# v1 <VolumesScreen.kt> : one body per section, and no state shared between them
v1() {
    local f="$1" bad=0
    grep -qE 'private fun ColumnScope\.VolumeSection\(' "$f" || { echo "    there is no per-section composable — a section cannot own its rows"; bad=1; }
    grep -qE 'Declarations\.volumes\.sections\.forEach' "$f" || { echo "    the declared sections are not rendered"; bad=1; }
    grep -qE 'Declarations\.volumes\.unsectioned\(\)' "$f" || { echo "    an unclaimed class would be lost (the #292 rule)"; bad=1; }
    # EXACTLY ONE AnimatedContent, and it must live inside VolumeSection: a body outside it is the
    # shared body #613 shipped, and two of them would mean a section body plus a screen body.
    local bodies; bodies=$(grep -cE 'AnimatedContent\(targetState' "$f")
    [ "$bodies" = "1" ] || { echo "    $bodies class bodies in the screen — a section's body is not the only one"; bad=1; }
    if ! awk '/private fun ColumnScope\.VolumeSection\(/{inside=1} inside && /AnimatedContent\(targetState/{found=1} END{exit !found}' "$f"; then
        echo "    the class body is drawn OUTSIDE VolumeSection — that is one shared body under two headers"; bad=1
    fi
    if ! awk '/private fun ColumnScope\.VolumeSection\(/{inside=1} inside && /var pick by rememberSaveable/{found=1} END{exit !found}' "$f"; then
        echo "    the selected class is not remembered PER SECTION — one strip would move the other"; bad=1
    fi
    # And no screen-level selection survives.
    if grep -qE 'var current by rememberSaveable' "$f"; then
        echo "    the screen still holds one shared `current` class across both sections"; bad=1
    fi
    grep -qE 'testTag\(DriveTags\.VOLUMES_SECTION\)' "$f" || { echo "    a section is not identifiable in the layout tree"; bad=1; }
    return $bad
}

# v2 <build.gradle> : the list is derived from the manifest and nothing caps it
v2() {
    local f="$1" bad=0
    grep -qE 'fleetById\.each \{ fleetId, fleetEntry ->' "$f" || { echo "    the fleet list is not derived from the fleet manifest"; bad=1; }
    grep -qE "fleetEntry\.kind != 'app'" "$f" || { echo "    the derivation does not select the manifest's apps"; bad=1; }
    grep -qE 'resolvedConstellation would render an empty section|yielded no .kind: app. entry' "$f" || { echo "    an empty derivation does not fail the build"; bad=1; }
    # A cap of any shape, inside the constellation block, is the four-app defect coming back.
    if awk '/def resolvedConstellation = \[\]/{inside=1} /def constellationB64/{inside=0} inside && /\.take\(|\.subList\(|\.first\(|\[0\.\./{print; found=1} END{exit !found}' "$f" >/dev/null; then
        echo "    the derived list is capped — the section would show a prefix of the fleet"; bad=1
    fi
    # The overrides file must not be the list any more: it may not name a package.
    return $bad
}

# v2b <drive-constellation.json> <constellation-fleet.json> : overrides only, and fewer than the fleet
v2b() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
over = json.load(open(sys.argv[1], encoding="utf-8"))
fleet = json.load(open(sys.argv[2], encoding="utf-8"))["apps"]
bad = 0
apps = over.get("apps") or []
for a in apps:
    if "package" in a:
        print("    override %s names a package — packages come from the fleet manifest only" % a.get("fleet")); bad += 1
    if not a.get("fleet"):
        print("    an override names no fleet id, so it can override nothing"); bad += 1
declared = [a for a in fleet if a.get("kind") == "app" and a.get("package")]
if len(declared) <= len(apps):
    print("    the fleet manifest yields %d apps and the overrides file carries %d — the overrides are still the list"
          % (len(declared), len(apps))); bad += 1
print("    fleet apps derived: %d (overrides: %d)" % (len(declared), len(apps)))
sys.exit(1 if bad else 0)
PYTHON
}

# v3 <build.json> <VolumeClasses.kt> : the owner-only tree is declared unopenable and never opened
v3() {
    local bj="$1" kt="$2" bad=0
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
v = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]["volumes"]
bad = 0
path = v.get("constellation_path") or ""
if "Android/data" in path or "Android/obb" in path:
    print("    ui.volumes.constellation_path is %s — that tree is owner-only since API 30 and can never open" % path); bad += 1
if "<package>" not in path:
    print("    ui.volumes.constellation_path carries no <package> placeholder"); bad += 1
if path.startswith("/"):
    print("    ui.volumes.constellation_path is absolute — the store's root is declared once, in storage.shared_root"); bad += 1
oo = v.get("constellation_owner_only") or {}
if not oo:
    print("    ui.volumes.constellation_owner_only is not declared: the wall is nowhere stated"); bad += 1
else:
    if oo.get("openable"):
        print("    constellation_owner_only.openable is true — that promises an Open the OS refuses"); bad += 1
    if "Android/data" not in (oo.get("path") or ""):
        print("    constellation_owner_only.path does not name the Android/data tree it is about"); bad += 1
    if int(oo.get("blocked_since_sdk") or 0) < 30:
        print("    blocked_since_sdk is %s; the carve-out landed in API 30" % oo.get("blocked_since_sdk")); bad += 1
    if not oo.get("handoff"):
        print("    handoff is off, so the row states a wall and offers nothing that works"); bad += 1
sys.exit(1 if bad else 0)
PYTHON
    # the owner-only path is PRINTED, never handed to the navigator
    grep -qE 'ownerOnlyPathOf\(app\.packageName\)' "$kt" || { echo "    the row never states the owner-only path"; bad=1; }
    if grep -qE 'onOpenPath\(Declarations\.volumes\.ownerOnlyPathOf|onOpenPath\(ownerOnly' "$kt"; then
        echo "    the owner-only path is handed to the navigator — an Open that cannot work"; bad=1
    fi
    grep -qE 'if \(ownerOnly != null && !ownerOnly\.openable\)' "$kt" || { echo "    the row does not gate its wall notice on the declaration"; bad=1; }
    grep -qE 'actions\.launchApp\(app\.packageName' "$kt" || { echo "    there is no handoff to the app that CAN read its own folder"; bad=1; }
    grep -qE 'SharedStore\.resolve\(Declarations\.volumes\.constellationPathOf' "$kt" || { echo "    the row's openable path is not the store leg"; bad=1; }
    return $bad
}

echo "── V1 two sections that look like two ──"
v1 "$SCREEN" && pass "each declared section owns its header, strip, selection and rows" || fail "the two declared sections still render as one list"

echo "── V2 the whole fleet, derived ──"
v2 "$GRADLE" && pass "the fleet list is derived from the constellation fleet manifest, uncapped" || fail "the fleet list is hand-picked or capped"
v2b "$OVERRIDES" "$FLEET" && pass "data/drive-constellation.json is label/icon overrides, not the list" || fail "the overrides file is still the list of apps"

echo "── V3 never an Open that cannot work ──"
v3 "$BJ" "$CLASSES" && pass "the owner-only tree is declared unopenable, stated in words, and handed off" || fail "an Android/data path is still offered as openable"

echo "── MUT mutations ──"
MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT

m_screen() { # <name> <python-edit>
    local copy="$MUT/VolumesScreen.kt"; cp "$SCREEN" "$copy"
    python3 - "$copy" <<PYTHON
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
$2
open(p, "w", encoding="utf-8").write(s)
PYTHON
    if v1 "$copy" >/dev/null 2>&1; then fail "MUT $1: the mutation passed — V1 does not hold"; else pass "MUT $1 goes RED"; fi
}
m_screen "one shared body under both headers" \
    's = s.replace("private fun ColumnScope.VolumeSection(", "private fun ColumnScope.NotASection(")'
m_screen "the per-section selection made screen-wide" \
    's = s.replace("var pick by rememberSaveable", "var current by rememberSaveable")'

copy="$MUT/build.gradle"; cp "$GRADLE" "$copy"
python3 -c "
import sys; p=sys.argv[1]; s=open(p,encoding='utf-8').read()
s=s.replace('resolvedConstellation.sort { it.label.toLowerCase() }', 'resolvedConstellation = resolvedConstellation.take(4)')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if v2 "$copy" >/dev/null 2>&1; then fail "MUT a capped fleet list passed — V2 does not hold"; else pass "MUT the derived list capped to four goes RED"; fi

copy="$MUT/build.json"; cp "$BJ" "$copy"
python3 -c "
import json,sys
p=sys.argv[1]; d=json.load(open(p,encoding='utf-8'))
d['ui']['volumes']['constellation_path']='/storage/emulated/0/Android/data/<package>/files'
json.dump(d,open(p,'w',encoding='utf-8'))" "$copy"
if v3 "$copy" "$CLASSES" >/dev/null 2>&1; then fail "MUT an Android/data path passed as openable — V3 does not hold"; else pass "MUT the Android/data tree offered as the openable path goes RED"; fi

copy="$MUT/build2.json"; cp "$BJ" "$copy"
python3 -c "
import json,sys
p=sys.argv[1]; d=json.load(open(p,encoding='utf-8'))
d['ui']['volumes']['constellation_owner_only']['openable']=True
json.dump(d,open(p,'w',encoding='utf-8'))" "$copy"
if v3 "$copy" "$CLASSES" >/dev/null 2>&1; then fail "MUT owner_only.openable=true passed — V3 does not hold"; else pass "MUT the owner-only tree declared openable goes RED"; fi

v1 "$SCREEN" >/dev/null 2>&1 && v2 "$GRADLE" >/dev/null 2>&1 && v3 "$BJ" "$CLASSES" >/dev/null 2>&1 \
    && pass "MUT control: the unmutated sources pass every mutated check" \
    || fail "MUT control: the unmutated sources do NOT pass — the mutations above prove nothing"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-volumes-sections: all checks passed"; else echo "test-drive-volumes-sections: $FAILURES check(s) FAILED"; exit 1; fi
