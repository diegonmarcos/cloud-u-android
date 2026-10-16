package app.sterna.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNameTest {
    @Test fun `date then subject then extension`() {
        assertEquals("2026-09-24 Rapport.mhtml", exportFileName("2026-09-24T10:00:00Z", "Rapport", "mhtml"))
    }

    @Test fun `hostile characters whitespace and edge dots are cleaned`() {
        assertEquals("2026-09-24 Re_ a_b_c_ _ d.eml", exportFileName("2026-09-24T10:00:00Z", "  Re: a/b\\c*  \n d  ", "eml"))
        assertEquals("2026-09-24 hidden.zip", exportFileName("2026-09-24T10:00:00Z", "...hidden...", "zip"))
    }

    @Test fun `blank or missing subject falls back`() {
        assertEquals("2026-09-24 message.html", exportFileName("2026-09-24T10:00:00Z", "   ", "html"))
        assertEquals("message.html", exportFileName(null, null, ".html"))
    }

    @Test fun `bad date is dropped, long subject is capped`() {
        assertEquals("x.eml", exportFileName("yesterday", "x", "eml"))
        val n = exportFileName("2026-09-24T10:00:00Z", "s".repeat(500), "eml")
        assertTrue(n.length <= 11 + 80 + 4)
    }
}
