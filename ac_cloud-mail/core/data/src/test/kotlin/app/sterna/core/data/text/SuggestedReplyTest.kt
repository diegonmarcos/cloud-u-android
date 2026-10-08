package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestedReplyTest {
    @Test fun `a summary and a reply are split on the marker line`() {
        val p = SuggestedReply.split("Short summary.\n${SuggestedReply.MARK}\nThanks, I will confirm tomorrow.\n")
        assertEquals("Short summary.", p.summary)
        assertEquals("Thanks, I will confirm tomorrow.", p.reply)
    }

    @Test fun `no marker or an empty reply keeps the summary and gives no reply`() {
        assertEquals(SummaryParts("only a summary", null), SuggestedReply.split(" only a summary \n"))
        assertNull(SuggestedReply.split("s\n${SuggestedReply.MARK}\n  ").reply)
    }

    @Test fun `join and split round trip`() {
        val parts = SummaryParts("- a\n- b", "Hola, gracias.")
        assertEquals(parts, SuggestedReply.split(SuggestedReply.join(parts)))
        assertEquals("only", SuggestedReply.join(SummaryParts("only", null)))
    }
}
