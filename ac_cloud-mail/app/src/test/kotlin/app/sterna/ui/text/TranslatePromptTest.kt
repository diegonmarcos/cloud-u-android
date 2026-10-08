package app.sterna.ui.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatePromptTest {
    @Test fun `an unknown source is left to the model, a known one is stated`() {
        val auto = llmTranslatePrompt("en", null)
        assertTrue(auto.contains("into English") && auto.contains("detect the source language yourself"))
        val known = llmTranslatePrompt("en", "de")
        assertTrue(known.contains("from German into English"))
        assertFalse(known.contains("detect the source language"))
    }

    @Test fun `the prompt keeps the line pairing and the protected tokens`() {
        val p = llmTranslatePrompt("fr", "es")
        assertTrue(p.contains("EXACTLY the same number of lines"))
        assertTrue(p.contains("⟦0⟧") && p.contains("web addresses"))
    }

    @Test fun `the summary prompt asks for both parts, the reply in the message's language`() {
        val known = summaryWithReplyPrompt("Summarise the message.", "de")
        assertTrue(known.startsWith("Summarise the message."))
        assertTrue("the marker the answer is split on", known.contains(app.sterna.core.data.text.SuggestedReply.MARK))
        assertTrue(known.contains("written in German"))
        val unknown = summaryWithReplyPrompt("Summarise the message.", null)
        assertTrue(unknown.contains("the language the message is written in"))
        assertTrue("one reply, not an essay", known.contains("ONE short suggested reply"))
    }
}
