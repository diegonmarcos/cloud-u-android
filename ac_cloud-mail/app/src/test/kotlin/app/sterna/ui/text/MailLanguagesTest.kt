package app.sterna.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MailLanguagesTest {
    @Test fun `blank or missing means English`() {
        assertEquals("en", MailLanguages.normalise(null))
        assertEquals("en", MailLanguages.normalise(""))
        assertEquals("en", MailLanguages.normalise("  "))
        assertEquals("pt-br", MailLanguages.normalise("pt_BR"))
        assertEquals("en", MailLanguages.DEFAULT)
    }

    @Test fun `the list is the languages of the fleet's engine, English among them`() {
        for (tag in listOf("en", "es", "fr", "de", "pt", "it", "nl", "pl", "ru", "ja", "zh", "ar")) {
            assertTrue("$tag is missing", tag in MailLanguages.TAGS)
        }
        assertEquals("no duplicates", MailLanguages.TAGS.size, MailLanguages.TAGS.toSet().size)
    }

    @Test fun `rows are named and sorted by name`() {
        val rows = MailLanguages.options("en", Locale.ENGLISH)
        assertEquals(MailLanguages.TAGS.size, rows.size)
        assertEquals("English", rows.first { it.first == "en" }.second)
        assertEquals("Spanish", rows.first { it.first == "es" }.second)
        assertEquals(rows.map { it.second.lowercase() }.sorted(), rows.map { it.second.lowercase() })
    }

    @Test fun `a stored tag the list does not know is still shown, so the setting can be seen and changed`() {
        val rows = MailLanguages.options("xx-custom", Locale.ENGLISH)
        assertTrue(rows.any { it.first == "xx-custom" })
        assertEquals(MailLanguages.TAGS.size + 1, rows.size)
    }

    @Test fun `names follow the reader's language`() {
        assertEquals("Español", MailLanguages.nameOf("es", Locale.forLanguageTag("es")))
        assertEquals("Spanish", MailLanguages.nameOf("es", Locale.ENGLISH))
    }
}
