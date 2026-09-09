#!/usr/bin/env bash
# cloud-mail HTML: static proof that received markup is sanitised before anything renders it, that
# the three HTML surfaces are wired to one another, and that the properties the last few tasks
# established still hold now that HTML is the body format -- WITHOUT a gradle build (this runner
# cannot build) and without an APK on a device. Same shape as test-mail-compose-signature-order.sh.
#
#   H1  received markup reaches a renderer through ONE function, and that function sanitises it
#   H2  the policy is a table, and the dangerous entries are IN it
#   H3  the containment the sanitiser backs up is still in place -- it is defence in DEPTH, and
#       proving one layer while another silently went away proves nothing
#   H4  remote pictures are not fetched until the user asks, and sanitisation does not take the
#       affordance away by stripping what it would load
#   H5  a link whose visible text names a host it does not go to is marked with the real one
#   H6  a sent HTML mail carries a text/plain alternative, generated from the body and not by
#       stripping tags out of the HTML
#   H7  the reply order -- answer, signature, quote -- still holds, in BOTH alternatives
#   H8  an old plain-text draft still opens
#   H9  the AI tools still resolve to their own engines and still refuse to rewrite markup
#  H10  the executed unit tests exist and are named
#  H11  the two compile breaks this task INHERITED stay fixed -- mail CI was red for the two
#       commits before this one, and neither cause was HTML
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
DATA="$APP/core/data/src/main/kotlin/app/sterna/core/data"
SANI="$DATA/text/ReceivedHtml.kt"
READER="$UI/message/ReaderBody.kt"
SCREEN="$UI/message/MessageScreen.kt"
TEXT="$UI/compose/ComposeText.kt"
VM="$UI/compose/ComposeViewModel.kt"
DRAFT="$DATA/text/DraftRichBody.kt"
SCOPE="$UI/text/TextToolScope.kt"
JMAP="$APP/core/jmap/src/main/kotlin/app/sterna/core/jmap/JmapClient.kt"
SANITEST="$APP/core/data/src/test/kotlin/app/sterna/core/data/text/ReceivedHtmlTest.kt"
DRAFT_REOPEN="$DATA/mail/LocalDraftReopen.kt"
REOPEN_TEST="$APP/core/data/src/test/kotlin/app/sterna/core/data/mail/LocalDraftReopenTest.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail HTML: sanitised on the way in, both alternatives on the way out =="

# ── H1 one door in, and it is guarded ──
# The reader and the print document both take a body from readerBody(). If the sanitiser sat at
# those two call sites instead, a third renderer added later would inherit nothing.
has "$SANI" 'fun sanitiseReceivedHtml(' "H1 the sanitiser exists"
has "$READER" 'return ReaderBody(sanitiseReceivedHtml(it), richHtml = true' \
  "H1 the ONE place a message's own markup becomes a fragment sanitises it"
# Every renderer must come through that function. A second reader of htmlContent() that builds a
# fragment of its own is the regression this counts.
n=$(grep -c 'readerBody(' "$SCREEN")
[ "$n" -ge 2 ] && ok "H1 both the reader and the print document route through readerBody ($n call sites)" \
  || bad "H1 a renderer does not go through readerBody"
hasnt "$SCREEN" 'ReaderBody(.*richHtml = true' \
  "H1 no second place builds a rich-HTML fragment"

# ── H2 the policy is a table, and it says the dangerous things ──
for table in KEPT_ELEMENTS DROPPED_WITH_CONTENT VOID_ELEMENTS GLOBAL_ATTRIBUTES \
             ELEMENT_ATTRIBUTES URL_ATTRIBUTE_SCHEMES; do
  has "$SANI" "private val $table" "H2 the policy names $table"
done
# The entries whose absence is a hole, each checked inside the DROPPED set rather than anywhere in
# the file -- "script" appears in prose too, and a comment is not a policy.
dropped=$(sed -n '/private val DROPPED_WITH_CONTENT/,/^)/p' "$SANI")
for el in script noscript iframe object embed form input svg math link meta base; do
  printf '%s' "$dropped" | grep -q "\"$el\"" \
    && ok "H2 <$el> is dropped with its content" \
    || bad "H2 <$el> is NOT in DROPPED_WITH_CONTENT"
done
# Event handlers are refused by a RULE, not by a list of names: a list needs extending for every
# attribute the platform ever adds, which is the same as saying it will eventually be wrong.
has "$SANI" "if (attribute.startsWith(\"on\")) return false" \
  "H2 every on* handler is refused by one rule"
# …and the attribute default is DENY. An allowlist that is consulted but not decisive is decoration.
has "$SANI" 'if (!allowed) return false' "H2 an attribute not in the tables is dropped"
# A URL attribute is judged on its DECODED value, or `java&#115;cript:` walks through.
has "$SANI" 'unescapeEntities(value ?: return false)' \
  "H2 a URL attribute's scheme is read after decoding, not before"
has "$SANI" "scheme != \"data\" || DATA_IMAGE" "H2 a data: URL may be a picture and nothing else"

# ── H3 the containment the sanitiser backs up is still there ──
# Defence in DEPTH: the sanitiser exists because these can regress, so a test that proves the
# sanitiser while one of these quietly went away has proved half of nothing.
# EVERY WebView that renders a message, not "at least one": the reader and the print document each
# build their own, and a grep satisfied by either would go quiet the day one of them was flipped.
# This is the shape that shipped as decoration once already -- two call sites sharing one assertion.
all=$(grep -c 'javaScriptEnabled' "$SCREEN")
off=$(grep -c 'javaScriptEnabled = false' "$SCREEN")
[ "$all" -ge 2 ] && [ "$all" -eq "$off" ] \
  && ok "H3 JavaScript is off on ALL $off of the $all WebViews that render a message" \
  || bad "H3 only $off of $all WebViews disable JavaScript"
has "$SCREEN" "default-src 'none'" "H3 the document's policy denies by default"
has "$SCREEN" "form-action 'none'" "H3 nothing in a message can submit"
has "$SCREEN" 'settings.domStorageEnabled = false' "H3 a message gets no on-device storage"
has "$SCREEN" 'val SAFE_OPEN_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo")' \
  "H3 only these schemes are handed to the system on a tap"
has "$SCREEN" 'if (!request.hasGesture()) return true' \
  "H3 a navigation without a real tap is refused, so a message cannot act by being viewed"

# ── H4 remote pictures: blocked first, loadable on request, and still THERE to load ──
has "$READER" '!plainText && (manualShow || senderAllowed)' \
  "H4 remote pictures need the user's request or a remembered sender"
has "$SCREEN" 'if (!blockRemote) return null' "H4 the load-time gate is what decides a fetch"
# Default-deny by SCHEME, not by naming http: "//evil.test/x.gif" arrives with no scheme at all.
has "$SCREEN" 'scheme == "data" || scheme == "cid" || scheme == "about"' \
  "H4 the gate allows only inert local schemes and blocks everything else"
# The other half, and the one sanitisation could break: `src` must SURVIVE the pass, or the
# "load images" button would have nothing to load and the pictures would never appear.
schemes=$(sed -n '/private val URL_ATTRIBUTE_SCHEMES/,/^)/p' "$SANI")
printf '%s' "$schemes" | grep -q '"src" to setOf("http", "https", "cid", "data")' \
  && ok "H4 a remote src survives sanitisation, so the affordance has something to load" \
  || bad "H4 sanitisation strips the src the load-images affordance needs"
# …and what the affordance CANNOT see must not survive, or a picture arrives with no way to ask
# for it: hasRemoteRefs reads `src`, so srcset and background may not be kept.
attrs=$(sed -n '/private val ELEMENT_ATTRIBUTES/,/^)/p' "$SANI")
for a in srcset background poster; do
  printf '%s' "$attrs" | grep -q "\"$a\"" \
    && bad "H4 $a is kept, but the images affordance cannot see it" \
    || ok "H4 $a is dropped -- the affordance reads src, so nothing may arrive past it"
done

# ── H5 the phishing shape ──
has "$SANI" 'fun markDeceptiveLinks(' "H5 the deceptive-link pass exists"
has "$SCREEN" 'inner = markDeceptiveLinks(inner)' "H5 the reader's document runs it"
# BEFORE the quote fold, so a forged thread's links are marked too.
awk '/inner = markDeceptiveLinks\(inner\)/{m=NR} /inner = foldTopLevelQuotes\(inner, quoteLabel\)/{f=NR} END{exit !(m && f && m < f)}' "$SCREEN" \
  && ok "H5 links are marked BEFORE the quote fold, so a forged thread is covered" \
  || bad "H5 the marking does not precede the quote fold"
# The real host is taken from after the LAST @, or "bank.test@evil.test" reads as the bank.
has "$SANI" 'substringAfterLast(.@.)' "H5 the real host is read after the last @, not before it"
# Both stylesheets carry the marker's rule: they share no CSS, so one of them alone is invisible.
n=$(grep -c '\.s-deceptive {' "$SCREEN")
[ "$n" -eq 2 ] && ok "H5 both document templates style the marker ($n rules)" \
  || bad "H5 the marker is styled in $n of the 2 templates"

# ── H6 both alternatives leave, and the plain one is not stripped tags ──
# A real HTML mail is multipart/alternative carrying BOTH parts, because recipients and their
# filters still expect the plain one. Generating only HTML is a regression dressed as a feature.
has "$VM" 'private suspend fun bodiesForSend(userBody: RichBody, identity: StoredIdentity?): Pair<String, String?>' \
  "H6 the send path builds a (text, html) PAIR"
has "$VM" 'return toPlainText(userBody) to html' \
  "H6 the plain alternative comes from the BODY MODEL, not from stripping the HTML"
hasnt "$VM" 'htmlToText(html)' "H6 the plain alternative is not tag-stripped HTML"
# …and both parts reach the wire as two named body values.
has "$JMAP" 'putJsonArray("textBody")' "H6 a text/plain part is declared on the wire"
has "$JMAP" 'putJsonArray("htmlBody")' "H6 a text/html part is declared on the wire"
has "$JMAP" 'if (htmlBody != null) putJsonObject("html") { put("value", htmlBody) }' \
  "H6 the html part carries a value of its own, beside the text one"

# ── H7 the order the last task pinned, now in both alternatives ──
# The plain-text assembly, unchanged -- this is the assertion test-mail-compose-signature-order.sh
# owns, repeated here because THIS change is the one that could have quietly replaced it.
has "$TEXT" 'return if (signatureBelowQuote) quoted + block else block + quoted' \
  "H7 the default is still block + quoted -- signature ABOVE the quote"
# The HTML alternative must not have grown a second ordering decision. It serialises the SAME body
# the plain one flattens, so the order cannot drift: there is nothing to drift from.
has "$TEXT" 'internal fun htmlBodyWithSignature(' "H7 the html alternative has one assembly too"
has "$TEXT" 'val found = signatureBlockAt(body.text, signature, delimiter) ?: return toHtml(body)' \
  "H7 the html alternative finds the signature in the SAME body the text one does"
hasnt "$TEXT" 'signatureBelowQuote.*toHtml' "H7 the html assembly holds no ordering decision of its own"
# A forward joins its original AFTER the body, in both alternatives, so the signature still precedes it.
has "$VM" 'return "${toPlainText(userBody)}\\n\\n${fwd.text}" to "$html<br><br>${fwd.html}"' \
  "H7 a forward's original is appended after the body in BOTH alternatives"

# ── H8 an old draft still opens ──
# Drafts written before the styling existed have no html part at all. Reopening one must fall back
# to its plain text rather than opening empty -- the failure that loses a message the user wrote.
has "$DRAFT" 'usableDraftHtml(html) ?: RichBody.plain(plainText())' \
  "H8 a draft with no usable html reopens on its plain text"
has "$DRAFT" 'fun draftHtmlToSave(body: RichBody): String? = if (body.isPlain) null else toHtml(body)' \
  "H8 a plain draft still stores no html part, so nothing about the old shape changed"
has "$DRAFT" 'fun draftHtmlIsLossy(' \
  "H8 html this editor cannot give back faithfully is NAMED, not silently flattened"

# ── H9 the AI tools ──
# They take text on purpose: a model handed markup returns markup of its own choosing. HTML support
# does not change that reasoning, so the rule must still be written down and still be true.
has "$SCOPE" 'HTML IS NEVER REWRITTEN' "H9 the text-only rule is still stated"
has "$SCOPE" 'fun historyStart(text: String): Int' "H9 the quote/signature scope walk is still there"
has "$SCOPE" 'while (i >= 0 && lines\[i\].isBlank()) i--' \
  "H9 the walk still starts from the BOTTOM, so an interleaved reply survives"
# The engines, not crossed. The pairing is the property; this is the one line that maps them.
has "$UI/text/TextToolRun.kt" 'enum class TextTool { ENHANCE, TRANSLATE, RESUME }' \
  "H9 the three tools are still the three tools"

# ── H10 the assertions that EXECUTE the policy ──
# The greps above prove the wiring; these named tests run the sanitiser and compare whole strings.
for t in aScriptElementAndItsCodeBothGo \
         everyEventHandlerIsDroppedWhateverItIsSpelledOn \
         aJavascriptHrefIsDroppedAndTheWordsAreKept \
         aSchemeHiddenInEntitiesOrControlCharactersIsStillRead \
         aDataUrlIsAPictureOrItIsNotALoad \
         aGreaterThanInsideAQuotedAttributeDoesNotEndTheTag \
         nothingMayNameASubresourceOrRestateTheDocumentsDirectives \
         aVoidElementDoesNotSwallowTextUpToAStrayCloseTag \
         anImportRuleGoesAndTheRestOfTheStylesheetStays \
         aRemotePictureSurvivesSanitisationSoTheGateCanStillHoldIt \
         remoteReferencesTheImagesAffordanceCannotSeeAreDropped \
         anUnknownElementLosesItsTagsAndKeepsItsWords \
         anUnclosedDroppedElementCostsItsOwnTagAndNotTheMessage \
         aLinkThatNamesOneHostAndGoesToAnotherIsMarkedWithTheRealOne \
         aLinkThatClaimsNothingOrClaimsTrulyIsLeftAlone; do
  has "$SANITEST" "fun $t(" "H10 executed: $t"
done

# ── H11 the inherited compile breaks ──
# Mail CI failed on the two commits before this one, for two reasons that had nothing to do with
# HTML. Both are shape errors a grep can hold, and both are the kind that come back.
#
# (a) `mailboxDisplayName` is @Composable. Both consumers want a plain `(Mailbox) -> String`, and on
#     the header's path the call sits inside a `remember` -- not a composable context at any point.
#     Passing it directly is "@Composable invocations can only happen from the context of a
#     @Composable function", three times.
hasnt "$SCREEN" 'nameOf = { mailboxDisplayName(' \
  "H11 no @Composable name resolver is passed where a plain lookup is required"
n=$(grep -c 'associate { it.id to mailboxDisplayName(it.role, it.name) }' "$SCREEN")
[ "$n" -eq 2 ] && ok "H11 both scopes resolve the names where resources ARE reachable ($n)" \
  || bad "H11 $n of the 2 scopes resolve mailbox names for a plain lookup"
# (b) DraftFields.showAllRecipients was read off a projection that did not carry it. It is a SAFETY
#     property (every address on screen before a word is typed), so the projection derives it from
#     the addresses the row holds rather than defaulting it away.
has "$DRAFT_REOPEN" 'val showAllRecipients: Boolean,' \
  "H11 the local-draft projection carries showAllRecipients"
has "$DRAFT_REOPEN" 'showAllRecipients = to.size + cc.size + bcc.size > 1,' \
  "H11 …derived from the row's own addresses, not defaulted to false"
has "$REOPEN_TEST" 'a reopened draft with more than one recipient shows every recipient chip' \
  "H11 executed: the derivation is run, not just wired"

echo
echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
