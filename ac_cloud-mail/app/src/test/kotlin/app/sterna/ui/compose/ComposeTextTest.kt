package app.sterna.ui.compose

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.Span
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeTextTest {
    @Test fun detectsAttachmentMentionsAcrossLanguages() {
        val positives = listOf(
            "Please see the attached file",          // en
            "Voir le document ci-joint",             // fr
            "Voici la pièce jointe",                 // fr
            "Details im Anhang",                     // de
            "Te envío el documento adjunto",         // es
            "Trovi il file in allegato",             // it
            "Segue o documento em anexo",            // pt
            "Zie de bijlage",                        // nl
            "Смотри вложение",                       // ru
            "W załączeniu przesyłam plik",           // pl
        )
        positives.forEach { assertTrue("should flag: $it", mentionsAttachment(it)) }
    }

    @Test fun caseInsensitive() {
        assertTrue(mentionsAttachment("SEE THE ATTACHED FILE"))
        assertTrue(mentionsAttachment("PIÈCE JOINTE ci-dessous"))
    }

    @Test fun ignoresUnrelatedText() {
        listOf(
            "Hello, let's meet for lunch tomorrow",
            "Thanks for the quick reply",
            "Bonjour, à demain",
            "Re: Project Phoenix review",
        ).forEach { assertFalse("should not flag: $it", mentionsAttachment(it)) }
    }

    // --- Forwarding an HTML email while preserving its formatting ---

    private val sampleHtml = """
        <html><head><style>p{color:red}</style></head>
        <body>
          <p>Hello <strong>bold</strong> and <a href="https://example.com">link</a>.</p>
          <ul><li>one</li><li>two</li></ul>
          <script>alert('x')</script>
          <img src="cid:logo123@mail" alt="logo">
        </body></html>
    """.trimIndent()

    @Test fun forwardedHtmlPreservesOriginalFormatting() {
        val blocks = buildForwardedBlocks(
            from = "Alice <alice@example.com>", subject = "Quarterly report",
            date = "Mon, 1 Jun 2026 10:00:00 +0000", to = "Bob <bob@example.com>",
            originalText = "Hello bold and link.", originalHtml = sampleHtml,
        )
        assertTrue("strong kept", blocks.html.contains("<strong>bold</strong>"))
        assertTrue("list kept", blocks.html.contains("<li>one</li>") && blocks.html.contains("<li>two</li>"))
        assertTrue("link kept", blocks.html.contains("<a href=\"https://example.com\">link</a>"))
    }

    @Test fun forwardedHeaderPresentInBothOutputs() {
        val blocks = buildForwardedBlocks(
            from = "Alice <alice@example.com>", subject = "Quarterly report",
            date = "Mon, 1 Jun 2026 10:00:00 +0000", to = "Bob <bob@example.com>",
            originalText = "body", originalHtml = sampleHtml,
        )
        listOf(blocks.text, blocks.html).forEach { out ->
            assertTrue("forward header: $out", out.contains("Forwarded message"))
            assertTrue("From present: $out", out.contains("Alice <alice@example.com>") ||
                out.contains("Alice &lt;alice@example.com&gt;"))
            assertTrue("Subject present: $out", out.contains("Quarterly report"))
        }
    }

    @Test fun forwardedHtmlStripsScriptAndStyle() {
        val cleaned = cleanForwardedHtml(sampleHtml)
        assertFalse("no <script>", cleaned.contains("<script", ignoreCase = true))
        assertFalse("no alert body", cleaned.contains("alert('x')"))
        assertFalse("no <style>", cleaned.contains("<style", ignoreCase = true))
        assertFalse("no head", cleaned.contains("<head", ignoreCase = true))
    }

    @Test fun forwardedHtmlNeutralizesUncarriedCidImagesAsFallback() {
        // No image was carried (empty carriedCids) → the cid image is neutralised, not left broken.
        val cleaned = cleanForwardedHtml(sampleHtml)
        assertFalse("no cid src", cleaned.contains("cid:", ignoreCase = true))
        assertFalse("no leftover img tag", cleaned.contains("<img", ignoreCase = true))
        assertTrue("placeholder present", cleaned.contains("[image]"))
    }

    @Test fun forwardedHtmlKeepsCarriedCidImage() {
        // The image IS being re-attached (cid in carriedCids) → keep the <img src="cid:..."> intact.
        val cleaned = cleanForwardedHtml(sampleHtml, carriedCids = setOf("logo123@mail"))
        assertTrue("img kept", cleaned.contains("src=\"cid:logo123@mail\"", ignoreCase = true) ||
            cleaned.contains("cid:logo123@mail"))
        assertTrue("img tag kept", cleaned.contains("<img", ignoreCase = true))
        assertFalse("no placeholder", cleaned.contains("[image]"))
    }

    @Test fun carriedCidNormalisesAngleBrackets() {
        // The original src has no brackets; carriedCids supplied them — they must still match.
        val cleaned = cleanForwardedHtml(sampleHtml, carriedCids = setOf("<logo123@mail>"))
        assertTrue("img kept despite bracket mismatch", cleaned.contains("cid:logo123@mail"))
        assertFalse("no placeholder", cleaned.contains("[image]"))
    }

    @Test fun mixOfCarriedAndUncarriedCidImages() {
        val html = "<img src=\"cid:keep@x\"><img src=\"cid:drop@y\">"
        val cleaned = cleanForwardedHtml(html, carriedCids = setOf("keep@x"))
        assertTrue("carried kept", cleaned.contains("cid:keep@x"))
        assertFalse("uncarried dropped", cleaned.contains("cid:drop@y"))
        assertTrue("placeholder for the dropped one", cleaned.contains("[image]"))
    }

    @Test fun buildForwardedKeepsCarriedInlineImage() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "S", date = "d", to = "Bob",
            originalText = "t", originalHtml = sampleHtml,
            carriedCids = setOf("logo123@mail"),
        )
        assertTrue("carried img survives in html block", blocks.html.contains("cid:logo123@mail"))
    }

    @Test fun cidImageNeutralizedRegardlessOfAttributeOrderAndQuoting() {
        val variants = listOf(
            "<IMG alt='x' SRC=cid:abc>",
            "<img\n  src = \"cid:abc@host\" width=10>",
            "<img class='c' src='cid:zzz'/>",
        )
        variants.forEach { v ->
            val cleaned = cleanForwardedHtml("<p>before</p>$v<p>after</p>")
            assertFalse("cid removed in: $v", cleaned.contains("cid:", ignoreCase = true))
            assertTrue("structure kept around: $v", cleaned.contains("before") && cleaned.contains("after"))
        }
    }

    @Test fun remoteImagesAndStructureSurviveCleaning() {
        val html = "<p>x</p><img src=\"https://example.com/a.png\"><div>y</div>"
        val cleaned = cleanForwardedHtml(html)
        assertTrue("http img kept", cleaned.contains("src=\"https://example.com/a.png\""))
        assertTrue("div kept", cleaned.contains("<div>y</div>"))
    }

    @Test fun plainTextOriginalStillForwards() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes", date = "today", to = "Bob",
            originalText = "line one\nline two", originalHtml = null,
        )
        assertTrue("text carries original", blocks.text.contains("line one\nline two"))
        // No HTML part: the plain text is escaped into the html alternative with <br> breaks.
        assertTrue("html carries original", blocks.html.contains("line one<br>line two"))
        assertTrue("header in text", blocks.text.contains("Forwarded message"))
        assertTrue("header in html", blocks.html.contains("Forwarded message"))
    }

    @Test fun htmlEscapingProtectsAgainstTagInjectionInHeader() {
        val blocks = buildForwardedBlocks(
            from = "<script>evil</script>", subject = "a & b < c", date = "d", to = "e",
            originalText = "t", originalHtml = null,
        )
        assertFalse("from escaped in html", blocks.html.contains("<script>evil"))
        assertTrue("ampersand escaped", blocks.html.contains("a &amp; b &lt; c"))
    }

    // --- Reopening a saved draft in compose (#63) ---

    @Test fun draftFieldsCarryAddressingSubjectAndPlainBody() {
        val draft = Email(
            id = "d1",
            subject = "Half-written",
            to = listOf(EmailAddress(email = "a@example.com"), EmailAddress(name = "B", email = "b@example.com")),
            cc = listOf(EmailAddress(email = "c@example.com")),
            bcc = listOf(EmailAddress(email = "d@example.com")),
            textBody = listOf(EmailBodyPart(partId = "1", type = "text/plain")),
            bodyValues = mapOf("1" to EmailBodyValue("first line\nsecond line")),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("a@example.com, b@example.com", fields.to)
        assertEquals("c@example.com", fields.cc)
        assertEquals("d@example.com", fields.bcc)
        assertEquals("Half-written", fields.subject)
        assertEquals("first line\nsecond line", fields.body)
        assertTrue("cc/bcc row revealed", fields.expand)
    }

    @Test fun draftFieldsFlattenHtmlOnlyBodyToText() {
        // A draft saved by another client may be HTML-only; the plain-text editor gets it
        // flattened with paragraphs preserved, not raw markup on one line. Still true after
        // #131: `<p>` is not a tag this app's parser reads, so the html is refused and the
        // flattening is what the editor gets — the styled case is two tests below.
        val draft = Email(
            id = "d2",
            subject = "Html draft",
            to = listOf(EmailAddress(email = "a@example.com")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf("h" to EmailBodyValue("<p>one</p><p>two &amp; three</p>")),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("one\ntwo & three", fields.body)
        assertFalse("no cc/bcc row", fields.expand)
    }

    @Test fun draftFieldsReadTheStylingOfATwoPartDraftOfOurs() {
        // #131, and the trap it walks past: a draft this app saved has BOTH parts, and
        // `bodySource` takes `textBody[0]` whenever it is non-blank — so read that way the styling
        // is dropped every single time, in silence, with the letters all still on screen.
        val draft = Email(
            id = "d5",
            subject = "Styled draft",
            to = listOf(EmailAddress(email = "a@example.com")),
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf(
                "t" to EmailBodyValue("hello world"),
                "h" to EmailBodyValue("<b>hello</b> world"),
            ),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("hello world", fields.body)
        assertEquals(
            "the html part is read FIRST, so the bold comes back",
            mapOf(Inline.BOLD to listOf(Span(0, 5))),
            fields.bodyRanges,
        )
    }

    @Test fun draftFieldsTakeTheBodyAndTheSpansOutOfTheSamePart() {
        // The witness the test above cannot be: there both parts say the same words, so
        // `body = originalPlainText(o)` — the text part — passes it. Here they DIFFER, which two
        // parts of one draft are free to do (another client rewrote one, a save was interrupted).
        // The spans are measured on the HTML; laid over the text part they would cover other
        // letters, and the re-save would then destroy the original in favour of that.
        val draft = Email(
            id = "d7",
            subject = "Two parts that disagree",
            to = listOf(EmailAddress(email = "a@example.com")),
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf(
                "t" to EmailBodyValue("ok"),
                "h" to EmailBodyValue("<b>hello</b> world"),
            ),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals(
            "the html is what was READ, so its text is what the composer shows — the body and the " +
                "spans must come out of ONE answer",
            "hello world",
            fields.body,
        )
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 5))), fields.bodyRanges)
    }

    @Test fun draftFieldsCarryTheListsOfTheHtmlPart() {
        // #131 lists. The `<ul>` is in the html part only, and it must be read FIRST: read
        // text-first, whatever the text part holds would show as typed text and the next save
        // would store it INSIDE the body. The dashes here are the worst case on purpose — and
        // since the links (#131) that IS what a draft's text column holds: the save writes
        // `toPlainText(body)`, the one plain-text answer the send uses too, so the markers and the
        // addresses are spelled out in both. The html part is read first, so the reopen is exact;
        // this fixture is what happens when it cannot be.
        val draft = Email(
            id = "d8",
            subject = "Shopping",
            to = listOf(EmailAddress(email = "a@example.com")),
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf(
                "t" to EmailBodyValue("shopping\n- milk\n- eggs"),
                "h" to EmailBodyValue("shopping<br><ul><li>milk</li><li>eggs</li></ul>"),
            ),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("shopping\nmilk\neggs", fields.body)
        assertEquals(
            "the list must reach the prefill as a BLOCK — dropped, the reopened draft is three " +
                "loose lines and the next save sends it as such",
            listOf(Block(BlockKind.BULLET, 1..2)),
            fields.bodyBlocks,
        )
    }

    /**
     * ROUTE 1 OF 4 — the SERVER draft (JMAP and IMAP alike land on `draftFieldsOf`).
     *
     * Why this one is not decoration. Since the parser accepts an anchor, this draft is judged
     * REPRODUCIBLE (`draftHtmlIsLossy`), so the next save destroys the server original and puts
     * this composer's answer in its place. `bodyLinks` dropped here and the replacement carries no
     * anchor at all: the address is gone from the only copy there was.
     */
    @Test fun draftFieldsOfALinkedDraftCarryItsLinks() {
        val draft = Email(
            id = "d10",
            subject = "Linked draft",
            to = listOf(EmailAddress(email = "a@example.com")),
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf(
                "t" to EmailBodyValue("hello world"),
                "h" to EmailBodyValue("""hello <a href="https://x">world</a>"""),
            ),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("hello world", fields.body)
        assertEquals(
            "the link comes back off the html part, or reopening a draft removes an address",
            listOf(Link(Span(6, 11), "https://x")),
            fields.bodyLinks,
        )
    }

    /** ROUTE 1 OF 4, a list AND a link on one body: the two answers come back together. */
    @Test fun draftFieldsOfADraftWithAListAndALinkCarryBoth() {
        val draft = Email(
            id = "d11",
            subject = "Both",
            to = listOf(EmailAddress(email = "a@example.com")),
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf(
                "t" to EmailBodyValue("- milk\n- the site"),
                "h" to EmailBodyValue("""<ul><li>milk</li><li>the <a href="https://x">site</a></li></ul>"""),
            ),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("milk\nthe site", fields.body)
        assertEquals(listOf(Block(BlockKind.BULLET, 0..1)), fields.bodyBlocks)
        assertEquals(
            "the list and the link say different things about the same body; both must come back",
            listOf(Link(Span(9, 13), "https://x")),
            fields.bodyLinks,
        )
    }

    @Test fun draftFieldsOfAPlainDraftCarryNoStyling() {
        val draft = Email(
            id = "d6",
            subject = "Plain draft",
            to = listOf(EmailAddress(email = "a@example.com")),
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            bodyValues = mapOf("t" to EmailBodyValue("hello world")),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("hello world", fields.body)
        assertEquals(emptyMap<Inline, List<Span>>(), fields.bodyRanges)
        assertEquals(emptyList<Block>(), fields.bodyBlocks)
        assertEquals(emptyList<Link>(), fields.bodyLinks)
    }

    @Test fun draftFieldsTolerateEmptyDraft() {
        val fields = draftFieldsOf(Email(id = "d3"), cached = null)
        assertEquals("", fields.to)
        assertEquals("", fields.subject)
        assertEquals("", fields.body)
        assertFalse(fields.expand)
    }

    // --- A recipient the server can only give back as a name (#96) ---

    @Test fun draftFieldsKeepARecipientThatCameBackWithoutAnAddress() {
        // The reported case: "aa" was typed, holds no @, and comes back with an empty address and
        // the typed string in the name. The list row shows it (display()); the composer opened
        // without it, and a dropped Cc also folded the Cc/Bcc row back shut.
        val draft = Email(
            id = "d4",
            to = listOf(EmailAddress(name = "aa", email = "")),
            cc = listOf(EmailAddress(name = "bb", email = "")),
            bcc = listOf(EmailAddress(name = "cc", email = "")),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("aa", fields.to)
        assertEquals("bb", fields.cc)
        assertEquals("cc", fields.bcc)
        assertTrue("cc/bcc row revealed by a name-only recipient", fields.expand)
    }

    @Test fun draftFieldsPreferTheAddressOverTheDisplayNameInEveryField() {
        // The fallback must never outrank a real address, in any of the three fields: the field is
        // a comma-joined string that gets sent as-is, so reopening `Bob <bob@example.com>` as "Bob"
        // would mail the draft to `Bob`.
        val draft = Email(
            id = "d5",
            to = listOf(EmailAddress(name = "Bob", email = "bob@example.com")),
            cc = listOf(EmailAddress(name = "Carol", email = "carol@example.com")),
            bcc = listOf(EmailAddress(name = "Dave", email = "dave@example.com")),
        )
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("bob@example.com", fields.to)
        assertEquals("carol@example.com", fields.cc)
        assertEquals("dave@example.com", fields.bcc)
    }

    @Test fun draftFieldsDropAnEntryCarryingNeitherAddressNorName() {
        val draft = Email(
            id = "d6",
            to = listOf(
                EmailAddress(name = null, email = ""),
                EmailAddress(email = "a@example.com"),
                EmailAddress(name = "   ", email = "  "),
            ),
        )
        assertEquals("a@example.com", draftFieldsOf(draft, cached = null).to)
    }

    @Test fun draftFieldsKeepOrderAndSeparatorWhenMixingAddressesAndNames() {
        val draft = Email(
            id = "d7",
            to = listOf(
                EmailAddress(email = "a@example.com"),
                EmailAddress(name = "aa", email = ""),
                // An address of spaces is not an address either: the name is what is left.
                EmailAddress(name = "zz", email = " "),
                EmailAddress(name = "Bob", email = "b@example.com"),
            ),
        )
        assertEquals("a@example.com, aa, zz, b@example.com", draftFieldsOf(draft, cached = null).to)
    }

    // --- A recipient the server dropped altogether, still held by our cached row (#96) ---

    @Test fun draftFieldsFallBackOnTheCachedRowForAFieldTheServerReturnedEmpty() {
        // Measured on the bench: a server can refuse an address it judges invalid and hand the
        // field back empty. The typed string then survives in exactly one place — the row this app
        // cached, the one the Drafts list draws — so that is what the field reopens with.
        val draft = Email(id = "d8", to = emptyList())
        val cached = Email(id = "d8", to = listOf(EmailAddress(name = "aa", email = "")))
        assertEquals("aa", draftFieldsOf(draft, cached).to)
    }

    @Test fun draftFieldsNeverOverwriteAFieldTheServerFilled() {
        // The whole freshness rule, and the reason it is "the server returned NOTHING" and not
        // "the cache holds more": another client may have deliberately removed a recipient, and
        // our row is then simply stale. Putting it back would re-address the message behind the
        // user's back, which is worse than leaving a partial loss uncorrected.
        val draft = Email(id = "d9", to = listOf(EmailAddress(email = "b@example.com")))
        val cached = Email(
            id = "d9",
            to = listOf(EmailAddress(email = "a@example.com"), EmailAddress(email = "b@example.com")),
        )
        assertEquals("b@example.com", draftFieldsOf(draft, cached).to)
    }

    @Test fun draftFieldsFallBackFieldByFieldAndTheCcBccRowFollows() {
        // Per field, not per draft: a To the server kept is kept, while a Cc it dropped comes back
        // from the cache — and the reveal is computed from the recovered field, or the recipient
        // would be restored into a row folded shut.
        val draft = Email(id = "d10", to = listOf(EmailAddress(email = "a@example.com")))
        val cached = Email(
            id = "d10",
            to = listOf(EmailAddress(email = "zzz@example.com")),
            cc = listOf(EmailAddress(name = "bb", email = "")),
        )
        val fields = draftFieldsOf(draft, cached)
        assertEquals("a@example.com", fields.to)
        assertEquals("bb", fields.cc)
        assertTrue("cc/bcc row revealed by the recovered Cc", fields.expand)
    }

    @Test fun draftFieldsRecoveredFromTheCacheStayInTheirOwnField() {
        // Each field falls back on the SAME field of the cached row, and the other two stay empty.
        // Feed a recovered To to the Bcc — one token, `cached?.to` where `cached?.bcc` belongs —
        // and the recipient reopens in blind copy: the mail then leaves addressed to them twice,
        // one of the copies hidden. The cache holds no Cc/Bcc today, so the field it is read from
        // has to be pinned here, in the one test that names all three, or nothing sees the swap.
        val draft = Email(id = "d14", to = emptyList(), cc = emptyList(), bcc = emptyList())
        val cached = Email(id = "d14", to = listOf(EmailAddress(name = "aa", email = "")))
        val fields = draftFieldsOf(draft, cached)
        assertEquals("aa", fields.to)
        assertEquals("", fields.cc)
        assertEquals("", fields.bcc)
        assertFalse("nothing was recovered into Cc/Bcc, so their row stays folded", fields.expand)
    }

    @Test fun draftFieldsRecoverEveryCachedRecipientOfTheField() {
        // The fallback hands the field back whole: two typed recipients come back as two, in the
        // order and with the separator the field is parsed and sent with. A fallback that took the
        // first one would drop the rest silently, which is the very loss being repaired.
        val draft = Email(id = "d15", to = emptyList())
        val cached = Email(
            id = "d15",
            to = listOf(
                EmailAddress(name = "aa", email = ""),
                EmailAddress(name = "Bob", email = "bob@example.com"),
                EmailAddress(name = "zz", email = ""),
            ),
        )
        assertEquals("aa, bob@example.com, zz", draftFieldsOf(draft, cached).to)
    }

    @Test fun draftFieldsTolerateACachedRowThatCarriesNothing() {
        // Two cases that must change nothing: no cached row at all, and a row cached before schema
        // v17 (recipientsJson null, never backfilled) which decodes to an empty recipient list.
        val draft = Email(id = "d11", to = emptyList(), subject = "s")
        assertEquals("", draftFieldsOf(draft, cached = null).to)
        assertEquals("", draftFieldsOf(draft, Email(id = "d11")).to)
        assertEquals("s", draftFieldsOf(draft, Email(id = "d11")).subject)
    }

    // --- The Cc/Bcc row reveal, one field at a time (#96) ---

    @Test fun draftFieldsRevealTheCcBccRowForACcWithNoBcc() {
        val draft = Email(id = "d12", cc = listOf(EmailAddress(email = "c@example.com")))
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("c@example.com", fields.cc)
        assertEquals("", fields.bcc)
        assertTrue("a Cc alone must reveal the row it sits in", fields.expand)
    }

    @Test fun draftFieldsRevealTheCcBccRowForABccWithNoCc() {
        val draft = Email(id = "d13", bcc = listOf(EmailAddress(email = "d@example.com")))
        val fields = draftFieldsOf(draft, cached = null)
        assertEquals("", fields.cc)
        assertEquals("d@example.com", fields.bcc)
        assertTrue("a Bcc alone must reveal the row it sits in", fields.expand)
    }

    // --- Which field opens focused (#63, #83) ---
    //
    // "link" here is the prefill a mailto: URI (or a system Share) arrives with; it is null on
    // every composer opened from inside the app.

    private fun focusOf(
        isDraft: Boolean = false,
        isReply: Boolean = false,
        linkTo: String? = null,
        linkSubject: String? = null,
    ) = initialComposeFocus(isDraft, isReply, linkTo, linkSubject)

    // The four rows of the #83 table.

    @Test fun mailtoWithOnlyARecipientOpensOnTheSubject() {
        assertEquals(ComposeFocus.SUBJECT, focusOf(linkTo = "foo@example.com"))
    }

    @Test fun mailtoWithASubjectOpensOnTheBody() {
        assertEquals(ComposeFocus.BODY, focusOf(linkTo = "foo@example.com", linkSubject = "Hello"))
    }

    // A link that also carries a body changes nothing — the subject is what decides, so a link
    // with a body but NO subject still stops at the subject, the first field it left empty.
    @Test fun mailtoWithABodyButNoSubjectOpensOnTheSubject() {
        assertEquals(ComposeFocus.SUBJECT, focusOf(linkTo = "foo@example.com", linkSubject = ""))
    }

    @Test fun mailtoWithNoRecipientOpensOnTheRecipients() {
        assertEquals(ComposeFocus.RECIPIENTS, focusOf(linkTo = "", linkSubject = "Hello"))
    }

    // A system "Share" reuses the mailto: path with an empty To — it must keep landing on the
    // recipients, which is exactly what it is still missing.
    @Test fun sharedTextOpensOnTheRecipients() {
        assertEquals(ComposeFocus.RECIPIENTS, focusOf(linkTo = null, linkSubject = "Photo"))
    }

    // The four in-app ways of opening the composer, unchanged.

    @Test fun freshMailOpensOnTheRecipients() {
        assertEquals(ComposeFocus.RECIPIENTS, focusOf())
    }

    @Test fun replyOpensOnTheBody() {
        assertEquals(ComposeFocus.BODY, focusOf(isReply = true))
    }

    @Test fun reopenedDraftOpensOnTheBody() {
        assertEquals(ComposeFocus.BODY, focusOf(isDraft = true))
    }

    @Test fun forwardOpensOnTheRecipients() {
        // A forward is neither a draft nor a reply and carries no link prefill.
        assertEquals(ComposeFocus.RECIPIENTS, focusOf())
    }

    // --- Where the caret lands when compose opens prefilled (#63, #83) ---

    @Test fun reopenedDraftResumesAfterItsLastCharacter() {
        assertEquals(12, initialBodyCaret(bodyLength = 12, focus = ComposeFocus.BODY, isDraft = true))
    }

    @Test fun replyStartsAboveTheQuotedOriginal() {
        assertEquals(0, initialBodyCaret(bodyLength = 200, focus = ComposeFocus.BODY, isDraft = false))
    }

    // The #83 trap: the body a mailto: link opens on already holds the signature, so a link that
    // carries no body of its own must start at the very top or the user types under their signature.
    @Test fun mailtoWithNoBodyStartsAboveTheSignature() {
        val body = "" + signatureBlock("Alex\nAcme", delimiter = true)
        assertEquals(
            0,
            initialBodyCaret(
                bodyLength = body.length,
                focus = ComposeFocus.BODY,
                isDraft = false,
                linkBodyLength = 0,
            ),
        )
    }

    // mailto:foo@example.com?subject=Hello&body=Hello%20world! — one writes AFTER the text the link
    // supplied, so the caret sits at its end, which is also just above the appended signature (#83).
    @Test fun mailtoBodyResumesAfterTheTextTheLinkSupplied() {
        val link = "Hello world!"
        val body = link + signatureBlock("Alex\nAcme", delimiter = true)
        assertEquals(
            link.length,
            initialBodyCaret(
                bodyLength = body.length,
                focus = ComposeFocus.BODY,
                isDraft = false,
                linkBodyLength = link.length,
            ),
        )
    }

    @Test fun mailtoBodyWithNoSignatureEndsAtTheEndOfTheBody() {
        val link = "Hello world!"
        assertEquals(
            link.length,
            initialBodyCaret(
                bodyLength = link.length,
                focus = ComposeFocus.BODY,
                isDraft = false,
                linkBodyLength = link.length,
            ),
        )
    }

    // Defensive: the caret can never be asked for past the text it is placed in.
    @Test fun aLinkBodyLongerThanTheBodyStopsAtItsEnd() {
        assertEquals(
            5,
            initialBodyCaret(bodyLength = 5, focus = ComposeFocus.BODY, isDraft = false, linkBodyLength = 40),
        )
    }

    // A reply is not a link: its body is the quoted original and the caret stays above it.
    @Test fun replyIsUnaffectedByTheLinkRule() {
        assertEquals(
            0,
            initialBodyCaret(bodyLength = 200, focus = ComposeFocus.BODY, isDraft = false, linkBodyLength = 0),
        )
    }

    @Test fun aSubjectOrRecipientFocusLeavesTheBodyAlone() {
        assertEquals(null, initialBodyCaret(bodyLength = 40, focus = ComposeFocus.SUBJECT, isDraft = false))
        assertEquals(
            null,
            initialBodyCaret(
                bodyLength = 12,
                focus = ComposeFocus.RECIPIENTS,
                isDraft = false,
                linkBodyLength = 12,
            ),
        )
    }

    // --- Where a tap on a header row puts the caret (#26) ---
    //
    // Geometry of a To row on a 1080px-wide phone: the row starts at x=0, the label spans 42→168
    // and the editable text starts at 200.

    @Test fun tapOnTheLabelGoesToTheStartOfTheText() {
        assertEquals(0, headerTapCaret(tapX = 100f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapJustBeforeTheFirstCharacterGoesToTheStart() {
        assertEquals(0, headerTapCaret(tapX = 199f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapOnTheTextItselfIsLeftToTheField() {
        // The leading edge belongs to the field: from there on the caret lands under the finger.
        assertEquals(null, headerTapCaret(tapX = 200f, textStartX = 200f, textLength = 21))
        assertEquals(null, headerTapCaret(tapX = 260f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapPastTheEndOfTheTextIsLeftToTheField() {
        // Empty space after the text: the field keeps its own handling, i.e. the caret at the end.
        assertEquals(null, headerTapCaret(tapX = 900f, textStartX = 200f, textLength = 21))
    }

    @Test fun tapOnAnEmptyFieldForcesNothing() {
        assertEquals(null, headerTapCaret(tapX = 100f, textStartX = 200f, textLength = 0))
    }

    @Test fun unknownGeometryForcesNothing() {
        // Before the first layout, or a field whose text isn't composed (a collapsed chip row).
        assertEquals(null, headerTapCaret(tapX = Float.NaN, textStartX = 200f, textLength = 21))
        assertEquals(null, headerTapCaret(tapX = 100f, textStartX = Float.NaN, textLength = 21))
    }

    // --- Tapping a recipient chip to edit the address again (#94) ---
    //
    // The field is one comma-joined string: "a@x.com, b@x.com, " is two committed addresses and an
    // empty input. Tapping a chip moves that address to the trailing token — where the field puts
    // the caret at the end — and leaves the others committed, in order.

    private val three = "alex@x.com, jordan@y.com, mia@z.com, "

    @Test fun tappingAChipInTheMiddleTakesItOutOfTheChips() {
        assertEquals("alex@x.com, mia@z.com, jordan@y.com", recipientsWithChipEdited(three, 1))
    }

    @Test fun tappingTheFirstChipKeepsTheOthersInOrder() {
        assertEquals("jordan@y.com, mia@z.com, alex@x.com", recipientsWithChipEdited(three, 0))
    }

    @Test fun tappingTheLastChipJustReopensIt() {
        assertEquals("alex@x.com, jordan@y.com, mia@z.com", recipientsWithChipEdited(three, 2))
    }

    @Test fun theTappedAddressBecomesTheEditableToken() {
        // What the field then shows in its input, with the caret at its end.
        assertEquals("jordan@y.com", splitRecipients(recipientsWithChipEdited(three, 1)).second)
        assertEquals(listOf("alex@x.com", "mia@z.com"), splitRecipients(recipientsWithChipEdited(three, 1)).first)
    }

    // The chip most worth tapping: the one flagged as invalid, i.e. the typo to fix.
    @Test fun tappingAnInvalidAddressReopensItForCorrection() {
        assertEquals(
            "jordan@y.com, alex@@x.com",
            recipientsWithChipEdited("alex@@x.com, jordan@y.com, ", 0),
        )
    }

    @Test fun anAddressBeingTypedIsCommittedRatherThanLost() {
        assertEquals(
            "jordan@y.com, mia@z.co, alex@x.com",
            recipientsWithChipEdited("alex@x.com, jordan@y.com, mia@z.co", 0),
        )
    }

    @Test fun aTapOnNoChipAtAllLeavesTheFieldAlone() {
        assertEquals(three, recipientsWithChipEdited(three, 3))
        assertEquals(three, recipientsWithChipEdited(three, -1))
        assertEquals("", recipientsWithChipEdited("", 0))
    }

    // The comma-joined model's own limit, unchanged by the tap: a display name holding a comma is
    // already two tokens before it is tapped, and is still two afterwards.
    @Test fun aDisplayNameWithACommaIsTwoTokensEitherWay() {
        val field = "\"Lee, Jordan\" <j@y.com>, alex@x.com, "
        assertEquals(listOf("\"Lee", "Jordan\" <j@y.com>", "alex@x.com"), splitRecipients(field).first)
        assertEquals("Jordan\" <j@y.com>, alex@x.com, \"Lee", recipientsWithChipEdited(field, 0))
    }

    // Semicolons commit an address just like commas, so a field typed with them reads the same.
    @Test fun semicolonSeparatedAddressesAreChipsToo() {
        assertEquals("jordan@y.com, alex@x.com", recipientsWithChipEdited("alex@x.com; jordan@y.com; ", 0))
    }

    // --- Reply / reply-all header derivation (works from a cached row, so offline replies address) ---

    private val originalToReply = Email(
        id = "m1",
        subject = "Project Phoenix",
        from = listOf(EmailAddress(name = "Alice", email = "alice@example.com")),
        to = listOf(
            EmailAddress(email = "me@example.com"),
            EmailAddress(name = "Bob", email = "bob@example.com"),
        ),
        cc = listOf(EmailAddress(email = "carol@example.com")),
    )

    @Test fun replyGoesToTheOriginalSender() {
        assertEquals("alice@example.com", replyRecipient(originalToReply))
    }

    @Test fun replyRecipientEmptyWhenSenderUnknown() {
        assertEquals("", replyRecipient(Email(id = "x")))
    }

    @Test fun replyAllIncludesSenderToAndCcButNotSelf() {
        val all = replyAllRecipients(originalToReply, setOf("me@example.com"))
        assertEquals("alice@example.com, bob@example.com, carol@example.com", all)
    }

    @Test fun replyAllDropsSelfCaseInsensitivelyAndDeduplicates() {
        val o = originalToReply.copy(
            cc = listOf(EmailAddress(email = "ME@Example.com"), EmailAddress(email = "alice@example.com")),
        )
        // "ME@Example.com" == self (ignore case) is removed; the duplicate alice is collapsed.
        assertEquals("alice@example.com, bob@example.com", replyAllRecipients(o, setOf("me@example.com")))
    }

    @Test fun replyAllExcludesEveryAliasOfTheAccount() {
        // Three addresses on the account: none of them may end up in the recipients (B5). The
        // original was sent to two of them and Cc'd the third.
        val mine = setOf("me@example.com", "Alias@Example.com", "third@example.com")
        val o = Email(
            id = "m2",
            from = listOf(EmailAddress(email = "alice@example.com")),
            to = listOf(
                EmailAddress(email = "me@example.com"),
                EmailAddress(email = "alias@example.com"),
                EmailAddress(email = "bob@example.com"),
            ),
            cc = listOf(EmailAddress(email = "THIRD@example.com")),
        )
        assertEquals("alice@example.com, bob@example.com", replyAllRecipients(o, mine))
    }

    @Test fun replyAllToYourOwnAliasStillAnswersTheSender() {
        // The original came FROM one of your aliases: it drops out of the recipients too.
        val o = Email(
            id = "m3",
            from = listOf(EmailAddress(email = "alias@example.com")),
            to = listOf(EmailAddress(email = "bob@example.com")),
        )
        assertEquals(
            "bob@example.com",
            replyAllRecipients(o, setOf("me@example.com", "alias@example.com")),
        )
    }

    // --- Reply-To: the sender named another mailbox for answers, and it wins over the From ---
    //
    // Every expectation below is a literal, and the two functions are EXECUTED: inverting the
    // shipped priority (From first, Reply-To second) or re-adding the From beside the Reply-To
    // turns these red. The harm they hold: a reply that looks sent, addressed to the very mailbox
    // the sender set aside — a `no-reply@`, a list's posting alias behind a bounce address.

    /** The same original, with the sender pointing answers at a support address. */
    private val originalWithReplyTo = originalToReply.copy(
        replyTo = listOf(EmailAddress(name = "Support", email = "support@example.com")),
    )

    @Test fun replyGoesToTheReplyToNotTheSender() {
        assertEquals(listOf("support@example.com"), replyRecipients(originalWithReplyTo))
        assertEquals("support@example.com", replyRecipient(originalWithReplyTo))
    }

    @Test fun replyWithoutAReplyToStillGoesToTheSender() {
        // Non-regression: an empty Reply-To (an IMAP row cached before v20, a mail without the
        // header) addresses exactly as it did before.
        assertEquals(listOf("alice@example.com"), replyRecipients(originalToReply))
        assertEquals("alice@example.com", replyRecipient(originalToReply))
    }

    @Test fun replyToWithSeveralAddressesKeepsThemAllInOrder() {
        val o = originalToReply.copy(
            replyTo = listOf(
                EmailAddress(email = "support@example.com"),
                EmailAddress(email = "list@example.org"),
            ),
        )
        assertEquals(listOf("support@example.com", "list@example.org"), replyRecipients(o))
        assertEquals("support@example.com, list@example.org", replyRecipient(o))
    }

    @Test fun replyToRepeatedIsOneRecipient() {
        // Same address twice, and once more in the From's spelling: one entry, case ignored.
        val o = originalToReply.copy(
            replyTo = listOf(
                EmailAddress(email = "Alice@Example.com"),
                EmailAddress(email = "alice@example.com"),
            ),
        )
        assertEquals(listOf("Alice@Example.com"), replyRecipients(o))
        assertEquals("Alice@Example.com", replyRecipient(o))
    }

    @Test fun replyToEqualToTheSenderAppearsOnce() {
        val o = originalToReply.copy(replyTo = listOf(EmailAddress(email = "ALICE@example.com")))
        assertEquals("ALICE@example.com", replyRecipient(o))
        assertEquals(
            "ALICE@example.com, bob@example.com, carol@example.com",
            replyAllRecipients(o, setOf("me@example.com")),
        )
    }

    @Test fun replyToOfBlanksIsNoReplyToAtAll() {
        // A header that parsed to nothing must not leave the reply with no recipient: the From
        // takes over, exactly as when the header is absent.
        val o = originalToReply.copy(replyTo = listOf(EmailAddress(email = "   ")))
        assertEquals(listOf("alice@example.com"), replyRecipients(o))
        assertEquals("alice@example.com", replyRecipient(o))
    }

    @Test fun replyToBlanksAreDroppedBesideARealAddress() {
        val o = originalToReply.copy(
            replyTo = listOf(EmailAddress(email = " "), EmailAddress(email = " support@example.com ")),
        )
        assertEquals(listOf("support@example.com"), replyRecipients(o))
    }

    @Test fun replyAllUsesTheReplyToAndDropsTheSender() {
        // alice@example.com — the From — must NOT be here: the sender displaced it.
        assertEquals(
            "support@example.com, bob@example.com, carol@example.com",
            replyAllRecipients(originalWithReplyTo, setOf("me@example.com")),
        )
    }

    @Test fun replyAllWithoutAReplyToIsExactlyWhatItWas() {
        assertEquals(
            "alice@example.com, bob@example.com, carol@example.com",
            replyAllRecipients(originalToReply, setOf("me@example.com")),
        )
    }

    @Test fun replyAllWithSeveralReplyToAddressesKeepsThemSeparate() {
        val o = originalToReply.copy(
            replyTo = listOf(
                EmailAddress(email = "support@example.com"),
                EmailAddress(email = "list@example.org"),
            ),
        )
        assertEquals(
            "support@example.com, list@example.org, bob@example.com, carol@example.com",
            replyAllRecipients(o, setOf("me@example.com")),
        )
    }

    @Test fun replyAllCollapsesAReplyToAlreadyOnTheOriginal() {
        // The list's posting address is both the Reply-To and a To/Cc entry: one occurrence. Joined
        // into one string it would slip past the dedup and be written twice.
        val o = originalToReply.copy(
            replyTo = listOf(
                EmailAddress(email = "BOB@example.com"),
                EmailAddress(email = "Carol@Example.com"),
            ),
        )
        assertEquals(
            "BOB@example.com, Carol@Example.com",
            replyAllRecipients(o, setOf("me@example.com")),
        )
    }

    @Test fun replyAllDropsAReplyToThatIsOneOfYourOwnAddresses() {
        // Your own alias named as the Reply-To is filtered like any other self address (B5) — and
        // the From does not come back to fill the hole.
        val o = originalToReply.copy(replyTo = listOf(EmailAddress(email = "Alias@Example.com")))
        assertEquals(
            "bob@example.com, carol@example.com",
            replyAllRecipients(o, setOf("me@example.com", "alias@example.com")),
        )
    }

    // --- Which of your addresses received the mail, and the identity it makes you reply as (#81) ---

    private fun identity(address: String, name: String = "") =
        StoredIdentity(id = address, name = name, email = address)

    /** An account with three send-as addresses; only "alias@example.com" is on this original. */
    private val aliasOptions = listOf(
        FromOption("acc", identity("me@example.com")),
        FromOption("acc", identity("alias@example.com")),
        FromOption("acc", identity("third@example.com")),
    )

    @Test fun receivingAddressNamesTheAliasInTo() {
        assertEquals("me@example.com", receivingAddress(originalToReply, setOf("me@example.com")))
    }

    @Test fun receivingAddressPrefersToOverCc() {
        val o = originalToReply.copy(
            to = listOf(EmailAddress(email = "me@example.com")),
            cc = listOf(EmailAddress(email = "alias@example.com")),
        )
        assertEquals("me@example.com", receivingAddress(o, setOf("alias@example.com", "me@example.com")))
    }

    @Test fun receivingAddressFallsBackToCc() {
        val o = originalToReply.copy(
            to = listOf(EmailAddress(email = "bob@example.com")),
            cc = listOf(EmailAddress(email = "alias@example.com")),
        )
        assertEquals("alias@example.com", receivingAddress(o, setOf("me@example.com", "alias@example.com")))
    }

    @Test fun receivingAddressKeepsTheOriginalSpelling() {
        // Matched case-insensitively, but reported as the message spells it (it is shown verbatim).
        val o = originalToReply.copy(to = listOf(EmailAddress(email = "Alias@Example.com")))
        assertEquals("Alias@Example.com", receivingAddress(o, setOf("alias@example.com")))
    }

    @Test fun receivingAddressUnknownForAListOrABcc() {
        // None of your addresses is named: a mailing-list post, or a delivery you were Bcc'd on.
        val o = originalToReply.copy(
            to = listOf(EmailAddress(email = "list@example.org")),
            cc = emptyList(),
        )
        assertEquals(null, receivingAddress(o, setOf("me@example.com", "alias@example.com")))
        assertEquals(null, receivingAddress(o, emptySet()))
    }

    @Test fun replyPreselectsTheIdentityTheMailCameInOn() {
        val o = originalToReply.copy(
            to = listOf(EmailAddress(email = "Alias@Example.com"), EmailAddress(email = "bob@example.com")),
        )
        assertEquals(
            FromOption("acc", identity("alias@example.com")),
            receivingFromOption(aliasOptions, "acc", o),
        )
    }

    @Test fun replyPreselectsFromCcWhenTheAliasIsOnlyThere() {
        val o = originalToReply.copy(
            to = listOf(EmailAddress(email = "bob@example.com")),
            cc = listOf(EmailAddress(email = "third@example.com")),
        )
        assertEquals(
            FromOption("acc", identity("third@example.com")),
            receivingFromOption(aliasOptions, "acc", o),
        )
    }

    @Test fun replyKeepsTheDefaultIdentityWhenNoAddressOfYoursIsNamed() {
        // Null = "nothing to preselect": the caller leaves the account's default identity alone.
        val o = originalToReply.copy(
            to = listOf(EmailAddress(email = "list@example.org")),
            cc = emptyList(),
        )
        assertEquals(null, receivingFromOption(aliasOptions, "acc", o))
    }

    @Test fun replyNeverSwitchesToAnotherAccountsIdentity() {
        // The mail was addressed to an identity of a DIFFERENT account: replying stays on its own.
        val options = aliasOptions + FromOption("other", identity("work@example.com"))
        val o = originalToReply.copy(to = listOf(EmailAddress(email = "work@example.com")), cc = emptyList())
        assertEquals(null, receivingFromOption(options, "acc", o))
        assertEquals(
            FromOption("other", identity("work@example.com")),
            receivingFromOption(options, "other", o),
        )
    }

    @Test fun replyPreselectsForADelegatedSubAccount() {
        // A shared mailbox: its options are the addresses the store resolves FOR it (its own here),
        // under the sub-account's own id — the login's identity must not be picked instead.
        val options = listOf(
            FromOption("login", identity("me@example.com")),
            FromOption("sub", identity("shared@example.com")),
        )
        val o = originalToReply.copy(to = listOf(EmailAddress(email = "shared@example.com")), cc = emptyList())
        assertEquals(
            FromOption("sub", identity("shared@example.com")),
            receivingFromOption(options, "sub", o),
        )
    }

    @Test fun replyAllStillExcludesEveryAliasIncludingTheOneNowSending() {
        // The B5 guarantee, with #81 on top: the mail came in on alias@, so the reply goes out from
        // alias@ — and NONE of the account's addresses (alias@ included) may land in the recipients.
        val o = originalToReply.copy(
            to = listOf(EmailAddress(email = "alias@example.com"), EmailAddress(email = "bob@example.com")),
            cc = listOf(EmailAddress(email = "me@example.com")),
        )
        val mine = aliasOptions.map { it.identity.email }
        assertEquals(FromOption("acc", identity("alias@example.com")), receivingFromOption(aliasOptions, "acc", o))
        assertEquals("alice@example.com, bob@example.com", replyAllRecipients(o, mine))
    }

    @Test fun subjectGetsRePrefixOnlyWhenMissing() {
        assertEquals("Re: Project Phoenix", withPrefix("Project Phoenix", "Re:"))
        assertEquals("Re: Project Phoenix", withPrefix("Re: Project Phoenix", "Re:"))
        assertEquals("RE: already", withPrefix("RE: already", "Re:")) // existing prefix kept, any case
        assertEquals("Fwd: ", withPrefix(null, "Fwd:"))
    }

    // --- Late-arriving quote must not clobber the user's typing (cache-first ordering) ---

    @Test fun quoteAppliesOntoTheUntouchedInitialBody() {
        // Header prefill applied, body still equals its baseline ("" for a reply) → apply the quote.
        assertTrue(canApplyReplyQuote(applied = true, bodyText = "", initialBody = ""))
    }

    @Test fun quoteSkippedWhenUserHasStartedTyping() {
        assertFalse(canApplyReplyQuote(applied = true, bodyText = "Hi there", initialBody = ""))
    }

    @Test fun quoteWaitsUntilHeaderPrefillApplied() {
        assertFalse(canApplyReplyQuote(applied = false, bodyText = "", initialBody = ""))
    }

    // --- Quoting cuts the sender's signature off (D4), on a strict delimiter only ---

    @Test fun quotedOriginalStopsAtTheSignatureDelimiter() {
        val original = "Sounds good.\n\n-- \nAlice\nAcme Ltd\n+33 1 23 45 67 89"
        assertEquals("Sounds good.", cutAtSignatureDelimiter(original))
    }

    @Test fun theTwoHyphenFormWithoutTrailingSpaceAlsoCuts() {
        assertEquals("Sounds good.", cutAtSignatureDelimiter("Sounds good.\n\n--\nAlice"))
    }

    @Test fun aDecorativeRuleIsNotADelimiter() {
        val original = "Part one\n----------\nPart two"
        assertEquals(original, cutAtSignatureDelimiter(original))
    }

    @Test fun aLineMerelyStartingWithHyphensIsNotADelimiter() {
        val original = "Agenda\n--- end ---\nSee you"
        assertEquals(original, cutAtSignatureDelimiter(original))
        assertEquals("a\n-- b\nc", cutAtSignatureDelimiter("a\n-- b\nc"))
    }

    @Test fun theFirstDelimiterWins() {
        assertEquals("Body", cutAtSignatureDelimiter("Body\n-- \nSig one\n-- \nSig two"))
    }

    @Test fun anOriginalWithoutASignatureIsQuotedWhole() {
        assertEquals("Just a line", cutAtSignatureDelimiter("Just a line"))
    }

    @Test fun quotingCutsTheSignatureButReopeningADraftDoesNot() {
        val mail = Email(
            id = "q1",
            textBody = listOf(EmailBodyPart(partId = "1", type = "text/plain")),
            bodyValues = mapOf("1" to EmailBodyValue("Hello\n\n-- \nAlice")),
        )
        assertEquals("Hello", quotedOriginalText(mail))
        // A draft is the user's own text: cutting it at its delimiter would delete their signature.
        assertEquals("Hello\n\n-- \nAlice", draftFieldsOf(mail, cached = null).body)
    }

    // --- Forwarded header: labels translated, format untouched (D7) ---

    @Test fun forwardedHeaderUsesTheSuppliedLabels() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes", date = "4 juil. 2026, 09:12", to = "Bob",
            originalText = "body", originalHtml = null,
            labels = ForwardLabels(from = "De", subject = "Objet", date = "Date", to = "À"),
        )
        assertTrue(blocks.text.contains("De: Alice"))
        assertTrue(blocks.text.contains("Objet: Notes"))
        assertTrue(blocks.text.contains("Date: 4 juil. 2026, 09:12"))
        assertTrue(blocks.text.contains("À: Bob"))
        assertTrue(blocks.html.contains("Objet: Notes"))
    }

    @Test fun forwardedHeaderKeepsItsDashedLineAndFieldOrder() {
        val blocks = buildForwardedBlocks(
            from = "Alice", subject = "Notes", date = "d", to = "Bob",
            originalText = "body", originalHtml = null,
            labels = ForwardLabels(from = "De", subject = "Objet", date = "Date", to = "À"),
        )
        assertTrue(blocks.text.startsWith("---------- Forwarded message ----------\n"))
        val order = listOf("De:", "Objet:", "Date:", "À:").map { blocks.text.indexOf(it) }
        assertEquals(order.sorted(), order)
    }
}
