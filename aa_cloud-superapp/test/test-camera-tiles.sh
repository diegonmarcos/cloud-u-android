#!/usr/bin/env bash
# Tester: the two camera entries the owner asked for on 2026-09-10 land on the
# two DIFFERENT pages he named, and the Cloud one keeps the position he named.
#
#   "cloud-sa/cloud/apps/tools primary: as last Camera (the Cloud-camera);
#    phone/apps/Tools Primary: add here the Samsung Camera App"
#
# THE FAILURE THIS EXISTS FOR is that both pages contain a grouping called
# "Tools · Primary" and they are backed by unrelated structures:
#
#   Cloud ▸ Apps ▸ Tools Primary   ui.sections[id=cloud].tile_groups[title=
#                                  "Tools Primary"].tiles — the constellation's
#                                  OWN apps, each an extapp:<id> into
#                                  ui.external_apps.
#   Phone ▸ Apps ▸ Tools · Primary ui.phone_sections[prefix="="] — a SECTION,
#                                  whose members are the ui.phone_folders whose
#                                  label starts with "=", populated by
#                                  `pkg:` keywords over INSTALLED THIRD-PARTY
#                                  packages.
#
# Put the Samsung package on the first and the Cloud Camera on the second and
# every file still parses, every other tester still passes, and only the owner's
# eye on the device finds out. So each assertion below names the structure it
# reads, and the two package identifiers are checked against each other's
# absence as much as their own presence.
#
# THE SECOND FAILURE is "as last". Nothing in this row carries an `order`:
# GroupedTilesFragment walks group.tiles in declared order and applies no sort,
# so LAST IS ARRAY POSITION and a presence-only assertion would pass while the
# instruction was broken. T1 reads the last element and nothing else.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
REPO="$(cd "$APP/.." && pwd)"                    # → cloud-u-android
BJ="$APP/build.json"
CAMERA_BJ="$REPO/ac_cloud-camera/build.json"
CAMERA_GRADLE="$REPO/ac_cloud-camera/app/build.gradle.kts"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# Fail CLOSED. A missing jq does not fail an assertion, it makes every jq
# expression answer "" and every "is this absent" check agree enthusiastically —
# which is how four testers in this directory passed for a week on a runner with
# no ripgrep. Same for the two files in the sibling application directory: this
# tester's whole claim about cld.camera is that it was read from THEM.
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
for f in "$BJ" "$CAMERA_BJ" "$CAMERA_GRADLE"; do
  [ -r "$f" ] || { echo "ERROR: cannot read $f" >&2; exit 2; }
done

CLOUD_TILE_TARGET="extapp:cloud-camera"
SAMSUNG_PKG="com.sec.android.app.camera"
OPUS_PKG="pl.mobimax.cameraopus"

echo "== T1: Camera is the LAST tile of Cloud ▸ Apps ▸ Tools Primary =="
# Read the last element, not a membership test. Appending anything after the
# Camera tile is the failure; so is a future `sort_by` in GroupedTilesFragment,
# which T2 is here to notice.
LAST="$(jq -r '
  .ui.sections[] | select(.id == "cloud")
  | .tile_groups[] | select(.title == "Tools Primary")
  | .tiles[-1] | [ (.label // ""), (.target // "") ] | @tsv' "$BJ")"
LAST_LABEL="$(printf '%s' "$LAST" | cut -f1)"
LAST_TARGET="$(printf '%s' "$LAST" | cut -f2)"
[ "$LAST_LABEL" = "Camera" ] && [ "$LAST_TARGET" = "$CLOUD_TILE_TARGET" ] \
  && ok "last tile of the row is '$LAST_LABEL' → $LAST_TARGET" \
  || bad "last tile of Cloud Tools Primary is '$LAST_LABEL' → '$LAST_TARGET', not Camera → $CLOUD_TILE_TARGET ('as last' is array position; nothing may be appended after it)"

echo "== T2: the row is rendered in DECLARED order, with no sort =="
# The assertion T1 depends on. If the renderer ever sorts, "last in the array"
# stops meaning "last on the page" and T1 becomes decoration that passes.
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/GroupedTilesFragment.kt"
[ -r "$FRAG" ] || { echo "ERROR: cannot read $FRAG" >&2; exit 2; }
SORTS="$(grep -nE 'group\.tiles[^)]*\.(sorted|sortBy|sortedBy|sortedWith|reversed)' "$FRAG" || true)"
[ -z "$SORTS" ] \
  && ok "GroupedTilesFragment renders group.tiles unsorted, so array position IS render position" \
  || bad "GroupedTilesFragment now reorders group.tiles — 'as last' can no longer be expressed by appending: $SORTS"

echo "== T3: the Cloud tile points at OUR camera, and the id is not written twice =="
# extapp:<id> is an indirection on purpose — the package lives in
# ui.external_apps and nowhere else. Three independent sources have to agree,
# and none of them is this tester's own opinion of what the package is:
#   the SuperApp's external_apps entry, ac_cloud-camera/build.json, and the
#   Gradle file the APK is actually built from.
HUB="$(jq -r '.ui.external_apps[] | select(.id == "cloud-camera") | .hub_package // ""' "$BJ")"
DECLARED="$(jq -r '.android.application_id // ""' "$CAMERA_BJ")"
# awk, not sed: the field wanted is the quoted value, and asking for the field
# is clearer than describing the characters around it.
BUILT="$(awk -F'"' '/^[[:space:]]*applicationId[[:space:]]*=/ { print $2; exit }' "$CAMERA_GRADLE")"
if [ -z "$HUB" ]; then
  bad "no ui.external_apps entry with id 'cloud-camera' — extapp:cloud-camera resolves to nothing and the tile can neither open nor offer an app"
elif [ -z "$DECLARED" ] || [ -z "$BUILT" ]; then
  bad "could not read the camera application id (build.json='$DECLARED', gradle='$BUILT') — the check that the tile names the right app did not run"
elif [ "$HUB" = "$DECLARED" ] && [ "$HUB" = "$BUILT" ]; then
  ok "extapp:cloud-camera → $HUB, matching ac_cloud-camera/build.json ($DECLARED) and app/build.gradle.kts ($BUILT)"
else
  bad "the Cloud tile would open '$HUB' but ac_cloud-camera builds '$BUILT' (declared '$DECLARED') — the tile names an app nobody publishes"
fi

echo "== T4: the Camera tile's icon resolves to a drawable that exists =="
# Sections.iconResFor falls back to ic_link_tile in silence when a name resolves
# to nothing, so a typo is a wrong icon and never an error. Reuse of an icon
# another tile already carries is the same defect the other way round.
ICON="$(jq -r '
  .ui.sections[] | select(.id == "cloud")
  | .tile_groups[] | select(.title == "Tools Primary")
  | .tiles[-1] | .icon // ""' "$BJ")"
if [ -n "$ICON" ] && [ -f "$APP/app/src/main/res/drawable/$ICON.xml" ]; then
  USERS="$(jq -r --arg i "$ICON" '[ .. | objects | select((.icon // "") == $i) ] | length' "$BJ")"
  [ "$USERS" = "1" ] \
    && ok "icon '$ICON' exists as res/drawable/$ICON.xml and is used by this tile alone" \
    || bad "icon '$ICON' is declared by $USERS entries — two tiles drawing one glyph read as deliberate on a phone"
else
  bad "icon '$ICON' has no res/drawable/$ICON.xml — the tile silently falls back to ic_link_tile"
fi

echo "== T5: the Samsung camera lands in a folder of the Phone section 'Tools · Primary' =="
# THE KEYWORD PASS OF PhoneAppClassifier.classify, in jq: folders sorted by
# `order`, first matching keyword wins, and our own apps contribute the `pkg:`
# keyword ui.external_apps[].folder derives. Membership in one folder's list is
# NOT the assertion — a folder earlier in `order` claiming the package with a
# broader rule is exactly the failure that makes a correct-looking diff wrong on
# the device, and only running the pass can see it.
CLASSIFY='
def folders_merged:
  . as $root
  | [ $root.ui.phone_folders[]
      | . as $folder
      | .match_keywords = ( (.match_keywords // [])
          + [ $root.ui.external_apps[]
              | select(.folder == $folder.id)
              | ( .hub_package, .alt_package, .install_package,
                  ((.forks // {}) | to_entries[] | .value) )
              | select(. != null and . != "")
              | "pkg:" + . ] ) ];
def fold($pkg):
  ($pkg | ascii_downcase) as $p
  | ( [ folders_merged[] | select((.match_keywords // []) | length > 0) ]
      | sort_by(.order) ) as $folders
  | ( first( $folders[]
      | select( any( .match_keywords[];
            ascii_downcase as $k
            | if   ($k | startswith("pkg:")) then $p == $k[4:]
              elif ($k | startswith("pkg^")) then ($p | startswith($k[4:]))
              elif ($k | startswith("lbl:")) or ($k | startswith("lbl~")) then false
              else (($k | length) >= 4 and ($p | contains($k)))
              end ) ) ) // null );
'
# The prefix is DERIVED from ui.phone_sections, never spelled "=" here: the
# section characters are data, and a tester that hardcodes one stops describing
# the file the moment somebody renumbers them.
PRIMARY_PREFIX="$(jq -r '.ui.phone_sections[] | select(.title == "Tools · Primary") | .prefix' "$BJ")"
[ -n "$PRIMARY_PREFIX" ] \
  && ok "Phone section 'Tools · Primary' declares prefix '$PRIMARY_PREFIX'" \
  || bad "no ui.phone_sections entry titled 'Tools · Primary' — the owner's target section does not exist under that name"

while IFS=$'\t' read -r pkg folder label; do
  case "$label" in
    "$PRIMARY_PREFIX"*) ok "$pkg classifies into '$label' ($folder), a folder of Tools · Primary" ;;
    "") bad "$pkg is claimed by no folder at all — it falls to the sink and renders under Other" ;;
    *)  bad "$pkg classifies into '$label' ($folder), which is not in Tools · Primary (prefix '$PRIMARY_PREFIX')" ;;
  esac
done < <(jq -r "$CLASSIFY"'
  . as $root
  | $pkg_in as $pkg
  | ($root | fold($pkg)) as $f
  | [ $pkg, ($f.id // ""), ($f.label // "") ] | @tsv' --arg pkg_in "$SAMSUNG_PKG" "$BJ")

echo "== T6: the Samsung camera left its old folder, and the other cameras did not move =="
# "add here" is a MOVE: a package written into two folders does not render
# twice, it renders in the earlier one, so the diff reads as done and the app
# never arrives. The general no-package-in-two-folders assertion already lives
# in test-phone-taxonomy-prefixes.sh T2 and is not repeated; this is the
# narrower fact that the OLD home specifically let go.
OLD="$(jq -r --arg p "pkg:$SAMSUNG_PKG" '
  [ .ui.phone_folders[] | select((.match_keywords // []) | index($p)) | .id ] | join(",")' "$BJ")"
[ "$OLD" = "prod_utils" ] \
  && ok "pkg:$SAMSUNG_PKG is declared by exactly one folder, prod_utils" \
  || bad "pkg:$SAMSUNG_PKG is declared by '$OLD' — it must be exactly prod_utils, or the move is a copy"

OPUS="$(jq -r --arg p "pkg:$OPUS_PKG" '
  [ .ui.phone_folders[] | select((.match_keywords // []) | index($p)) | .id ] | join(",")' "$BJ")"
[ "$OPUS" = "prod_storage" ] \
  && ok "$OPUS_PKG (Camera Opus, third-party) stayed in prod_storage" \
  || bad "$OPUS_PKG moved to '$OPUS' — it is not part of this change and belongs in prod_storage"

echo "== T7: neither camera appears on the other one's page =="
# The single assertion that would have caught the whole task being done
# backwards. The Cloud row carries constellation ids, never raw packages; the
# phone taxonomy carries third-party packages, and cld.camera is classified
# through ui.external_apps rather than a keyword of its own.
STRAY_CLOUD="$(jq -r --arg p "$SAMSUNG_PKG" '
  [ .ui.sections[] | select(.id == "cloud") | .tile_groups[].tiles[]
    | select((.target // "") | contains($p)) | .label ] | join(",")' "$BJ")"
[ -z "$STRAY_CLOUD" ] \
  && ok "the Samsung package appears on no Cloud ▸ Apps tile" \
  || bad "the Samsung stock camera is targeted by Cloud tile(s) '$STRAY_CLOUD' — that page lists the fleet's own apps"

STRAY_PHONE="$(jq -r --arg h "$HUB" '
  [ .ui.phone_folders[] | . as $f | (.match_keywords // [])[]
    | select(ascii_downcase == ("pkg:" + ($h | ascii_downcase))) | $f.id ] | join(",")' "$BJ")"
[ -z "$STRAY_PHONE" ] \
  && ok "$HUB carries no ui.phone_folders keyword — its folder comes from ui.external_apps alone" \
  || bad "ui.phone_folders '$STRAY_PHONE' restates pkg:$HUB, a package ui.external_apps already owns — two copies of one classification, free to disagree"

echo "== T8: both package identifiers are well-formed =="
# After the placeholder change, an unresolved curated entry is a VISIBLE
# install-me tile rather than a silent omission, so a mistyped identifier is a
# permanent dead tile offering an app that does not exist. Two or more
# dot-separated segments, each starting with a letter.
for pkg in "$SAMSUNG_PKG" "$HUB"; do
  printf '%s' "$pkg" | grep -qE '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$' \
    && ok "'$pkg' is a well-formed Android package id" \
    || bad "'$pkg' is not a well-formed Android package id"
done

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
