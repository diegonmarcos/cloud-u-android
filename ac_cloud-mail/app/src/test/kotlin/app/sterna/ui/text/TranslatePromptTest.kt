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
}
