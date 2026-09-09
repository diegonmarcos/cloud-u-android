#!/usr/bin/env bash
# cloud-mail attachment chips: static proof that the message LIST can name a message's files and
# open one, that it costs no extra request per page, that only rows WITH files grow, and that a
# filename and a MIME type chosen by a stranger cannot decide where bytes land or what opens them --
# WITHOUT a gradle build (this runner cannot build) and without an APK on a device.
# Same shape as test-mail-html.sh and test-mail-signatures.sh.
#
#   A1  the LIST QUERY asks for the attachment metadata, in the SAME request as the rest of the page
#   A2  every JMAP path that caches a row asks with ONE list, not a copy each
#   A3  IMAP pays nothing: the metadata was already on the wire and is now read instead of dropped
#   A4  the metadata survives into the cache, through BOTH mappers, as metadata and never as bytes
#   A5  only rows WITH attachments grow -- the chips are emitted conditionally, inside the row's
#       content-sized column, and nothing gains a fixed or minimum height
#   A6  a chip tap resolves to the DOWNLOAD path and not to opening the message
#   A7  there is ONE way out of this app with a file, and both surfaces use it
#   A8  a hostile filename cannot escape the directory it is written into
#   A9  the sender's declared MIME type does not decide what opens the file
#  A10  a large attachment over mobile data is asked about, and the rule is testable
#  A11  a failed tap says so -- no silent no-op
#  A12  the executed unit tests exist and are named
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
DATA="$APP/core/data/src/main/kotlin/app/sterna/core/data"
JMAP="$APP/core/jmap/src/main/kotlin/app/sterna/core/jmap/JmapClient.kt"
LIMITS="$APP/core/jmap/src/main/kotlin/app/sterna/core/jmap/DownloadLimits.kt"
IMAPTEXT="$APP/core/imap/src/main/kotlin/app/sterna/core/imap/TextPart.kt"
IMAPCLIENT="$APP/core/imap/src/main/kotlin/app/sterna/core/imap/ImapClient.kt"
IMAPSVC="$DATA/mail/ImapMailService.kt"
MAPPER="$DATA/mail/EmailMapper.kt"
ENTITY="$DATA/db/EmailEntity.kt"
MIGRATIONS="$DATA/db/Migrations.kt"
DB="$DATA/db/SternaDatabase.kt"
CODEC="$DATA/db/EmailAttachments.kt"
SAFENAME="$DATA/storage/SafeFileName.kt"
MIME="$DATA/storage/AttachmentMime.kt"
STORAGE="$DATA/storage/StorageRepository.kt"
ROW="$UI/components/EmailListItem.kt"
OPEN="$UI/attachment/AttachmentOpen.kt"
INBOXVM="$UI/inbox/InboxViewModel.kt"
INBOXUI="$UI/inbox/InboxScreen.kt"
READERVM="$UI/message/MessageViewModel.kt"
BOUNDARY="$APP/core/data/src/test/kotlin/app/sterna/core/data/storage/AttachmentBoundaryTest.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail attachment chips: named in the list, opened from it, at no cost per page =="

# ── A1 the page asks for the metadata, in the page's own request ──
# The whole task turns on this. `attachments` (RFC 8621 4.1.4) is derived and body-free, so it rides
# the chained Email/get the page already issues. A fetch per row would be 50 round trips per page.
has "$JMAP" 'internal val EMAIL_LIST_PROPERTIES' "A1 the list property set is named once"
awk '/internal val EMAIL_LIST_PROPERTIES/,/^        \)/' "$JMAP" | grep -q '"attachments"' \
  && ok "A1 the list property set asks for attachments" \
  || bad "A1 the list property set does NOT ask for attachments ($JMAP)"
# It must NOT have brought the body along with it: bodyValues on a 50-row page is the regression.
awk '/internal val EMAIL_LIST_PROPERTIES/,/^        \)/' "$JMAP" | grep -q 'bodyValues\|htmlBody\|textBody' \
  && bad "A1 the list property set pulls BODY content onto every page ($JMAP)" \
  || ok "A1 the list set carries no body content"
# One request per page: the get is CHAINED off the query by back-reference, not issued per id.
has "$JMAP" 'put("resultOf", "q0")' "A1 the page's get is chained off its query, not issued per row"

# ── A2 one list, not a copy per call site ──
# Three inline copies each warned that the copies must agree. A warning repeated is not a mechanism.
n=$(grep -c 'EMAIL_LIST_PROPERTIES' "$JMAP")
[ "$n" -ge 4 ] && ok "A2 every caching path uses the one list ($n references)" \
  || bad "A2 a caching path still carries its own copy ($JMAP)"
# The search/crawl set is deliberately separate and deliberately WITHOUT attachments: neither writes
# an `emails` row, and the crawl walks the whole account.
has "$JMAP" 'internal val EMAIL_INDEX_PROPERTIES' "A2 the index set is named separately"
awk '/internal val EMAIL_INDEX_PROPERTIES/,/^        \)/' "$JMAP" | grep -q '"attachments"' \
  && bad "A2 the crawl now drags attachments over the whole account ($JMAP)" \
  || ok "A2 the crawl set stays lean"

# ── A3 IMAP pays nothing ──
# BODYSTRUCTURE is already in every list FETCH and was already walked twice. This is a third read.
has "$IMAPCLIENT" 'ENVELOPE BODYSTRUCTURE' "A3 the list FETCH already asks for BODYSTRUCTURE"
has "$IMAPTEXT" 'fun attachmentParts(' "A3 the file parts are read out of that same structure"
has "$IMAPCLIENT" 'attachments = attachmentParts(map\["BODYSTRUCTURE"\])' \
  "A3 the parse fills them from the structure it already has"
# No new FETCH item may have been added to pay for it. Pinned by the whole item LIST of every list
# FETCH, not by a guessed string: an assertion that a made-up item is absent can never fail, and this
# file shipped one on its first pass.
want='(UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])'
n=$(grep -c -F "$want" "$IMAPCLIENT")
extra=$(grep -oE 'FETCH [^(]*\(UID FLAGS[^\n]*' "$IMAPCLIENT" | grep -vF "$want" | wc -l)
[ "$n" -ge 4 ] && [ "$extra" -eq 0 ] \
  && ok "A3 all $n list FETCHes still ask for exactly the items they always did" \
  || bad "A3 a list FETCH gained or lost an item ($n matching, $extra differing) ($IMAPCLIENT)"
# The disposition index is per-shape. A single hardcoded 9 silently misreads message/rfc822.
has "$IMAPTEXT" 'private fun dispositionIndex(' "A3 body-fld-dsp is located per part shape"

# ── A4 the metadata reaches the cache, both ways, through BOTH mappers ──
has "$ENTITY" 'val attachmentsJson: String? = null' "A4 the row has a column for it"
has "$MIGRATIONS" 'ALTER TABLE `emails` ADD COLUMN `attachmentsJson` TEXT' "A4 the migration adds it"
has "$DB" 'MIGRATION_27_28' "A4 the migration is registered"
has "$DB" 'SCHEMA_VERSION = 28' "A4 the schema version moved with it"
has "$MAPPER" 'attachmentsJson = EmailAttachments.encode(attachments)' "A4 the JMAP mapper writes it"
has "$MAPPER" 'attachments = EmailAttachments.decode(attachmentsJson)' "A4 the JMAP mapper reads it back"
# The IMAP mapper is a SECOND door into the same table and the @Upsert replaces the row whole:
# filling only one of them means every sync pass erases the other's chips.
has "$IMAPSVC" 'attachmentsJson = EmailAttachments.encode' "A4 the IMAP mapper writes it too"
# Metadata only. A body value in this column is a page of 50 messages holding 50 files.
hasnt "$CODEC" 'bodyValues' "A4 the stored parts carry no body content"

# ── A5 only rows WITH attachments grow ──
has "$ROW" 'if (onOpenAttachment != null && attachmentParts.isNotEmpty())' \
  "A5 the chips are emitted only when there are files"
# The row's height is its content. A height/heightIn anywhere in this file would make EVERY row as
# tall as the tallest, which is exactly the complaint the folder sidebar fix was about.
hasnt "$ROW" '\.height(' "A5 no row is given a fixed height"
hasnt "$ROW" 'heightIn(' "A5 no row is given a minimum height"
# The chips sit INSIDE the weighted column, above the origin chip -- not in the outer Row, where
# they would fight the star for width.
awk '/^fun EmailListItem\(/,/^}/' "$ROW" | grep -q 'AttachmentChips(' \
  && ok "A5 the chips are drawn inside the row's own column" \
  || bad "A5 the chips are not where the row's height comes from ($ROW)"
# Long names are shortened from the MIDDLE, keeping the extension: the folder sidebar settled that
# truncating into unreadability is not this module's answer (b5e47807e).
has "$ROW" 'internal fun attachmentChipLabel(' "A5 long filenames have a stated rule"
has "$ROW" 'name.take(head) + "…" + name.takeLast(tail)' "A5 the rule keeps BOTH ends of the name"
# Several files wrap rather than truncate or scroll -- a horizontal scroller inside a vertical list
# eats the list's own drags.
has "$ROW" 'FlowRow(' "A5 several chips wrap"
has "$ROW" 'MAX_ATTACHMENT_CHIPS' "A5 and are capped, so 20 files cannot make a row a screenful"

# ── A6 a chip tap downloads; it does not open the message ──
has "$ROW" 'clickable(enabled = !busy, onClick = onOpen)' "A6 the chip has its own click"
# THE assertion of this section: the chip's handler must NOT be the row's onClick.
awk '/private fun AttachmentChip\(/,/^}/' "$ROW" | grep -q 'onClick = onOpen' \
  && ok "A6 the chip's tap is its own handler" \
  || bad "A6 the chip's tap is not wired to its own handler ($ROW)"
awk '/private fun AttachmentChip\(/,/^}/' "$ROW" | grep -q 'onClick()\|onClick = onClick' \
  && bad "A6 the chip falls through to opening the message ($ROW)" \
  || ok "A6 the chip does not fall through to the message"
has "$INBOXUI" 'onOpenAttachment = { part -> viewModel.openAttachment(email, part) }' \
  "A6 the list wires the chip to the download, not to onOpenEmail"
has "$INBOXVM" 'AttachmentOpen.openExternally(app, repo, storage, credentials, part, email.id)' \
  "A6 and that call reaches the download path"
# An unfolded conversation child is a row too: a file reachable collapsed and unreachable expanded
# is the affordance vanishing exactly where the user went looking.
has "$INBOXUI" 'onOpenAttachment = { child, part -> viewModel.openAttachment(child, part) }' \
  "A6 conversation children offer their own files"

# ── A7 one way out of this app with a file ──
has "$OPEN" 'FileProvider.getUriForFile' "A7 the file leaves as a content:// URI, never a path"
has "$OPEN" 'FLAG_GRANT_READ_URI_PERMISSION' "A7 with a read grant scoped to the receiving app"
has "$OPEN" 'Intent.createChooser' "A7 through a chooser, so no app is picked for the user"
# The reader had this code first. It must now CALL it rather than carry a second copy: two copies
# is two chances for one of them to stop granting, or to keep trusting the sender's type.
has "$READERVM" 'AttachmentOpen.openExternally(' "A7 the reader uses the shared path"
hasnt "$READERVM" 'FileProvider.getUriForFile' "A7 and no longer has its own copy"
n=$(grep -rl 'FileProvider.getUriForFile' "$UI" | wc -l)
[ "$n" -eq 1 ] && ok "A7 exactly one place hands a file to another app" \
  || bad "A7 $n places hand a file out; there must be one"

# ── A8 a hostile filename cannot escape ──
has "$SAFENAME" 'object SafeFileName' "A8 the name rule is pure and has a name"
has "$STORAGE" 'SafeFileName.of(name)' "A8 the disk write goes through it"
hasnt "$STORAGE" 'Regex("\[^A-Za-z0-9._-\]")' "A8 the old inline replacement is gone from the writer"
# The containment check is NOT redundant with the rule: it is what must hold whatever the rule does,
# including after someone edits the rule.
has "$STORAGE" 'target.parentFile?.canonicalPath == root' "A8 the write is checked against where it lands"
has "$SAFENAME" "reduced.all { it == '.' }" "A8 a name of only dots is a DIRECTORY and is refused"
# Length must not be able to remove the extension: that is a security decision made by a cosmetic rule.
has "$SAFENAME" 'name.take(MAX_LENGTH - extension.length) + extension' \
  "A8 shortening a long name keeps its extension"

# ── A9 the sender's claim does not decide what opens the file ──
has "$MIME" 'object AttachmentMime' "A9 the type rule is pure and has a name"
has "$OPEN" 'AttachmentMime.of(part.type, mimeFromName(file.name))' "A9 the open path uses it"
has "$OPEN" 'getMimeTypeFromExtension' "A9 the FILE is asked first"
has "$MIME" 'application/vnd.android.package-archive' "A9 the package installer is never summoned"
hasnt "$OPEN" 'part.type ?: "\*/\*"' "A9 the raw claim is not handed to the system"

# ── A10 mobile data ──
# Nothing is refused -- the tap was consent -- but a LARGE file on a metered network is asked about.
has "$LIMITS" 'const val METERED_CONFIRM_BYTES' "A10 the threshold is beside the other ceilings"
has "$LIMITS" 'fun needsMeteredConfirmation(size: Long, metered: Boolean)' \
  "A10 the rule is pure, so it is testable without a device"
has "$INBOXVM" 'DownloadLimits.needsMeteredConfirmation(part.size, AttachmentOpen.isMetered(app))' \
  "A10 the tap consults it before fetching anything"
has "$INBOXUI" 'attachment_metered_title' "A10 and the question is actually asked"
# Fail-safe: unknown network, null service and any exception all count as metered.
has "$OPEN" 'getOrDefault(true)' "A10 an unreadable network counts as metered"
has "$OPEN" '?: return true' "A10 a null connectivity service counts as metered"

# ── A11 a failed tap says so ──
has "$INBOXVM" 'status_open_attachment_failed' "A11 a failure names the file that failed"
has "$INBOXVM" 'status_attachment_too_large' "A11 our own ceiling is said plainly, not as an error"
has "$ROW" 'CircularProgressIndicator' "A11 a download in progress is visible on the chip"
has "$INBOXVM" '_openingAttachment.value = null' "A11 the progress state is always released"
# The catch must not swallow: an empty catch block is the silent no-op this cannot be.
awk '/private fun download\(/,/^    }/' "$INBOXVM" | grep -q '_message.value = app.getString' \
  && ok "A11 the failure reaches the user, not just logcat" \
  || bad "A11 a failed tap is silent ($INBOXVM)"

# ── A12 the executed tests exist ──
has "$BOUNDARY" 'class AttachmentBoundaryTest' "A12 the boundary test exists"
for t in 'no declared name escapes the attachment directory' \
         'a name that is only directory dots becomes the fallback' \
         'a long name is shortened but keeps its extension' \
         'the file decides the type, not the sender' \
         'the package installer is never summoned by a mail attachment'; do
  has "$BOUNDARY" "$t" "A12 executed: $t"
done

echo
echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
