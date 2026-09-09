#!/usr/bin/env bash
# cloud-mail filters, foreign active script (#209): static proof, WITHOUT a gradle
# build (this runner cannot build) and without a device or a mail account.
#
# What went wrong. The Filters screen printed these two sentences together:
#
#     "Another filter script is active on the server; saving will make Cloud Mail's
#      the active one."
#     "No rules yet. Add one to filter incoming mail on the server."
#
# The app had listed the account's Sieve scripts (JMAP, RFC 9661), found one that
# was active and not its own, had its blobId in hand — and never downloaded it. So
# it told the owner their working filters did not exist, and invited them to build
# the script that would replace them: Save regenerates `sterna` and activates it,
# which stops whatever was filtering the account before.
#
#   S1  the repository SELECTS the foreign active script and downloads its body
#   S2  the flag and the content travel as one value, so neither can be claimed alone
#   S3  a save over a foreign active script goes through a confirmation
#   S4  every door that writes goes through that decision - no fourth door
#   S5  "No rules yet" is gated on the foreign script too, not just on our own
#   S6  the foreign script's text is rendered, read-only, and its absence is named
#   S7  the sentences exist in every locale and all name the script
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$APP/core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
READ="$APP/core/data/src/main/kotlin/app/sterna/core/data/filter/FilterRulesRead.kt"
STATUS="$APP/core/data/src/main/kotlin/app/sterna/core/data/filter/FilterScriptStatus.kt"
GATE="$APP/app/src/main/kotlin/app/sterna/ui/settings/FiltersSaveGate.kt"
SCREEN="$APP/app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt"
VM="$APP/app/src/main/kotlin/app/sterna/ui/settings/FiltersViewModel.kt"
RES="$APP/app/src/main/res"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF -- "$2" "$1" && ok "$3" || bad "$3 [$(basename "$1")]"; }
hasnt() { grep -qF -- "$2" "$1" && bad "$3 [$(basename "$1")]" || ok "$3"; }

echo "== cloud-mail filters: a foreign active script is read, shown, and never replaced silently =="

# ---- S1 the fetch that was missing --------------------------------------------------------
# `scripts.any { it.isActive && ... }` answers the question from the script LIST, which carries
# names and flags and never content. Selecting the script is what makes a body reachable.
has "$REPO" 'scripts.firstOrNull { it.isActive && it.name != SieveCodec.SCRIPT_NAME }' \
  "S1 the foreign active script is SELECTED, not counted"
has "$REPO" 'body = runCatching { bodyOf(other) }' \
  "S1 its body is downloaded through the same blob reader as ours"
has "$REPO" 'getOrElseUnlessCancelled { null }' \
  "S1 a body that would not download stays null, never an empty script"
# The bug, spelled out: any() cannot carry a body, and its presence here means the read went back
# to answering from the list.
loadbody=$(awk '/suspend fun loadFilterRules\(credentials: AccountCredentials\)/,/^    \/\*\*/' "$REPO")
case "$loadbody" in
  *"scripts.any { it.isActive"*) bad "S1 loadFilterRules still answers the foreign question from the list" ;;
  *) ok "S1 no boolean-only path is left beside the download" ;;
esac

# ---- S2 the flag and the content are one value --------------------------------------------
has "$READ" 'fun loadedFilterRules(sternaScript: String?, foreignScript: ForeignScript?)' \
  "S2 the read takes the script, not a boolean about it"
has "$READ" 'foreignActiveScript = foreignScript != null || unreadable' \
  "S2 the flag is DERIVED from the value, so it cannot be raised without one"
has "$READ" 'val body: String? = null' \
  "S2 an unfetched body is nullable and distinct from an empty one"
has "$VM" 'foreignScriptFromRulesRead = result.foreignScript' \
  "S2 the content reaches the screen's state, not just the flag"

# ---- S3 the save asks ----------------------------------------------------------------------
has "$GATE" 'CONFIRM_TAKEOVER' "S3 the gate has a step for stopping somebody else's script"
has "$GATE" 'foreignActive -> FiltersSaveStep.CONFIRM_TAKEOVER' \
  "S3 a foreign active script routes Save to that step"
has "$SCREEN" 'filtersSaveStep(scriptUnreadable = state.scriptUnreadable, foreignActive = state.foreignActive)' \
  "S3 the screen consults the gate with BOTH of its own flags"
# A literal here is the bug wearing the fix's clothes: the gate exists, is tested, and is asked a
# question that always has the same answer.
grep -qE 'filtersSaveStep\((scriptUnreadable|foreignActive) = (true|false)' "$SCREEN" \
  && bad "S3 the screen passes a literal to the gate" \
  || ok "S3 no literal is passed to the gate"

# ---- S4 no fourth door ---------------------------------------------------------------------
# Every write starts with viewModel.save(). Exactly three lines may contain it: the WRITE branch
# and the two confirmations' buttons. A fourth is how the first one was found open.
doors=$(grep -vE '^[[:space:]]*(//|\*|/\*)' "$SCREEN" | grep -cE 'viewModel(::save\b|\.save\(\))')
[ "$doors" = 3 ] && ok "S4 exactly three write doors, all behind the decision" \
                 || bad "S4 $doors write doors in FiltersScreen, expected 3"
has "$SCREEN" 'FiltersSaveStep.CONFIRM_TAKEOVER -> { takeoverThenLeave = thenLeave; confirmTakeover = true }' \
  "S4 the takeover branch arms the dialog rather than writing"
# Both gestures that write - the button and the exit dialog's Save - go through one funnel, so the
# confirmation belongs to the write and cannot be walked around by the back gesture.
onsave=$(grep -c 'requestSave(thenLeave = ' "$SCREEN")
[ "$onsave" = 2 ] && ok "S4 both Save gestures funnel through requestSave" \
                  || bad "S4 $onsave calls to requestSave, expected 2"

# ---- S5 the empty list stops lying ---------------------------------------------------------
has "$STATUS" 'fun showsNoRulesNote(ruleCount: Int, scriptUnreadable: Boolean, foreignActive: Boolean)' \
  "S5 the note's decision takes the foreign script into account"
has "$STATUS" 'ruleCount == 0 && !scriptUnreadable && !foreignActive' \
  "S5 …and all three inputs actually decide it"
has "$SCREEN" 'foreignActive = state.foreignActive,' "S5 the screen passes its real flag, not a literal"

# ---- S6 the owner can SEE the script -------------------------------------------------------
has "$SCREEN" 'private fun ForeignScriptBody(foreign: ForeignScript)' \
  "S6 there is a composable for the script's own text"
has "$SCREEN" 'state.foreignScript?.let { foreign -> ForeignScriptBody(foreign) }' \
  "S6 …and the screen renders it whenever there is one"
has "$SCREEN" 'settings_filters_foreign_body_unavailable' \
  "S6 a body that would not download says so instead of showing nothing"
has "$SCREEN" 'fontFamily = FontFamily.Monospace' "S6 Sieve is shown as the indented code it is"
# Read-only on purpose: an editor here would be a Sieve editor, and a parser filling the rule list
# from this text would drop every construct the rule model has no field for.
awk '/private fun ForeignScriptBody\(foreign: ForeignScript\)/,/^}/' "$SCREEN" \
  | grep -qE 'OutlinedTextField|TextField\(|onValueChange' \
  && bad "S6 the foreign script is editable - a partial Sieve editor drops what it cannot parse" \
  || ok "S6 the foreign script is read-only"

# ---- S7 the sentences, in every locale ------------------------------------------------------
for key in settings_filters_foreign_warning settings_filters_takeover_title \
           settings_filters_takeover_body settings_filters_takeover_confirm \
           settings_filters_foreign_body_title settings_filters_foreign_body_unavailable; do
  missing=""
  for d in "$RES"/values "$RES"/values-*; do
    [ -f "$d/strings.xml" ] || continue
    grep -q "name=\"$key\"" "$d/strings.xml" || missing="$missing $(basename "$d")"
  done
  [ -z "$missing" ] && ok "S7 $key present in every locale" \
                    || bad "S7 $key missing in:$missing"
done
# The script is NAMED. "Another filter script is active" is the sentence the owner read as a
# routine notice; a warning that cannot say which script is about to stop is that sentence again.
for key in settings_filters_foreign_warning settings_filters_takeover_title settings_filters_takeover_body; do
  unnamed=""
  for d in "$RES"/values "$RES"/values-*; do
    [ -f "$d/strings.xml" ] || continue
    grep "name=\"$key\"" "$d/strings.xml" | grep -q '%1\$s' || unnamed="$unnamed $(basename "$d")"
  done
  [ -z "$unnamed" ] && ok "S7 $key names the script in every locale" \
                    || bad "S7 $key does not name the script in:$unnamed"
done

echo
echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" = 0 ]
