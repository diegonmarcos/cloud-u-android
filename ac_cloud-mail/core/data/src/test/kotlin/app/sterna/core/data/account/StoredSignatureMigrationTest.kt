package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Named signatures (#206) must cost an existing install NOTHING.
 *
 * The failure this file exists to prevent is not a broken feature: it is an upgrade that works
 * perfectly and silently throws away the signature the owner has been sending for years. Every
 * assertion here is about the OLD record, read by the NEW code.
 */
class StoredSignatureMigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Migration: the single pre-#206 signature becomes the default of the new set -------------

    @Test fun theExistingPlainSignatureBecomesTheOnlyAndDefaultSignature() {
        val legacy = StoredIdentity("a", "Alex", "alex@masto.top", signature = "Alex Rivera\nAcme")
        val all = legacy.resolvedSignatures()
        assertEquals(1, all.size)
        assertEquals("Alex Rivera\nAcme", all.single().text)
        assertEquals("", all.single().html)
        assertEquals(StoredSignature.MIGRATED_ID, all.single().id)
        // …and it is what composing pre-selects, with no stored choice pointing at it.
        assertNull(legacy.defaultSignatureId)
        assertEquals(all.single(), legacy.defaultSignature())
    }

    @Test fun theExistingHtmlSignatureMigratesWithBothOfItsHalves() {
        val legacy = StoredIdentity(
            "a", "Alex", "alex@masto.top",
            signature = "Alex Rivera", signatureHtml = "<b>Alex Rivera</b>",
        )
        assertEquals("Alex Rivera", legacy.defaultSignature()?.text)
        assertEquals("<b>Alex Rivera</b>", legacy.defaultSignature()?.html)
        assertTrue(legacy.defaultSignature()!!.isHtml)
    }

    @Test fun aLegacyRawHtmlSignatureIsSplitAsItMigrates() {
        // Pre-1.3.13 "Import HTML" wrote raw markup into the PLAIN field. Migration must not carry
        // that forward as text, or the composer inserts angle brackets into the body.
        val legacy = StoredIdentity("a", "Alex", "alex@masto.top", signature = "<p>Alex Rivera</p>")
        val migrated = legacy.defaultSignature()!!
        assertEquals("Alex Rivera", migrated.text)
        assertEquals("<p>Alex Rivera</p>", migrated.html)
    }

    @Test fun migrationReadsTheLegacyFieldsAndNeverClearsThem() {
        // Read, not moved: a downgrade to a build with no [signatures] must still find the signature.
        val legacy = StoredIdentity("a", "Alex", "alex@masto.top", signature = "Alex Rivera")
        legacy.resolvedSignatures()
        assertEquals("Alex Rivera", legacy.signature)
    }

    @Test fun anAccountStoredBeforeNamedSignaturesStillReadsBackAndMigrates() {
        val stored = """
            {"id":"acc","server":"https://s","username":"alex@masto.top",
             "identities":[{"id":"a","name":"Alex","email":"alex@masto.top","signature":"Alex Rivera"}]}
        """.trimIndent()
        val account = json.decodeFromString(StoredAccount.serializer(), stored)
        val identity = account.identities.single()
        assertEquals(emptyList<StoredSignature>(), identity.signatures)
        assertEquals("Alex Rivera", identity.defaultSignature()?.text)
    }

    // --- A fresh install: no signature, and NOT one blank one ------------------------------------

    @Test fun afreshIdentityHasNoSignaturesAtAll() {
        val fresh = StoredIdentity("a", "Alex", "alex@masto.top")
        assertEquals(emptyList<StoredSignature>(), fresh.resolvedSignatures())
        // Not a list holding one empty signature: that would show a nameless blank row in every
        // picker and put a bare delimiter line into every message.
        assertNull(fresh.defaultSignature())
    }

    @Test fun aWhitespaceOnlyLegacySignatureCountsAsNone() {
        val blank = StoredIdentity("a", "Alex", "alex@masto.top", signature = "   \n ")
        assertEquals(emptyList<StoredSignature>(), blank.resolvedSignatures())
    }

    // --- Once there are named signatures, they win over the legacy pair --------------------------

    @Test fun anExplicitSetIsUsedAsIsAndTheLegacyPairIsIgnored() {
        val identity = StoredIdentity(
            "a", "Alex", "alex@masto.top",
            signature = "the old one",
            signatures = listOf(
                StoredSignature("work", "Work", "Alex, Acme"),
                StoredSignature("home", "Home", "Alex"),
            ),
        )
        assertEquals(listOf("work", "home"), identity.resolvedSignatures().map { it.id })
        // No stored choice: the FIRST is the default, exactly as [StoredAccount.defaultIdentity] does.
        assertEquals("work", identity.defaultSignature()?.id)
    }

    @Test fun theStoredDefaultIsHonouredWhereverItSitsInTheList() {
        val identity = StoredIdentity(
            "a", "Alex", "alex@masto.top",
            signatures = listOf(
                StoredSignature("work", "Work", "Alex, Acme"),
                StoredSignature("home", "Home", "Alex"),
            ),
            defaultSignatureId = "home",
        )
        assertEquals("home", identity.defaultSignature()?.id)
    }

    @Test fun aDefaultPointingAtSomethingGoneDegradesToTheFirst() {
        // The stored id names a row that a server refresh or a delete can take away; answering
        // "none" there would silently drop the signature off outgoing mail.
        val identity = StoredIdentity(
            "a", "Alex", "alex@masto.top",
            signatures = listOf(StoredSignature("work", "Work", "Alex, Acme")),
            defaultSignatureId = "deleted",
        )
        assertEquals("work", identity.defaultSignature()?.id)
    }

    // --- Round trip through storage --------------------------------------------------------------

    @Test fun namedSignaturesRoundTripThroughJsonUnescaped() {
        val identity = StoredIdentity(
            "a", "Alex", "alex@masto.top",
            signatures = listOf(StoredSignature("work", "Work", "Alex Rivera", "<b>Alex Rivera</b>")),
            defaultSignatureId = "work",
        )
        val back = json.decodeFromString(
            StoredIdentity.serializer(),
            json.encodeToString(StoredIdentity.serializer(), identity),
        )
        assertEquals(identity, back)
        // The markup survives as MARKUP: not escaped to &lt;b&gt; on the way through storage, which
        // is what would make the owner's HTML signature arrive as literal characters.
        assertEquals("<b>Alex Rivera</b>", back.defaultSignature()?.html)
    }

    // --- Telling the two halves of the label apart ------------------------------------------------

    @Test fun typedHtmlIsStoredAsHtmlWithAFlattenedTextHalf() {
        val typed = StoredSignature.of("s", "Work", "<b>Alex</b><br>Acme")
        assertTrue(typed.isHtml)
        assertEquals("<b>Alex</b><br>Acme", typed.html)
        assertEquals("<b>Alex</b><br>Acme", typed.source())
        assertEquals("Alex\nAcme", typed.text)
    }

    @Test fun typedPlainTextStaysPlainTextAndUntouched() {
        // The label promises "plain text OR HTML": a plain signature must not be put through an HTML
        // pipeline that escapes or reflows it.
        val typed = StoredSignature.of("s", "Home", "Alex Rivera\nAcme & co <not a tag")
        assertTrue(!typed.isHtml)
        assertEquals("", typed.html)
        assertEquals("Alex Rivera\nAcme & co <not a tag", typed.text)
        assertEquals("Alex Rivera\nAcme & co <not a tag", typed.source())
        assertEquals("", typed.renderableHtml())
    }

    @Test fun theEditorShowsTheSourceSoTypedMarkupSurvivesRecomposition() {
        // THE defect that made the label a lie: the field showed the FLATTENED text, so markup the
        // owner typed was redrawn as plain words and lost on the next keystroke.
        val typed = StoredSignature.of("s", "Work", "<i>Alex</i>")
        assertEquals("<i>Alex</i>", typed.source())
        assertEquals(typed, StoredSignature.of("s", "Work", typed.source()))
    }

    // --- The render path -------------------------------------------------------------------------

    @Test fun anHtmlSignatureKeepsItsFormattingWhenRendered() {
        val s = StoredSignature.of("s", "Work", "<b>Alex</b><br>Acme")
        assertEquals("<b>Alex</b><br>Acme", s.renderableHtml())
    }

    @Test fun anHtmlSignatureCannotExecuteOrReachTheNetworkWhenRendered() {
        // A signature is STORED content that gets rendered — by the recipient, by this app reading
        // the sent copy back, and by the editor's own preview. Import HTML reads a file off the
        // device, so "the owner supplied it" is not "the owner wrote it".
        val hostile = StoredSignature.of(
            "s", "Work",
            "<b>Alex</b><script>fetch('https://evil.example')</script>" +
                "<img src=\"x\" onerror=\"fetch('https://evil.example')\">" +
                "<iframe src=\"https://evil.example\"></iframe>" +
                "<a href=\"javascript:alert(1)\">click</a>",
        )
        val rendered = hostile.renderableHtml()
        assertTrue("the signature's own words survive", rendered.contains("<b>Alex</b>"))
        assertTrue("no script element", !rendered.contains("<script"))
        assertTrue("no script BODY either", !rendered.contains("fetch("))
        assertTrue("no event handler", !rendered.contains("onerror"))
        assertTrue("no nested document", !rendered.contains("<iframe"))
        assertTrue("no javascript: url", !rendered.contains("javascript:"))
    }
}
