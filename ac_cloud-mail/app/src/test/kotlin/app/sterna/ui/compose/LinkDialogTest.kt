package app.sterna.ui.compose

import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.toPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions the link dialog makes (#131), EXECUTED — what it opens with, and what OK
 */
class LinkDialogTest {

    private fun sp(s: Int, e: Int) = Span(s, e)
    private fun cursor(c: Int) = Span(c, c)
    private fun link(s: Int, e: Int, url: String) = Link(sp(s, e), url)

    private val plain = RichBody.plain("hello world")
    private val linked = RichBody("hello world", emptyMap(), emptyList(), listOf(link(6, 11, "https://x")))

    // --- what the dialog opens with ---------------------------------------------------------

    @Test fun `a selection fills the text field and freezes it`() {
        assertEquals(
            LinkDialogFields(url = "", text = "world", textEditable = false, canRemove = false),
            linkDialogFields(plain, sp(6, 11)),
        )
    }

    @Test fun `a selection already linked offers its URL and the remove button`() {
        assertEquals(
            LinkDialogFields(url = "https://x", text = "world", textEditable = false, canRemove = true),
            linkDialogFields(linked, sp(6, 11)),
        )
    }

    @Test fun `a cursor inside a link opens on that link, text frozen to what it covers`() {
        assertEquals(
            "editing a link must show the words it is on, not an empty box",
            LinkDialogFields(url = "https://x", text = "world", textEditable = false, canRemove = true),
            linkDialogFields(linked, cursor(8)),
        )
    }

    @Test fun `a bare cursor opens empty, with the text field live and no remove`() {
        assertEquals(
            LinkDialogFields(url = "", text = "", textEditable = true, canRemove = false),
            linkDialogFields(plain, cursor(3)),
        )
        assertEquals(
            "at the very border of a link the cursor is OUTSIDE it, as everywhere else",
            LinkDialogFields(url = "", text = "", textEditable = true, canRemove = false),
            linkDialogFields(linked, cursor(11)),
        )
    }

    // --- what OK produces ----------------------------------------------------------------------

    @Test fun `OK on a selection lays the normalised URL on it`() {
        assertEquals(
            "the URL must go through the guard: `example.org` typed becomes `https://example.org`",
            RichBody("hello world", emptyMap(), emptyList(), listOf(link(6, 11, "https://example.org"))),
            linkApplied(plain, sp(6, 11), "  example.org ", text = "IGNORED"),
        )
    }

    @Test fun `OK on a cursor inserts the typed text and links it`() {
        assertEquals(
            RichBody("hello world!", emptyMap(), emptyList(), listOf(link(11, 12, "https://example.org"))),
            linkApplied(plain, cursor(11), "example.org", text = "!"),
        )
    }

    @Test fun `OK inside a link only changes the address`() {
        assertEquals(
            RichBody("hello world", emptyMap(), emptyList(), listOf(link(6, 11, "https://y"))),
            linkApplied(linked, cursor(9), "https://y", text = ""),
        )
    }

    @Test fun `OK with no label at all writes the address as its own words`() {
        // Without this, the fourth arm of `setLink` is reachable from the dialog: a bare cursor,
        // no link under it, the label box empty — the body comes back UNCHANGED, `onApply` runs,
        assertEquals(
            RichBody(
                "hello worldhttps://example.org",
                emptyMap(),
                emptyList(),
                listOf(link(11, 30, "https://example.org")),
            ),
            linkApplied(plain, cursor(11), "example.org", text = ""),
        )
    }

    @Test fun `a label that IS the address is written once in the plain-text alternative`() {
        // The other half of the same decision, executed: the two ends must agree about the shape
        // the rule above produces.
        val out = linkApplied(plain, cursor(11), "example.org", text = "")!!
        assertEquals("hello worldhttps://example.org", toPlainText(out))
    }

    @Test fun `a selection ignores the label box, empty or not`() {
        // The guard on the rule above: the address must NOT be inserted when characters are
        // already selected — those are the words the user chose, and `setLink` is right to ignore
        // the box. An `ifEmpty` applied one arm too far would append the URL to the message.
        assertEquals(
            RichBody("hello world", emptyMap(), emptyList(), listOf(link(6, 11, "https://example.org"))),
            linkApplied(plain, sp(6, 11), "example.org", text = ""),
        )
    }

    @Test fun `a cursor inside a link ignores the label box too`() {
        assertEquals(
            "editing an address may not touch the words it is on",
            RichBody("hello world", emptyMap(), emptyList(), listOf(link(6, 11, "https://y"))),
            linkApplied(linked, cursor(9), "https://y", text = ""),
        )
    }

    @Test fun `a refused URL produces nothing at all, and the body is untouched`() {
        for (bad in listOf("", "   ", "javascript:alert(1)", "data:text/html,x", "ftp://example.org")) {
            assertNull("`$bad` must be refused, with the message on the dialog", linkApplied(plain, sp(6, 11), bad, "w"))
        }
    }

    @Test fun `a label inserted into an item stays inside that item`() {
        // The lists half of this volet, and it is not decoration: `setLink` inserts through
        // `remapAfterEdit`, so the line the caret sits on keeps its block. Inserted any other way,
        // the label would land on a line the list no longer covers and the item would break in two
        // — one of them no longer an item at all.
        val list = RichBody("milk\neggs", emptyMap(), listOf(Block(BlockKind.BULLET, 0..1)))
        assertEquals(
            RichBody(
                "milk\neggs!",
                emptyMap(),
                listOf(Block(BlockKind.BULLET, 0..1)),
                listOf(link(9, 10, "https://x")),
            ),
            linkApplied(list, cursor(9), "https://x", text = "!"),
        )
    }

    @Test fun `a selection covering two whole items is offered as the one link it carries`() {
        // Two items, one address: stored as TWO links, because `</li><li>` carries no character.
        // Read as "no link", the dialog would offer to CREATE one with an empty address and
        // would not offer to remove anything, while `removeLink` would have done it.
        val two = RichBody(
            "milk\neggs",
            emptyMap(),
            listOf(Block(BlockKind.BULLET, 0..1)),
            listOf(link(0, 4, "https://x"), link(5, 9, "https://x")),
        )
        assertEquals(
            LinkDialogFields(url = "https://x", text = "milk\neggs", textEditable = false, canRemove = true),
            linkDialogFields(two, sp(0, 9)),
        )
    }

    @Test fun `the styling around an inserted link is not disturbed`() {
        val bold = RichBody("hello", mapOf(Inline.BOLD to listOf(sp(0, 5))))
        assertEquals(
            "the inserted label is roman, and the bold it fell inside is cut in two",
            RichBody("hel!!lo", mapOf(Inline.BOLD to listOf(sp(0, 3), sp(5, 7))), emptyList(), listOf(link(3, 5, "https://x"))),
            linkApplied(bold, cursor(3), "https://x", text = "!!"),
        )
    }
}
