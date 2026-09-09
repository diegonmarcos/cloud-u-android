package app.sterna.ui.settings

import app.sterna.ui.compose.bodyWithSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Identities editor opens on the default sender and folds the rest away. These cover the
 */
class IdentityRowsTest {

    private val server = IdentityRowRef("srv-1", listOf("srv-1"))
    private val serverAlias = IdentityRowRef("ovr-2", listOf("srv-2"))
    private val manual = IdentityRowRef("man-3")

    // --- Which row opens ---------------------------------------------------------------------

    @Test fun theDefaultSenderIsTheOneLeftOpen() {
        val rows = listOf(server, serverAlias, manual)
        assertEquals("man-3", initialExpandedIdentityId(rows, "man-3"))
        assertEquals("srv-1", initialExpandedIdentityId(rows, "srv-1"))
    }

    @Test fun anOverriddenServerRowMatchesOnEitherId() {
        val rows = listOf(server, serverAlias, manual)
        // The default was stored against the server's own id before the row gained an override.
        assertEquals("ovr-2", initialExpandedIdentityId(rows, "srv-2"))
        assertEquals("ovr-2", initialExpandedIdentityId(rows, "ovr-2"))
    }

    @Test fun withNoDefaultTheFirstRowOfTheFirstGroupOpens() {
        val rows = listOf(server, serverAlias, manual)
        assertEquals("srv-1", initialExpandedIdentityId(rows, null))
        assertEquals("srv-1", initialExpandedIdentityId(rows, ""))
        // A default pointing at a row that no longer exists falls back the same way.
        assertEquals("srv-1", initialExpandedIdentityId(rows, "deleted"))
    }

    @Test fun aLoneIdentityIsAlwaysOpen() {
        assertEquals("man-3", initialExpandedIdentityId(listOf(manual), null))
        assertEquals("man-3", initialExpandedIdentityId(listOf(manual), "man-3"))
        assertEquals("man-3", initialExpandedIdentityId(listOf(manual), "gone"))
    }

    @Test fun noIdentityAtAllOpensNothing() {
        assertNull(initialExpandedIdentityId(emptyList(), null))
        assertNull(initialExpandedIdentityId(emptyList(), "man-3"))
    }

    @Test fun everyOtherRowStartsCollapsed() {
        val rows = listOf(server, serverAlias, manual)
        val open = setOfNotNull(initialExpandedIdentityId(rows, "srv-2"))
        assertEquals(setOf("ovr-2"), open)
        assertTrue(rows.filter { it.rowId !in open }.map { it.rowId } == listOf("srv-1", "man-3"))
    }

    // --- What a collapsed row says about its signature ---------------------------------------

    @Test fun aTextSignatureReadsAsText() {
        assertEquals(SignatureState.TEXT, signatureStateOf("Alex Rivera\nAcme", ""))
    }

    @Test fun anImportedHtmlSignatureReadsAsHtml() {
        // Importing HTML also stores the flattened text, so both halves are filled: HTML wins.
        assertEquals(SignatureState.HTML, signatureStateOf("Alex Rivera", "<p>Alex Rivera</p>"))
        assertEquals(SignatureState.HTML, signatureStateOf("", "<p>Alex Rivera</p>"))
    }

    @Test fun nothingStoredReadsAsNoSignature() {
        assertEquals(SignatureState.NONE, signatureStateOf("", ""))
        assertEquals(SignatureState.NONE, signatureStateOf("  \n ", "   "))
    }

    // --- Folding is display only --------------------------------------------------------------

    @Test fun tappingOpensThenClosesTheSameRow() {
        val opened = setOf("srv-1").toggleIdentityRow("man-3")
        assertEquals(setOf("srv-1", "man-3"), opened)
        assertEquals(setOf("srv-1"), opened.toggleIdentityRow("man-3"))
    }

    // --- The "-- " delimiter is shown, never stored (#90) --------------------------------------

    @Test fun thePreviewShowsTheDelimiterTheFieldDoesNotHold() {
        assertEquals("-- \nAlex Rivera\nAcme", signaturePreview("Alex Rivera\nAcme", delimiter = true))
    }

    @Test fun aBlankSignatureHasNothingToPreview() {
        assertNull(signaturePreview("", delimiter = true))
        assertNull(signaturePreview("   \n \n ", delimiter = true))
        assertNull(signaturePreview("", delimiter = false))
        assertNull(signaturePreview("   \n \n ", delimiter = false))
    }

    @Test fun thePreviewIsExactlyWhatTheComposerWillInsert() {
        // The one thing that must not drift: what is shown here is the block the composer appends,
        // minus only the blank line that separates it from the message above. True in BOTH
        // positions of the delimiter switch — the preview is derived from the same function.
        val signature = "Alex Rivera\nAcme"
        for (delimiter in listOf(true, false)) {
            val body = bodyWithSignature(quoted = "", signature = signature, delimiter = delimiter)
            assertEquals("\n\n" + signaturePreview(signature, delimiter), body)
            assertTrue(body.endsWith(signaturePreview(signature, delimiter)!!))
        }
    }

    @Test fun aDelimiterTypedByHandIsShownDoubled() {
        // Not stripped — the field holds the user's own text — but the preview makes it plain.
        assertEquals("-- \n-- \nAlex Rivera", signaturePreview("-- \nAlex Rivera", delimiter = true))
        assertTrue(signatureHasOwnDelimiter("-- \nAlex Rivera", delimiter = true))
        // Without the trailing space, and under a stray blank line the block would strip anyway.
        assertTrue(signatureHasOwnDelimiter("--\nAlex Rivera", delimiter = true))
        assertTrue(signatureHasOwnDelimiter("\n\n-- \nAlex Rivera", delimiter = true))
    }

    @Test fun ordinaryDashesAreNotADuplicateDelimiter() {
        assertFalse(signatureHasOwnDelimiter("Alex Rivera\n-- \nAcme", delimiter = true))
        assertFalse(signatureHasOwnDelimiter("---------- Acme ----------", delimiter = true))
        assertFalse(signatureHasOwnDelimiter("--- Alex", delimiter = true))
        assertFalse(signatureHasOwnDelimiter("Alex Rivera", delimiter = true))
        assertFalse(signatureHasOwnDelimiter("", delimiter = true))
    }

    // --- The delimiter switched off (#90) ------------------------------------------------------

    @Test fun withTheDelimiterOffThePreviewIsTheSignatureAlone() {
        assertEquals("Alex Rivera\nAcme", signaturePreview("Alex Rivera\nAcme", delimiter = false))
        // Whatever separator was typed into the field is shown, and only it.
        assertEquals("__\nAlex Rivera", signaturePreview("__\nAlex Rivera", delimiter = false))
    }

    @Test fun withTheDelimiterOffThereIsNoDuplicateToWarnAbout() {
        // A typed "-- " is then the user's chosen separator, not a second copy of the app's: the
        // preview shows one line and the warning has nothing left to say.
        assertEquals("-- \nAlex Rivera", signaturePreview("-- \nAlex Rivera", delimiter = false))
        assertFalse(signatureHasOwnDelimiter("-- \nAlex Rivera", delimiter = false))
        assertFalse(signatureHasOwnDelimiter("--\nAlex Rivera", delimiter = false))
        assertFalse(signatureHasOwnDelimiter("\n\n-- \nAlex Rivera", delimiter = false))
    }
}
