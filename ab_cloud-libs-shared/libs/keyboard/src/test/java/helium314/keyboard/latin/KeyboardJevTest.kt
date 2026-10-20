package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #883 keyboard_style: THE FALLBACK TEST. No advice (no engine, no consent, a closed gate, unsure, an unknown id)
 * and every style the owner chose keep the style the keyboard always used; the model sees the shape, never the text.
 */
class KeyboardJevTest {

    private val styles = listOf(
        AiRouter.Style("improve", "Improve", "p1"),
        AiRouter.Style("formal", "Formal", "p2"),
    )

    @Test fun noAdviceKeepsTheDefaultStyle() {
        for (pick in listOf(null, "", "unknown", "FORMAL")) assertNull("pick=$pick", KeyboardJev.applies(false, pick, styles))
    }

    @Test fun aStyleTheOwnerChoseIsNeverReplaced() {
        assertNull(KeyboardJev.applies(true, "formal", styles))
    }

    @Test fun anOfferedStyleIsAcceptedWhenNothingWasChosen() {
        assertEquals("formal", KeyboardJev.applies(false, "formal", styles)?.id)
    }

    @Test fun theStateIsTheShapeOfTheTextNotTheText() {
        val secret = "my password is hunter2 https://example.org\nsecond line"
        val s = KeyboardJev.shape(secret)
        assertEquals(secret.length, s.getInt("chars"))
        assertEquals(2, s.getInt("lines"))
        assertTrue(s.getBoolean("has_link"))
        assertFalse(s.toString().contains("hunter2"))
        assertFalse(s.toString().contains("example.org"))
    }

    @Test fun theQuestionOffersEveryRegistryStyle() {
        val crit = KeyboardJev.questions(styles).getJSONObject("style").getJSONObject("criteria")
        assertEquals(setOf("improve", "formal"), crit.keys().asSequence().toSet())
    }
}
