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
#                       notifications and no error. Each entry also names the
#                       `folder` it classifies into, and PhoneFolders derives
#                       the `pkg:` keyword from it — so identity and taxonomy
#                       are ONE record for our own apps and cannot drift (T8).
#
#   ui.phone_folders    classification of ANY installed THIRD-PARTY package,
#                       keyed by package (`pkg:` exact / `pkg^` prefix). A
#                       package that matches no folder lands in the sink, and a
#                       sink folder carries no section prefix, which is the same
#                       thing as invisible on every filtered surface (T2/T3/T4).
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
# The folder list the app actually classifies with: ui.phone_folders, plus the
# `pkg:` keyword every ui.external_apps entry contributes to the folder it
# names. PhoneFolders.loadFromBuildConfig does exactly this at load time, and a
# checker that read only the declared half would report our own apps as
# sectionless — the very failure it exists to catch, inverted.
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
# A section is joined by the FIRST CHARACTER of the folder label (see
# ui.phone_sections prefixes and PhoneTaxonomy.sectionPrefixOf). An
# alphanumeric first character means the folder belongs to no section at all,
# which is what Misc and the Others sink are.
def sect($pkg):
  fold($pkg) as $folder
  | if $folder == null then "" else
      ($folder.label[0:1]) as $c
      | if ($c | test("^[A-Za-z0-9]$")) then "" else $c end
    end;
# The folder id is the KIND. Projects > Inboxes derives the kind of a card from
# it rather than declaring one, so the tester needs the answer the app gets.
def fid($pkg):
  fold($pkg) as $folder
  | if $folder == null then "" else $folder.id end;
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
                   || bad "$id.$field $pkg classifies to no section (set ui.external_apps[$id].folder — never add a pkg: keyword, see T8b)"
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

echo "== T3: every curated package list classifies into a SECTION =="
# The Phone ▸ Apps Quickmarks rows. These are third-party packages the owner
# curated by hand, and they are the list that drifted: an entry here that the
# taxonomy cannot place renders as a tile but never as a notification.
#
# Discovered BY SHAPE — every `packages` array anywhere in the document, named
# by its json path — not by walking to sections[phone].phone_app_groups. A
# curated list is curated somewhere, and the next surface to grow one will not
# announce itself to this file; the whole point of the check is to see a list
# the author of the check never heard of.
#
# BOTH ENTRY SHAPES, and the second one is not optional. An element is either a
# bare package string or {"pkg","label"} — 149c5b9 added the object form so a
# not-installed tile could carry a readable name, and this filter said
# `select(type == "string")`, so from that day every entry written in the new
# shape contributed ZERO assertions here while the check went on reporting a
# tally of passes. By 2026-09-10 that was three of the five Quickmark groups.
# A guard that silently stops covering the thing it was written for is worse
# than no guard, because the tally reads like coverage.
while IFS=$'\t' read -r where pkg prefix; do
  [ -n "$prefix" ] && ok "$where / $pkg → section '$prefix'" \
                   || bad "$where / $pkg classifies to no section (add pkg:$pkg to ui.phone_folders)"
done < <(jq -r "$CLASSIFY"'
  . as $root
  | paths(type == "array") as $path
  | select(($path | last) == "packages")
  | ($path | map(tostring) | join(".")) as $where
  | (getpath($path))[]
  | (if type == "string" then . elif type == "object" then .pkg else null end) as $pkg
  | select($pkg != null)
  | [ $where, $pkg, ($root | sect($pkg)) ] | @tsv' "$BJ")

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
# apps resolved on paper and arrived nowhere. These assertions are the rest of
# the route, from the central list to the tab.
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
TAXO="$APP/app/src/main/java/com/diegonmarcos/superapp/apps/PhoneTaxonomy.kt"

# T5a — a folder with NO match rule at all can never claim an app, so a section
# built only out of such folders is a tab that can only ever be empty. The Misc
# exile and the Others sink are rule-less on purpose and carry no prefix, which
# is why the selection is by prefix and not by folder.
while IFS=$'\t' read -r fid label rules; do
  [ "$rules" -gt 0 ] && ok "folder $fid ('$label') declares $rules match rule(s)" \
                     || bad "folder $fid ('$label') sits in a section but declares no match_keywords and no match_metadata, so nothing can ever reach it"
done < <(jq -r "$CLASSIFY"'
  folders_merged[]
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

echo "== T6: every Projects > Inboxes card derives ONE kind from the central list =="
# The cards on stack_msgs do not declare which notifications they carry; they
# derive it, by asking ui.phone_folders which folder their declared app
# classifies into (AggregatorStackFragment.inboxFolderId). Two things have to
# hold for that to be well defined, and both are static facts about build.json.
#
#   T6a  An app's packages must AGREE. A hub and its resigned stock alt are one
#        app to the owner; if the classification splits them across folders the
#        card has no single kind and falls back to its own roster, silently
#        showing less than the page promises.
#   T6b  A folder claimed by two cards is claimed by NEITHER, or the page draws
#        the same notification twice. That is by design, not a failure — this
#        prints which folders are contested so the design stays visible.
while IFS=$'\t' read -r title id kinds count; do
  [ "$count" -le 1 ] && ok "card '$title' (extapp:$id) derives kind '${kinds}'" \
                     || bad "card '$title' (extapp:$id) packages classify into $count different folders ($kinds), so it has no single kind"
done < <(jq -r "$CLASSIFY"'
  . as $root
  | ( .ui.sections[] | .stack_msgs // empty )[]
  | ( [ .url, ((.links // [])[] | .url) ] | map(select(type == "string" and startswith("extapp:")))
      | first // "" ) as $target
  | select($target != "")
  | .title as $title
  | ($target | ltrimstr("extapp:") | split("/")[0] | split("#")[0]) as $id
  | ( [ $root.ui.external_apps[] | select(.id == $id) ] | first ) as $app
  | select($app != null)
  | ( [ $app.hub_package, $app.alt_package, $app.install_package,
        ((($app.forks // {}) | to_entries[]).value) ]
      | map(select(. != null and . != "")) ) as $pkgs
  # `.` is a closure evaluated where it is USED, so a bare fid(.) would be read
  # off $root — the same trap documented in T2. Bind the package first.
  | ( [ $pkgs[] | . as $pk | ($root | fid($pk)) ] | unique ) as $kinds
  | [ $title, $id, (($kinds | join(",")) | if . == "" then "<sink>" else . end),
      ($kinds | length) ] | @tsv' "$BJ")

while IFS=$'\t' read -r kind cards; do
  ok "kind '$kind' is claimed by $cards cards, so none of them expands to it (no double-draw)"
done < <(jq -r "$CLASSIFY"'
  . as $root
  | [ ( .ui.sections[] | .stack_msgs // empty )[]
      | ( [ .url, ((.links // [])[] | .url) ] | map(select(type == "string" and startswith("extapp:")))
          | first // "" ) as $target
      | select($target != "")
      | ($target | ltrimstr("extapp:") | split("/")[0] | split("#")[0]) as $id
      | ( [ $root.ui.external_apps[] | select(.id == $id) ] | first ) as $app
      | select($app != null)
      | ( [ [ $app.hub_package, $app.alt_package, $app.install_package,
              ((($app.forks // {}) | to_entries[]).value) ]
            | .[] | select(. != null and . != "") | . as $pk | ($root | fid($pk)) ] | unique ) as $kinds
      | select(($kinds | length) == 1) | $kinds[0] ]
  | group_by(.) | map(select(length > 1)) | .[]
  | [ .[0], (. | length) ] | @tsv' "$BJ")

echo "== T7: the BY KIND summary derives its kinds, and never prints an unasked zero =="
# The box between each summary and its list segments notifications by kind. Two
# properties are worth pinning, because losing either quietly turns the box into
# the thing it was added to prevent.
#
#   T7a  The kinds come from ui.phone_folders through PhoneTaxonomy — not from a
#        list of kind names in Kotlin. A fourth parallel taxonomy is exactly the
#        defect the rest of this file exists to catch.
#   T7b  A kind that was never queried says so. The count is reachable ONLY
#        under the "notification access granted" branch; drop that guard and
#        every kind reports a confident 0 that the owner cannot tell apart from
#        a real empty inbox.
grep -q 'PhoneTaxonomy.folders.map { it.id }' "$FRAG" \
  && ok "BY KIND enumerates ui.phone_folders through PhoneTaxonomy, not a Kotlin list" \
  || bad "BY KIND no longer derives its kinds from PhoneTaxonomy.folders — check for a second kind list"

grep -q 'if (granted) (counted\[id\] ?: 0).toString() else NO_SOURCE' "$FRAG" \
  && ok "BY KIND prints a count only when the listener was queried, '$(grep -o 'private val NO_SOURCE = "[^"]*"' "$FRAG" | sed 's/.*"\(.*\)"/\1/')' otherwise" \
  || bad "BY KIND can print a number for a kind it never queried — a zero indistinguishable from a real empty inbox"

grep -q 'NO_SOURCE · classification unavailable' "$FRAG" \
  && ok "BY KIND reports an unloaded ui.phone_folders as no source rather than as no kinds" \
  || bad "BY KIND treats a missing central classification as an empty one"

echo "== T8: the two identity structures cannot disagree, because there is only one copy =="
# THE ASSERTION THIS FILE IS FOR. Every check above answers "does this app
# resolve?"; these three answer "can the answer ever be written down twice?",
# which is the question the Sterna report actually asked. An app used to be
# declared in ui.external_apps (identity) AND again in ui.phone_folders (a
# `pkg:` keyword that classified it), with nothing tying the copies together —
# so editing one and forgetting the other produced an app that was installable
# and sectionless at the same time, and nothing anywhere complained. The entry
# now names its folder and the keyword is derived from it, so the copy is gone;
# T8b is what keeps it gone.

# T8a — the reference has to resolve. An entry with no `folder`, or one naming
# a folder that no longer exists, classifies through nothing and lands in the
# sink: installable, sectionless, invisible on every filtered surface.
while IFS=$'\t' read -r id folder known; do
  [ "$known" = "true" ] && ok "external app '$id' classifies into ui.phone_folders '$folder'" \
                        || bad "external app '$id' declares folder '$folder', which no ui.phone_folders entry defines — it will fall to the sink and show on no filtered surface"
done < <(jq -r '
  [ .ui.phone_folders[].id ] as $ids
  | .ui.external_apps[]
  # Bind before the pipe: inside index() the input is $ids, so a bare .folder
  # would be read off the array — the closure trap documented in T2.
  | .id as $id | (.folder // "") as $folder
  # A placeholder, not "": `read` with IFS=tab collapses consecutive tabs, so
  # an empty column would shift `known` into $folder and the message would
  # report the wrong thing in the one case it is written for.
  | [ $id, (if $folder == "" then "<none>" else $folder end),
      (($ids | index($folder)) != null) ] | @tsv' "$BJ")

# T8b — and no second copy may come back. A `pkg:` keyword for a package
# ui.external_apps already owns is that copy, and a copy is free to name a
# different folder than the entry does, which is a disagreement no surface can
# report. Third-party packages are unaffected: they have no identity entry.
while IFS=$'\t' read -r folder pkg owner; do
  bad "ui.phone_folders '$folder' restates pkg:$pkg, which ui.external_apps '$owner' already owns — delete the keyword, the entry's \`folder\` is what classifies it"
done < <(jq -r '
  [ .ui.external_apps[] | . as $a
    | ( .hub_package, .alt_package, .install_package, ((.forks // {}) | to_entries[] | .value) )
    | select(. != null and . != "") | { pkg: (. | ascii_downcase), owner: $a.id } ] as $owned
  | .ui.phone_folders[] | . as $folder
  | (.match_keywords // [])[] | ascii_downcase | select(startswith("pkg:")) | .[4:] as $pkg
  | $owned[] | select(.pkg == $pkg)
  | [ $folder.id, $pkg, .owner ] | @tsv' "$BJ")
DUPES="$(jq -r '
  [ .ui.external_apps[]
    | ( .hub_package, .alt_package, .install_package, ((.forks // {}) | to_entries[] | .value) )
    | select(. != null and . != "") | ascii_downcase ] as $owned
  | [ .ui.phone_folders[] | (.match_keywords // [])[] | ascii_downcase
      | select(startswith("pkg:")) | .[4:] | select(. as $p | $owned | index($p)) ] | length' "$BJ")"
[ "$DUPES" = "0" ] && ok "no ui.phone_folders keyword restates a package ui.external_apps owns ($(jq -r '[.ui.external_apps[] | (.hub_package, .alt_package, .install_package, ((.forks // {}) | to_entries[] | .value)) | select(. != null and . != "")] | length' "$BJ") packages, one copy each)"

# T8c — the derivation has to LAND. A folder earlier in `order` can claim one
# of our packages with a broader rule, and then the `folder` an entry declares
# is simply not where the app ends up; the card would derive a kind the grid
# disagrees with. Cheap to check, impossible to see by reading.
while IFS=$'\t' read -r id declared actual pkg; do
  [ "$declared" = "$actual" ] && ok "external app '$id' package $pkg lands in its declared folder '$declared'" \
                              || bad "external app '$id' declares folder '$declared' but $pkg classifies into '${actual:-<sink>}' — an earlier folder claims it first"
done < <(jq -r "$CLASSIFY"'
  . as $root
  | .ui.external_apps[] | . as $app
  | select((.folder // "") != "")
  # hub_package and install_package are the same string on every entry today;
  # uniquing keeps that from printing each app twice.
  | ( [ .hub_package, .alt_package, .install_package, ((.forks // {}) | to_entries[] | .value) ]
      | map(select(. != null and . != "")) | unique )[] | . as $pkg
  | [ $app.id, $app.folder, ($root | fid($pkg)), $pkg ] | @tsv' "$BJ")

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
