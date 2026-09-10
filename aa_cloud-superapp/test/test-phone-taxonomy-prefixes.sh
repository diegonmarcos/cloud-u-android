#!/usr/bin/env bash
# Tester: the Phone > Apps folder taxonomy is INTERNALLY CONSISTENT.
#
# THE FAILURE THIS EXISTS TO KEEP FIXED. ui.phone_sections buckets a folder
# under a section by its label's FIRST CHARACTER: "-" Services · Buro, "+"
# Services · Others, "_" Tools · System, "@" Tools · Inboxes & AI, "." Tools ·
# Data Apps, "=" Tools · Primary, ">" Tools · Media, "*" Tools · Health. A
# folder whose label starts with none of those belongs to NO section and
# renders last, in the prefix-less sink area at the bottom of the page.
#
# So a folder label is not a label. It is the label AND the section membership
# in one string, and the two are indistinguishable to anyone renaming it. The
# owner asked to rename "_Dev & Terminal" to "Dev" on 2026-09-10; doing that
# literally would have dropped the "_" and evicted the folder from Tools ·
# System into the unsectioned sink -- a rename in the diff, a deletion on the
# device, and nothing anywhere would have said so. It shipped as "_Dev".
#
# T1 is the assertion that carries the weight, and it deliberately covers
# EVERY folder rather than the ones that task touched: the trap is not in any
# one folder, it is in the schema, so the guard has to be too.
#
# WHY EVERY jq CALL GOES THROUGH q(). The first draft of this file asked
# `$prefixes | index(.label[0:1])`, which is a jq scoping error -- inside
# `A | index(B)`, B is evaluated with `.` bound to A, so `.label` was read off
# the prefix ARRAY and jq exited 5 with "Cannot index array with string". The
# assertion then tested `[ -z "$OUTPUT" ]`, output was empty because jq had
# DIED, and the tester printed PASS. That is a verdict drawn from a tool's
# failure -- the same shape as the four testers here that were green only
# because ripgrep was missing. q() reads jq's own exit status, outside any
# pipe, and makes a broken filter fatal instead of green.
#
# Usage: ./test-phone-taxonomy-prefixes.sh    (static only, no network, no device)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> ~/git/cloud-u-android
BUILD_JSON="$ROOT/aa_cloud-superapp/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# FAIL CLOSED on tooling and inputs, before any assertion runs.
for tool in jq sort uniq awk; do
    command -v "$tool" >/dev/null || {
        echo "FATAL: $tool is not on PATH -- refusing to report a verdict"; exit 2; }
done
[ -f "$BUILD_JSON" ] || {
    echo "FATAL: $BUILD_JSON not found -- refusing to report a verdict"; exit 2; }

# q <filter> -- run jq, and DIE if jq itself fails. Assignment from $(...)
# preserves jq's exit status; a pipe would hand back the last stage's status
# instead, which is how a pipeline's verdict gets read through a `tail`.
q() {
    local out
    if ! out="$(jq -r "$1" "$BUILD_JSON")"; then
        echo "FATAL: jq failed (exit $?) on filter: $1"; exit 2
    fi
    printf '%s\n' "$out"
}
q '.ui.phone_folders | length' >/dev/null

echo "== T1: EVERY folder label starts with a declared section prefix =="
# The allowance for a prefix-less label is DERIVED, not a list of ids: the two
# sinks are the folders with no rules at all (_doc_phone_folders: "Two sinks,
# neither with any rules"). So this catches both directions -- a real folder
# that lost its prefix, and a sink that grew one -- and it survives a rename.
UNSECTIONED="$(q '
  (.ui.phone_sections | map(.prefix)) as $prefixes
  | .ui.phone_folders[]
  | select(((.match_keywords // []) | length) > 0 or ((.match_metadata // []) | length) > 0)
  | (.label[0:1]) as $char
  | select(($prefixes | index($char)) == null)
  | "\(.id) label=\(.label)"' | grep -v '^$' || true)"
if [ -z "$UNSECTIONED" ]; then
    ok "every rule-carrying folder label starts with a phone_sections prefix"
else
    bad "folder(s) belong to NO section and will render in the prefix-less sink:"
    echo "$UNSECTIONED" | sed 's/^/        /'
fi

SINKS_WITH_PREFIX="$(q '
  (.ui.phone_sections | map(.prefix)) as $prefixes
  | .ui.phone_folders[]
  | select(((.match_keywords // []) | length) == 0 and ((.match_metadata // []) | length) == 0)
  | (.label[0:1]) as $char
  | select(($prefixes | index($char)) != null)
  | "\(.id) label=\(.label)"' | grep -v '^$' || true)"
if [ -z "$SINKS_WITH_PREFIX" ]; then
    ok "the ruleless sinks carry no section prefix"
else
    bad "a ruleless sink claims a section prefix (it would render as a real folder):"
    echo "$SINKS_WITH_PREFIX" | sed 's/^/        /'
fi

echo "== T2: no package is claimed by two folders =="
# PhoneAppClassifier walks folders in `order` and the first match wins, so a
# package written into two folders does not render twice -- it renders in
# whichever sorts earlier, which makes the second entry a silent lie about
# where the app lives. Covers pkg^ prefix families too: two folders declaring
# the same startsWith rule is the same defect.
ALL_KEYWORDS="$(q '
  .ui.phone_folders[]
  | (.match_keywords // [])[]
  | select(startswith("pkg:") or startswith("pkg^"))')"
DUPES="$(printf '%s\n' "$ALL_KEYWORDS" | grep -v '^$' | sort | uniq -d || true)"
if [ -z "$DUPES" ]; then
    ok "every package keyword is claimed by exactly one folder"
else
    bad "package keyword(s) claimed by more than one folder:"
    printf '%s\n' "$DUPES" | while read -r kw; do
        owners="$(q "[.ui.phone_folders[] | select((.match_keywords // []) | index(\"$kw\")) | .id] | join(\" \")")"
        echo "        $kw -> $owners"
    done
fi

echo "== T3: the Terminals folder exists, in the right section =="
TERM_LABEL="$(q '[.ui.phone_folders[] | select(.id=="prod_terminals") | .label] | join("")')"
if [ "$TERM_LABEL" = "@Terminals" ]; then
    ok "prod_terminals is labelled '@Terminals' (Tools · Inboxes & AI)"
else
    bad "prod_terminals label is '$TERM_LABEL', expected '@Terminals'"
fi

echo "== T4: every terminal is in Terminals and none is left in _Dev =="
# The two pkg^ families matter as much as the exact ids. 'pkg^com.termux.'
# left behind in sys_dev (order 310, walked BEFORE prod_terminals at 460)
# would have kept every undeclared termux sibling in _Dev while the seven
# named ones moved -- correct-looking in the diff, wrong on the device.
for kw in "pkg:cld.termux" "pkg:cld.termux.nix" "pkg:cld.termux.nix.boot" \
          "pkg:com.termux" "pkg:com.termux.api" "pkg:com.termux.nix" \
          "pkg:com.termux.nix.boot" "pkg^cld.termux." "pkg^com.termux." \
          "pkg:com.foxdebug.acode"; do
    owner="$(q "[.ui.phone_folders[] | select((.match_keywords // []) | index(\"$kw\")) | .id] | join(\",\")")"
    if [ "$owner" = "prod_terminals" ]; then
        ok "$kw is in prod_terminals"
    else
        bad "$kw is in '${owner:-NO FOLDER}', expected prod_terminals"
    fi
done

echo "== T5: _Dev kept its underscore and lost its terminals =="
DEV_LABEL="$(q '[.ui.phone_folders[] | select(.id=="sys_dev") | .label] | join("")')"
if [ "$DEV_LABEL" = "_Dev" ]; then
    ok "sys_dev is labelled '_Dev' -- renamed, prefix intact"
else
    bad "sys_dev label is '$DEV_LABEL', expected '_Dev'"
fi
LEFTOVER="$(q '
  .ui.phone_folders[] | select(.id=="sys_dev") | (.match_keywords // [])[]
  | select(test("termux") or . == "pkg:com.foxdebug.acode")' | grep -v '^$' || true)"
if [ -z "$LEFTOVER" ]; then
    ok "sys_dev holds no terminal keyword"
else
    bad "sys_dev still claims terminal keyword(s) -- they would win on order 310:"
    echo "$LEFTOVER" | sed 's/^/        /'
fi

echo "== T6: the moved data apps landed where the owner put them =="
for pair in "pkg:com.github.android prod_storage" \
            "pkg:com.viscouspot.gitsync prod_storage" \
            "pkg:com.rhmsoft.edit prod_docs" \
            "pkg:jp.sblo.pandora.jota.plus prod_docs"; do
    kw="${pair% *}"; want="${pair#* }"
    owner="$(q "[.ui.phone_folders[] | select((.match_keywords // []) | index(\"$kw\")) | .id] | join(\",\")")"
    if [ "$owner" = "$want" ]; then
        ok "$kw is in $want"
    else
        bad "$kw is in '${owner:-NO FOLDER}', expected $want"
    fi
done

echo "== T7: folder order is unique and blocked by section =="
ORDERS="$(q '.ui.phone_folders[] | .order')"
ORDER_DUPES="$(printf '%s\n' "$ORDERS" | grep -v '^$' | sort | uniq -d || true)"
if [ -z "$ORDER_DUPES" ]; then
    ok "every folder order is unique (order drives match precedence)"
else
    bad "duplicate folder order(s) -- match precedence is then undefined: $ORDER_DUPES"
fi
# order is "one hundred-block per section in phone_sections order", so a
# folder's block index must equal its prefix's position in phone_sections.
MISBLOCKED="$(q '
  (.ui.phone_sections | map(.prefix)) as $prefixes
  | .ui.phone_folders[]
  | (.label[0:1]) as $char
  | ($prefixes | index($char)) as $at
  | select($at != null)
  | select(($at + 1) != ((.order | tonumber) / 100 | floor))
  | "\(.id) order=\(.order) label=\(.label)"' | grep -v '^$' || true)"
if [ -z "$MISBLOCKED" ]; then
    ok "every folder's order block matches its section's position"
else
    bad "folder(s) whose order block disagrees with their section prefix:"
    echo "$MISBLOCKED" | sed 's/^/        /'
fi

echo "== T8: every declared section prefix is UNIQUE, one character, and not alphanumeric =="
# THE TRAP THAT COMES WITH ADDING A SECTION, and the reason T1 alone is not
# enough. T1 only asks whether a folder's first character is SOME declared
# prefix. It cannot notice two sections declaring the SAME character, and that
# failure is invisible in the diff and silent on the device: renderAllApps
# buckets with `sections.firstOrNull { folder.label.startsWith(it.prefix) }`,
# so the second section to declare a duplicate simply never receives a folder
# and is skipped as empty, while its folders appear under the first section's
# subhead. The owner sees apps in a section he never put them in and no error
# anywhere. Minting "#" for Projects on 2026-09-10 was exactly this choice, and
# proving the character was free was a manual enumeration; this is that
# enumeration made permanent.
DUP_PREFIX="$(q '.ui.phone_sections | group_by(.prefix) | map(select(length > 1))
                 | .[] | "\(.[0].prefix) claimed by: \(map(.title) | join(", "))"' | grep -v '^$' || true)"
if [ -z "$DUP_PREFIX" ]; then
    ok "all $(q '.ui.phone_sections | length') section prefixes are distinct"
else
    bad "section prefix(es) claimed by more than one section — the later section can never receive a folder:"
    echo "$DUP_PREFIX" | sed 's/^/        /'
fi

# One character, because the bucket is read as `label[0:1]` here, in
# test-app-identity-resolves.sh and in PhoneTaxonomy.prefixOfFolder. And NOT
# alphanumeric, because prefixOfFolder returns "" for a letter or digit --
# a section keyed on "P" would collect folders that every other surface
# reports as belonging to no section at all.
BAD_PREFIX="$(q '.ui.phone_sections[]
                 | select((.prefix | length) != 1 or (.prefix | test("^[A-Za-z0-9]$")))
                 | "\(.title) prefix=\(.prefix | @json)"' | grep -v '^$' || true)"
if [ -z "$BAD_PREFIX" ]; then
    ok "every section prefix is exactly one non-alphanumeric character"
else
    bad "section prefix(es) PhoneTaxonomy.prefixOfFolder would report as sectionless:"
    echo "$BAD_PREFIX" | sed 's/^/        /'
fi

echo "== T9: Projects sits immediately after Tools · Primary =="
# The owner asked for it "after Tools Primary and Before Configs" on
# 2026-09-10. Position is render order (ui._doc_phone_sections: "Order =
# render order"), so it is an assertion about the ARRAY, and it is worth
# pinning because the hundred-blocks in T7 are derived from this index -- a
# section reordered here silently misblocks every folder below it.
AFTER="$(q '.ui.phone_sections | to_entries
            | (map(select(.value.title == "Tools · Primary")) | first | .key) as $p
            | .[$p + 1].value.title')"
if [ "$AFTER" = "Projects" ]; then
    ok "Projects is the section immediately after Tools · Primary"
else
    bad "the section after Tools · Primary is '$AFTER', expected 'Projects'"
fi

echo "== T10: the Projects intake is a MOVE — present in the new folder, gone from the old =="
# Both halves, deliberately. Asserting only arrival would pass while the old
# keyword still sat in a folder with a LOWER order, which is the folder that
# would actually win -- correct-looking in the diff, wrong on the device, and
# exactly the shape of the leftover 'pkg^com.termux.' T4 exists for. The old
# folder is named so the assertion says where it must NOT be, not merely that
# one owner exists.
for triple in "pkg:co.mangotechnologies.clickup proj_projects -" \
              "pkg:com.fatsecret.android proj_projects hlth_outdoor" \
              "pkg:com.google.android.apps.fitness proj_projects hlth_outdoor" \
              "pkg:com.nomadmania.presentation proj_projects svc_travel" \
              "pkg:mobi.eup.easygerman proj_projects hlth_learning" \
              "pkg:es.bancosantander.apps proj_money svc_money" \
              "pkg:com.revolut.revolut proj_money svc_money" \
              "pkg:es.gob.interior.policia.midni prod_utils svc_gov"; do
    set -- $triple; kw="$1"; want="$2"; gone="$3"
    owners="$(q "[.ui.phone_folders[] | select((.match_keywords // []) | index(\"$kw\")) | .id] | join(\",\")")"
    if [ "$owners" = "$want" ]; then
        ok "$kw is in $want, and in nothing else"
    else
        bad "$kw is in '${owners:-NO FOLDER}', expected exactly '$want'${gone:+ (it must no longer be in $gone)}"
    fi
done

# svc_money moved WHOLE and must not have been left behind as a husk: an
# empty -Money in Buro would be hidden by renderAllApps (empty folders are
# skipped) and so would read as correct from the diff alone.
STALE="$(q '[.ui.phone_folders[] | select(.id == "svc_money" or .label == "-Money") | .id] | join(",")')"
if [ -z "$STALE" ]; then
    ok "no '-Money' folder is left behind in Services · Buro"
else
    bad "svc_money/-Money still exists ($STALE) — the folder was copied into Projects, not moved"
fi

echo "== T11: every package keyword in the Projects section is a well-formed package id =="
# w258 made an unresolved curated entry render as a visible "Not installed"
# tile instead of hiding, so a typo is now permanent furniture on the owner's
# screen rather than a silent absence. co.mangotechnologies.clickup is the
# live example: the guessable com.clickup.android resolves to nothing at all.
# Two or more dot-separated segments, each starting with a letter -- the
# Android manifest rule, minus the keyword's own "pkg:"/"pkg^" verb.
MALFORMED="$(q '
  (.ui.phone_sections | map(select(.title == "Projects") | .prefix)) as $p
  | .ui.phone_folders[]
  | select(.label[0:1] as $c | $p | index($c))
  | . as $folder
  | (.match_keywords // [])[]
  | select(startswith("pkg:") or startswith("pkg^"))
  | .[4:] | select(test("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+\\.?$") | not)
  | "\($folder.id) \(.)"' | grep -v '^$' || true)"
if [ -z "$MALFORMED" ]; then
    ok "every Projects package keyword is a well-formed Android package id"
else
    bad "malformed package id(s) in the Projects section — these can never resolve:"
    echo "$MALFORMED" | sed 's/^/        /'
fi

echo "== T12: every section prefix is selectable on the Notify filter rows =="
# Adding a section to ui.phone_sections is only half of adding a section. The
# Notify tabs narrow by taxonomy through `filters_*` option ids, and an option
# id is a SET of prefixes (AggregatorStackFragment.taxonomyKeeps does
# `want.contains(prefix)`), so a prefix no option contains is a section whose
# apps reach no tab -- installable, classified, and unreachable. Checked over
# EVERY filters_* row, not just the first: the six rows are six copies of the
# same option list and a prefix added to one of them is a filter that works on
# one tab and not the other five.
while IFS=$'\t' read -r row prefix covered; do
    [ "$covered" = "true" ] && ok "$row offers section prefix '$prefix'" \
                            || bad "$row has no option containing '$prefix' — that section's apps reach no tab from this row"
done < <(q '
  (.ui.phone_sections | map(.prefix)) as $prefixes
  | .ui.sections[] | . as $section
  | to_entries[] | select(.key | startswith("filters_"))
  | .key as $row | .value as $filters
  | [ $filters[] | select(.id == "tools" or .id == "services") | .options[] | .id ] as $opts
  | $prefixes[] | . as $p
  | [ "\($section.id).\($row)", $p, ([ $opts[] | select(contains($p)) ] | length > 0) ]
  | @tsv')

echo
echo "-- test-phone-taxonomy-prefixes: $PASS passed, $FAIL failed --"
[ "$FAIL" -eq 0 ]
