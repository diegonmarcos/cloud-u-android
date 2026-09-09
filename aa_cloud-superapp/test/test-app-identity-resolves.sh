#!/usr/bin/env bash
# Tester: every app any SECTION names resolves in the CENTRAL list that section
# reads, so no surface can describe an app the rest of the launcher has never
# heard of.
#
# The failure this exists for, 2026-09-09: the mail app was re-cloned from
# Sterna and `app.sterna` was added to sections[phone].phone_app_groups
# "Inboxes" — but NOT to ui.phone_folders, which is the list that decides an
# app's taxonomy section. So Sterna Mail fell through the keyword pass to the
# `others` sink, came back with an empty section prefix, and
# AggregatorStackFragment.taxonomyKeeps pruned it from every Notify tab except
# All. Nothing logged, nothing crashed, the app was simply not there. One list
# was edited, three were not, and only a device could tell.
#
# Two central lists, and the two invariants that bind everything to them:
#
#   ui.external_apps    identity of OUR constellation apps, keyed by `id`.
#                       Every `extapp:<id>` target in the file must resolve here
#                       (T1). AggregatorStackFragment.inboxApp has exactly this
#                       one route, so an unresolved id is a card with no
#                       notifications and no error.
#
#   ui.phone_folders    classification of ANY installed package, keyed by
#                       package (`pkg:` exact / `pkg^` prefix). A package that
#                       matches no folder lands in the sink, and a sink folder
#                       carries no section prefix, which is the same thing as
#                       invisible on every filtered surface (T2/T3/T4).
#
# Both are keyed on things a rename does not move — a slug id and a package name
# — which is exactly why the mail rename broke a MEMBERSHIP (a package missing
# from a list) and not a LOOKUP. A list keyed on the human-readable label would
# have broken the lookup too; the Inboxes cards used to be one, and were fixed
# the same morning.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }

# The keyword pass of PhoneAppClassifier.classify, in jq. Folders in declared
# order, first matching folder wins; the label-matching forms (`lbl:`, `lbl~`)
# and the plain-substring form are evaluated against the package alone, because
# a static check has no installed app to read a user-facing label from — so this
# is a LOWER bound on what the device would match, and a package this says is
# classified really is.
CLASSIFY='
def sect($pkg):
  ($pkg | ascii_downcase) as $p
  | ( [ .ui.phone_folders[] | select((.match_keywords // []) | length > 0) ]
      | sort_by(.order) ) as $folders
  | ( first( $folders[]
      | select( any( .match_keywords[];
            ascii_downcase as $k
            | if   ($k | startswith("pkg:")) then $p == $k[4:]
              elif ($k | startswith("pkg^")) then ($p | startswith($k[4:]))
              elif ($k | startswith("lbl:")) or ($k | startswith("lbl~")) then false
              else (($k | length) >= 4 and ($p | contains($k)))
              end ) ) ) // null ) as $folder
  # A section is joined by the FIRST CHARACTER of the folder label (see
  # ui.phone_sections prefixes and PhoneTaxonomy.sectionPrefixOf). An
  # alphanumeric first character means the folder belongs to no section at all,
  # which is what Misc and the Others sink are.
  | if $folder == null then "" else
      ($folder.label[0:1]) as $c
      | if ($c | test("^[A-Za-z0-9]$")) then "" else $c end
    end;
'

echo "== T1: every extapp:<id> target resolves in ui.external_apps =="
UNRESOLVED="$(jq -r '
  [ .ui.external_apps[].id ] as $ids
  | [ .. | strings | select(startswith("extapp:"))
      | ltrimstr("extapp:") | split("/")[0] | split("#")[0] ]
  | unique | map(select(. as $i | $ids | index($i) | not)) | .[]' "$BJ")"
[ -z "$UNRESOLVED" ] \
  && ok "all extapp: targets resolve ($(jq -r '[.. | strings | select(startswith("extapp:"))] | unique | length' "$BJ") distinct)" \
  || { for i in $UNRESOLVED; do bad "extapp:$i has no ui.external_apps entry"; done; }

echo "== T2: every ui.external_apps package classifies into a SECTION =="
while IFS=$'\t' read -r id field pkg prefix; do
  [ -n "$prefix" ] && ok "$id.$field $pkg → section '$prefix'" \
                   || bad "$id.$field $pkg classifies to no section (add pkg:$pkg to ui.phone_folders)"
done < <(jq -r "$CLASSIFY"'
  . as $root
  | .ui.external_apps[]
  | .id as $id
  | ( ({hub_package, alt_package, install_package} | to_entries[]),
      ((.forks // {}) | to_entries[] | {key: ("fork:" + .key), value}) )
  | select(.value != null and .value != "")
  # `.value` is bound to $pkg BEFORE the pipe: a jq function argument is a
  # closure evaluated where it is USED, so inside `$root | sect(...)` a bare
  # `.value` would be read off the root document and come back null.
  | .value as $pkg
  | [ $id, .key, $pkg, ($root | sect($pkg)) ] | @tsv' "$BJ")

echo "== T3: every phone_app_groups package classifies into a SECTION =="
# The Phone ▸ Apps Quickmarks rows. These are third-party packages the owner
# curated by hand, and they are the list that drifted: an entry here that the
# taxonomy cannot place renders as a tile but never as a notification.
while IFS=$'\t' read -r group pkg prefix; do
  [ -n "$prefix" ] && ok "$group / $pkg → section '$prefix'" \
                   || bad "$group / $pkg classifies to no section (add pkg:$pkg to ui.phone_folders)"
done < <(jq -r "$CLASSIFY"'
  . as $root
  | ( .ui.sections[] | select(.id == "phone") | .phone_app_groups[] )
  | .title as $g
  | ( (.packages // [])[], ((.folders // [])[] | (.packages // [])[]) ) as $pkg
  | [ $g, $pkg, ($root | sect($pkg)) ] | @tsv' "$BJ")

echo "== T4: every app:<package> target classifies into a SECTION =="
while IFS=$'\t' read -r pkg prefix; do
  [ -n "$prefix" ] && ok "app:$pkg → section '$prefix'" \
                   || bad "app:$pkg classifies to no section (add pkg:$pkg to ui.phone_folders)"
done < <(jq -r "$CLASSIFY"'
  . as $root
  | ( [ .. | strings
        | select(startswith("app:") and (startswith("app://") | not))
        | ltrimstr("app:") ] | unique | .[] ) as $pkg
  | [ $pkg, ($root | sect($pkg)) ] | @tsv' "$BJ")

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
