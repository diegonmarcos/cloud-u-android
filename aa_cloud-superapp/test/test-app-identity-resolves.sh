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

echo "== T5: every app the central list puts in an Inboxes section reaches the Inboxes UI =="
# T1-T4 stop at "the app resolves to a section". That is only half the trip, and
# the missing half is what let Notify > Inboxes lose the whole chat category on
# 2026-09-09 while every one of the checks above passed: `ui.phone_folders`
# placed the messengers in "@Chat" through a `match_metadata` rule, and the
# surface reading that classification could not run the metadata pass, so the
# apps resolved on paper and arrived nowhere. These three assertions are the
# rest of the route, from the central list to the tab.
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
TAXO="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneTaxonomy.kt"

# T5a — a folder with NO match rule at all can never claim an app, so a section
# built only out of such folders is a tab that can only ever be empty. The Misc
# exile and the Others sink are rule-less on purpose and carry no prefix, which
# is why the selection is by prefix and not by folder.
while IFS=$'\t' read -r fid label rules; do
  [ "$rules" -gt 0 ] && ok "folder $fid ('$label') declares $rules match rule(s)" \
                     || bad "folder $fid ('$label') sits in a section but declares no match_keywords and no match_metadata, so nothing can ever reach it"
done < <(jq -r '
  .ui.phone_folders[]
  | select((.label[0:1] | test("^[A-Za-z0-9]$")) | not)
  | [ .id, .label, (((.match_keywords // []) | length) + ((.match_metadata // []) | length)) ]
  | @tsv' "$BJ")

# T5b — THE CHECK THAT WOULD HAVE CAUGHT ALL THREE REPORTS. A folder that
# classifies by `match_metadata` is reachable only when the lookup is handed a
# Context: PhoneAppClassifier skips the metadata pass for AppMetadata.NONE, and
# PhoneTaxonomy passes NONE exactly when its `ctx` argument is null. So a
# context-less call site silently downgrades the central classification to its
# keyword half, and every app that only a metadata rule would have placed
# becomes sectionless — which taxonomyKeeps reads as "show on no tab".
METADATA_FOLDERS="$(jq -r '[ .ui.phone_folders[] | select(((.match_metadata // []) | length) > 0) ] | length' "$BJ")"
CTXLESS="$(grep -nE '(sectionPrefixOf|folderIdOf)\([^)]*\)' "$FRAG" \
           | grep -vE '(sectionPrefixOf|folderIdOf)\([^)]*,[^)]*,[^)]*\)' || true)"
[ -z "$CTXLESS" ] \
  && ok "every taxonomy lookup in AggregatorStackFragment passes a Context ($METADATA_FOLDERS folders classify by match_metadata and need it)" \
  || { while IFS= read -r line; do
         bad "context-less taxonomy lookup — match_metadata folders cannot be reached from it: ${line}"
       # A here-string, not a pipe: a piped `while` runs in a subshell, so every
       # bad() it called would increment a FAIL the exit status never sees — a
       # checker that prints failures and still reports success.
       done <<< "$CTXLESS"; }

# T5c — and the memo must not freeze a context-less answer, or the first such
# caller would make every later, correct caller wrong for the life of the
# process. Same defect, one indirection further away.
grep -q 'if (ctx != null || keywordId != sinkId) cache\[pkg\] = id' "$TAXO" \
  && ok "PhoneTaxonomy memoises only answers the metadata pass could not change" \
  || bad "PhoneTaxonomy caches provisional (context-less, unclaimed) answers, freezing apps out of their section"

# T5d — the section an app resolves to must be selectable on the page that is
# supposed to show it. An option id is a SET of prefixes (see
# _doc_filters_my-rss), so membership, not equality.
while IFS=$'\t' read -r prefix covered; do
  [ "$covered" = "true" ] && ok "section '$prefix' is selectable on Notify > Inboxes" \
                          || bad "section '$prefix' is declared in ui.phone_sections but no filters_rss-inboxes option offers it, so its apps reach no tab"
done < <(jq -r '
  ( [ .ui.sections[] | .["filters_rss-inboxes"] // empty ] | first // [] ) as $f
  | [ $f[] | select(.id == "tools" or .id == "services") | .options[].id ] as $opts
  | .ui.phone_sections[]
  | .prefix as $p
  | [ $p, ([ $opts[] | select(contains($p)) ] | length > 0) ]
  | @tsv' "$BJ" | sort -u)

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
