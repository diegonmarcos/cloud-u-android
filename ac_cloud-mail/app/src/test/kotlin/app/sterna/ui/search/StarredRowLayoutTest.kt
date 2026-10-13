package app.sterna.ui.search

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Starred (the search screen on `$flagged`) draws the SAME row as every folder: the star toggle in the
 * right slot and the attachment chips. It used to call EmailListItem without a star handler and
 * without an attachment handler, so the row fell back to the attachment icon in the star's slot and
 * drew no chips.
 */
class StarredRowLayoutTest {

    private val pdf = EmailBodyPart(partId = "2", type = "application/pdf", name = "a.pdf", disposition = "attachment", size = 10)

    /** The EmailListItem call of the results list, as written. */
    private fun searchRowCall(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/res/values/strings.xml").isFile }
        val src = File(root, "app/src/main/kotlin/app/sterna/ui/search/SearchScreen.kt").readText()
        val at = src.indexOf("EmailListItem(")
        assertTrue("the results list draws EmailListItem", at >= 0)
        return src.substring(at, src.indexOf("\n                                        )", at))
    }

    @Test fun `the starred row puts the star toggle in the right slot`() {
        assertTrue("onToggleFavourite is handed to the shared row", searchRowCall().contains("onToggleFavourite = { viewModel.toggleFlag(email) }"))
    }

    @Test fun `the starred row shows the attachment chips`() {
        assertTrue("onOpenAttachment is handed to the shared row", searchRowCall().contains("onOpenAttachment = { part -> viewModel.openAttachment(email, part) }"))
    }

    @Test fun `a hit gets its attachments from the cached copy of the same message`() {
        val hit = Email(id = "M1", accountId = "a")
        val cached = Email(id = "M1", accountId = "a", attachments = listOf(pdf))
        val other = Email(id = "M1", accountId = "b", attachments = listOf(pdf))
        assertEquals(listOf(pdf), withCachedParts(listOf(hit), listOf(cached)).single().attachments)
        assertTrue("never another account's copy", withCachedParts(listOf(hit), listOf(other)).single().attachments.isEmpty())
    }

    @Test fun `unstarring clears the star of that row only`() {
        val starred = mapOf("\$flagged" to true)
        val rows = listOf(Email(id = "M1", accountId = "a", keywords = starred), Email(id = "M2", accountId = "a", keywords = starred))
        val after = withFlag(rows, searchResultKey(rows[0]), flagged = false)
        assertFalse(after[0].isFlagged)
        assertTrue(after[1].isFlagged)
        assertTrue(withFlag(after, searchResultKey(rows[0]), flagged = true)[0].isFlagged)
    }
}
